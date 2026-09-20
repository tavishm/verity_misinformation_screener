"""Regression tests for conservative claim-transfer and cache boundaries."""
import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from .claim_graph import ClaimGraph, TTL_SECONDS, assessment_key, compatibility
from .pipeline import VerificationEngine


class IndexStub:
    revision = "rev-1"

    def search(self, query, limit=5):
        return []


def supported_result(text, checked_at="seed"):
    return {
        "rating": 4,
        "label": "Probably true",
        "claims": [{
            "text": text,
            "verdict": "supported",
            "evidence": [{
                "source_url": "https://www.federalregister.gov/documents/example",
                "document_version": "v1",
                "title": "Official table",
                "quote": "Alice received 10 grants and Bob received 20 grants.",
            }],
        }],
        "extracted_claims_resolved": True,
        "whole_post_validated": True,
        "checked_at": checked_at,
        "reuse": {"kind": "fresh_review"},
    }


class PipelineSafetyAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.graph = ClaimGraph(Path(self.temp.name) / "claims.sqlite3")

    def tearDown(self):
        self.graph.db.close()
        self.temp.cleanup()

    def engine(self, ask=None, scorer=None):
        async def default_ask(prompt, payload, tokens):
            return {"kind": "no_factual_claim"}, {}

        return VerificationEngine(
            IndexStub(), self.graph, ask or default_ask,
            lambda claims, verified, sources: [], "extract", "verify",
            scorer=scorer,
        )

    def test_same_numbers_swapped_between_entities_are_vetoed_and_not_reused(self):
        canonical = "Alice received 10 grants and Bob received 20 grants."
        swapped = "Alice received 20 grants and Bob received 10 grants."
        check = compatibility(canonical, swapped)
        self.assertFalse(check["compatible"])
        self.assertIn("ordered_numbers", check["reasons"])
        post = {"platform": "demo", "post_id": "canonical", "relation": "original"}
        key = assessment_key(canonical, False, post, "rev-1")
        with patch("demo.claim_graph.time.time", return_value=1000):
            self.graph.record(key, canonical, False, post, "rev-1",
                              supported_result(canonical))

        async def permissive_scorer(pairs):
            return [1.0] * len(pairs)

        with patch("demo.claim_graph.time.time", return_value=1001):
            reused = asyncio.run(self.engine(scorer=permissive_scorer).reuse_claim(
                swapped, "rev-1", {}))
        self.assertIsNone(reused)

    def test_swapped_entities_with_unchanged_number_order_still_get_fresh_review(self):
        canonical = "Alice received 10 grants and Bob received 20 grants."
        swapped_entities = "Bob received 10 grants and Alice received 20 grants."
        # Ordered values alone cannot establish which entity owns each value.
        self.assertTrue(compatibility(canonical, swapped_entities)["compatible"])
        post = {"platform": "demo", "post_id": "canonical", "relation": "original"}
        key = assessment_key(canonical, False, post, "rev-1")
        with patch("demo.claim_graph.time.time", return_value=1000):
            self.graph.record(key, canonical, False, post, "rev-1",
                              supported_result(canonical))

        async def permissive_scorer(pairs):
            return [1.0] * len(pairs)

        with patch("demo.claim_graph.time.time", return_value=1001):
            reused = asyncio.run(self.engine(scorer=permissive_scorer).reuse_claim(
                swapped_entities, "rev-1", {}))
        self.assertIsNone(reused)

    def test_combined_quote_cache_expires_no_later_than_original_assessment(self):
        quoted = "Congress passed the bill on September 18, 2026."
        wrapper = "Look at this."
        wrapper_post = {
            "platform": "x", "post_id": "wrapper-1", "relation": "quote",
            "quoted_text": quoted, "quoted_post_id": "original-1",
            "quoted_published_at": "2026-09-18T12:00:00Z",
        }
        original_post = {
            "platform": "x", "post_id": "original-1", "relation": "original",
            "published_at": "2026-09-18T12:00:00Z",
        }
        original_key = assessment_key(quoted, False, original_post, "rev-1")
        with patch("demo.claim_graph.time.time", return_value=1000):
            seeded = supported_result(quoted)
            seeded.update(assessment_key=original_key, source_revision="rev-1")
            self.graph.record(original_key, quoted, False, original_post, "rev-1",
                              seeded)

        # Build the combined wrapper shortly before the original expires.
        with patch("demo.claim_graph.time.time", return_value=1000 + TTL_SECONDS - 10):
            combined = asyncio.run(self.engine().review(wrapper, post=wrapper_post))
        self.assertIn("original_assessment", combined)
        wrapper_key = combined["assessment_key"]
        original_expiry = self.graph.db.execute(
            "SELECT expires_at FROM reviews WHERE cache_key=?", (original_key,)).fetchone()[0]
        wrapper_expiry = self.graph.db.execute(
            "SELECT expires_at FROM reviews WHERE cache_key=?", (wrapper_key,)).fetchone()[0]
        self.assertLessEqual(wrapper_expiry, original_expiry)

        with patch("demo.claim_graph.time.time", return_value=1000 + TTL_SECONDS + 1):
            self.assertIsNone(self.graph.cached(original_key, "rev-1"))
            self.assertIsNone(self.graph.cached(wrapper_key, "rev-1"))

    def test_plain_post_cannot_inherit_claim_reviewed_only_with_quote_or_media_context(self):
        claim = "Congress passed the bill on September 18, 2026."
        contextual_cases = (
            (False, {
                "platform": "x", "post_id": "quoted", "relation": "quote",
                "quoted_text": "A different contextual statement.",
                "quoted_post_id": "quoted-original",
            }),
            (True, {
                "platform": "x", "post_id": "media", "relation": "original",
                "media_fingerprint": "sha256:context-image",
            }),
        )
        contextual_ids = []
        with patch("demo.claim_graph.time.time", return_value=1000):
            for has_media, post in contextual_cases:
                key = assessment_key(claim, has_media, post, "rev-1")
                result = supported_result(claim)
                self.graph.record(key, claim, has_media, post, "rev-1", result)
                contextual_ids.append(result["claims"][0]["canonical_id"])

        rows = self.graph.db.execute(
            "SELECT id,reusable FROM claims WHERE id IN (?,?)", contextual_ids).fetchall()
        self.assertEqual(len(rows), 2)
        self.assertTrue(all(row["reusable"] == 0 for row in rows))
        self.assertNotEqual(contextual_ids[0], contextual_ids[1])
        self.assertEqual(self.graph.candidates(claim, "rev-1"), [])

        with patch("demo.claim_graph.time.time", return_value=1001):
            reused = asyncio.run(self.engine().reuse_claim(
                claim, "rev-1", {"platform": "x", "post_id": "plain", "relation": "original"}))
        self.assertIsNone(reused)


if __name__ == "__main__":
    unittest.main()
