import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from .claim_graph import ClaimGraph, TTL_SECONDS, assessment_key, compatibility


def result(*, whole=True, text=True, verdict="supported", reuse="fresh_review"):
    return {
        "label": "Supported" if verdict == "supported" else "Unresolved",
        "whole_post_validated": whole,
        "extracted_claims_resolved": text,
        "claims": [{
            "text": "Congress passed the bill on September 18, 2026.",
            "verdict": verdict,
            "evidence": [{
                "title": "Roll call",
                "source_url": "https://www.congress.gov/example",
                "document_version": "v1",
                "quote": "The bill passed on September 18, 2026.",
            }],
        }],
        "reuse": {"kind": reuse},
    }


class ClaimGraphTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "claims.sqlite3"
        self.graph = ClaimGraph(self.path)

    def tearDown(self):
        self.graph.db.close()
        self.temp.cleanup()

    def test_hard_qualifier_changes_veto_semantic_inheritance(self):
        canonical = "Biden said Congress passed all 12 bills on September 18, 2026."
        variants = {
            "numbers": "Biden said Congress passed all 13 bills on September 18, 2026.",
            "dates": "Biden said Congress passed all 12 bills on September 19, 2026.",
            "negation": "Biden said Congress did not pass all 12 bills on September 18, 2026.",
            "attribution": "Congress passed all 12 bills on September 18, 2026.",
            "states": "Biden said Congress proposed all 12 bills on September 18, 2026.",
            "entities": "Harris said Congress passed all 12 bills on September 18, 2026.",
            "quantifiers": "Biden said Congress passed some 12 bills on September 18, 2026.",
        }
        for expected_reason, candidate in variants.items():
            with self.subTest(expected_reason=expected_reason):
                check = compatibility(candidate, canonical)
                self.assertFalse(check["compatible"])
                self.assertIn(expected_reason, check["reasons"])

    def test_relative_time_is_never_inherited_even_when_wording_matches(self):
        check = compatibility("Congress passed the bill today.", "Congress passed the bill today.")
        self.assertFalse(check["compatible"])
        self.assertIn("relative_time", check["reasons"])

    def test_native_repost_requires_no_wrapper_and_known_matching_media(self):
        original = {
            "platform": "x", "post_id": "original-1", "relation": "original",
            "media_fingerprint": "sha256:media-a",
        }
        text = "Congress passed the bill on September 18, 2026."
        self.graph.record("original-review", text, True, original, "rev-1", result())

        repost = {
            "platform": "x", "post_id": "repost-1", "original_post_id": "original-1",
            "relation": "native_repost", "media_fingerprint": "sha256:media-a",
        }
        self.assertTrue(self.graph.original_match(text, True, repost))

        for change in (
            {"media_fingerprint": None},
            {"media_fingerprint": "sha256:different"},
            {"has_commentary": True},
            {"quoted_text": "Actually, this is wrong."},
            {"relation": "quote"},
        ):
            with self.subTest(change=change):
                candidate = {**repost, **change}
                self.assertFalse(self.graph.original_match(text, True, candidate))

    def test_plain_repost_does_not_match_an_original_with_media(self):
        text = "Congress passed the bill."
        original = {"platform": "x", "post_id": "with-media", "relation": "original", "media_fingerprint": "asset-1"}
        self.graph.record("media-review", text, True, original, "rev-1", result())
        repost = {"platform": "x", "post_id": "r", "original_post_id": "with-media", "relation": "native_repost"}
        self.assertFalse(self.graph.original_match(text, False, repost))

    def test_reviews_persist_but_expire_and_are_revision_scoped(self):
        text = "Congress passed the bill."
        post = {"platform": "demo", "post_id": "p1", "relation": "original"}
        key = assessment_key(text, False, post, "rev-1")
        with patch("demo.claim_graph.time.time", return_value=1000):
            self.graph.record(key, text, False, post, "rev-1", result())

        self.graph.db.close()
        self.graph = ClaimGraph(self.path)
        with patch("demo.claim_graph.time.time", return_value=1001):
            self.assertIsNotNone(self.graph.cached(key, "rev-1"))
            self.assertIsNone(self.graph.cached(key, "rev-2"))
        with patch("demo.claim_graph.time.time", return_value=1000 + TTL_SECONDS + 1):
            self.assertIsNone(self.graph.cached(key, "rev-1"))

    def test_snapshot_drops_assessment_when_review_revision_changes_or_expires(self):
        text = "Congress passed the bill."
        post = {"platform": "demo", "post_id": "p1", "relation": "original"}
        key = assessment_key(text, False, post, "rev-1")
        with patch("demo.claim_graph.time.time", return_value=1000):
            self.graph.record(key, text, False, post, "rev-1", result())
            self.graph.observe({"scope": "live_feed", "session_id": "s", "observation_id": "o", "text": text})
            self.graph.attach("o", "s", "live_feed", key, result())
        with patch("demo.claim_graph.time.time", return_value=1001):
            self.assertEqual(self.graph.snapshot("rev-1")["stats"]["live_feed"]["decisive"], 1)
            changed = self.graph.snapshot("rev-2")["stats"]["live_feed"]
            self.assertEqual(changed["observed"], 1)
            self.assertEqual(changed["assessed"], 0)
            self.assertEqual(changed["decisive"], 0)
        with patch("demo.claim_graph.time.time", return_value=1000 + TTL_SECONDS + 1):
            expired = self.graph.snapshot("rev-1")["stats"]["live_feed"]
            self.assertEqual(expired["observed"], 1)
            self.assertEqual(expired["assessed"], 0)

    def test_coverage_excludes_media_quotes_and_unresolved_results(self):
        cases = (
            ("plain", {}, result(), 1, 1),
            ("media", {"has_media": True}, result(), 0, 1),
            ("quote", {"has_quoted_content": True}, result(), 0, 1),
            ("unsure", {}, result(whole=False, text=False, verdict="insufficient_evidence"), 0, 0),
        )
        with patch("demo.claim_graph.time.time", return_value=1000):
            for name, observation, review, _, _ in cases:
                text = "Congress passed the bill " + name
                post = {"platform": "demo", "post_id": name, "relation": "original"}
                key = assessment_key(text, observation.get("has_media", False), post, "rev-1")
                self.graph.record(key, text, observation.get("has_media", False), post, "rev-1", review)
                self.graph.observe({"scope": "live_feed", "session_id": "s", "observation_id": name, "text": text, **observation})
                self.graph.attach(name, "s", "live_feed", key, review)

        with patch("demo.claim_graph.time.time", return_value=1100):
            stats = self.graph.snapshot("rev-1")["stats"]["live_feed"]
        self.assertEqual(stats["observed"], 4)
        self.assertEqual(stats["assessed"], 4)
        self.assertEqual(stats["decisive"], 1)
        self.assertEqual(stats["text_decisive"], 3)
        self.assertEqual(stats["coverage"], .25)

        ignored = self.graph.observe({"scope": "untracked", "session_id": "s", "observation_id": "ignored", "text": "text"})
        self.assertIsNone(ignored)
        with patch("demo.claim_graph.time.time", return_value=1100):
            self.assertEqual(self.graph.snapshot("rev-1")["stats"]["live_feed"]["observed"], 4)

    def test_prelabelled_means_review_existed_before_first_observation(self):
        before_text = "A review existed before this exposure."
        before_post = {"platform": "demo", "post_id": "before", "relation": "original"}
        before_key = assessment_key(before_text, False, before_post, "rev-1")
        with patch("demo.claim_graph.time.time", return_value=1000):
            self.graph.record(before_key, before_text, False, before_post, "rev-1", result())
        with patch("demo.claim_graph.time.time", return_value=1010):
            self.graph.observe({"scope": "live_feed", "session_id": "s", "observation_id": "before", "text": before_text})
            self.graph.attach("before", "s", "live_feed", before_key, result())

        after_text = "This review was created after its exposure."
        after_post = {"platform": "demo", "post_id": "after", "relation": "original"}
        after_key = assessment_key(after_text, False, after_post, "rev-1")
        with patch("demo.claim_graph.time.time", return_value=1020):
            self.graph.observe({"scope": "live_feed", "session_id": "s", "observation_id": "after", "text": after_text})
        with patch("demo.claim_graph.time.time", return_value=1030):
            self.graph.record(after_key, after_text, False, after_post, "rev-1", result())
            self.graph.attach("after", "s", "live_feed", after_key, result())

        with patch("demo.claim_graph.time.time", return_value=1040):
            stats = self.graph.snapshot("rev-1")["stats"]["live_feed"]
        self.assertEqual(stats["observed"], 2)
        self.assertEqual(stats["decisive"], 2)
        self.assertEqual(stats["prelabelled"], 1)
        self.assertEqual(stats["prelabelled_coverage"], .5)

    def test_duplicate_observation_does_not_inflate_denominator(self):
        observation = {"scope": "development", "session_id": "s", "observation_id": "same", "text": "one"}
        self.graph.observe(observation)
        self.graph.observe({**observation, "text": "changed later"})
        self.assertEqual(self.graph.snapshot("rev-1")["stats"]["development"]["observed"], 1)


if __name__ == "__main__":
    unittest.main()
