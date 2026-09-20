import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from .claim_graph import ClaimGraph, TTL_SECONDS
from .evidence import EvidenceIndex
from .pipeline import VerificationEngine


SOURCE_URL = "https://www.congress.gov/example"


def source(text, url=SOURCE_URL):
    return {
        "text": text,
        "source_url": url,
        "title": "Congressional record",
        "language": "en",
        "published_at": "2026-09-18T18:00:00Z",
        "fetched_at": "2026-09-19T08:00:00Z",
        "source_kind": "primary",
    }


class FakeModel:
    def __init__(self, verdict="supported", mutate_index=None):
        self.verdict = verdict
        self.mutate_index = mutate_index
        self.calls = []

    async def __call__(self, prompt, payload, tokens):
        self.calls.append((prompt, payload, tokens))
        usage = {"input_tokens": 10, "output_tokens": 5, "generation_seconds": .01}
        if prompt == "extract":
            return {"kind": "factual", "queries": [payload["post"]]}, usage
        if self.mutate_index is not None:
            index, record = self.mutate_index
            index.ingest_records([record])
            self.mutate_index = None
        return {
            "claims": [{"id": row["id"], "verdict": self.verdict,
                        "evidence": [{"source_id": payload["sources"][0]["source_id"]}]}
                       for row in payload["claims"]]
        }, usage


def validate(claims, raw, sources):
    by_id = {row["id"]: row for row in raw.get("claims", [])}
    output = []
    for claim_id, claim in enumerate(claims):
        row = by_id.get(claim_id, {})
        verdict = row.get("verdict", "insufficient_evidence")
        evidence = []
        for citation in row.get("evidence", []):
            hit = sources.get(citation.get("source_id"))
            if hit:
                evidence.append({**hit, "quote": hit["text"]})
        output.append({"text": claim["text"], "verdict": verdict,
                       "evidence": evidence,
                       "explanation": "Deterministic test verifier."})
    return output


class FakeScorer:
    def __init__(self, score=1.0):
        self.score = score
        self.calls = []

    async def __call__(self, pairs):
        self.calls.append(pairs)
        return [self.score] * len(pairs)


class FailOnceDuringOriginal(FakeModel):
    """Wrapper uses calls 1-2; fail the quoted original's extraction once."""
    async def __call__(self, prompt, payload, tokens):
        if len(self.calls) == 2:
            self.calls.append((prompt, payload, tokens))
            raise RuntimeError("simulated original-review failure")
        return await super().__call__(prompt, payload, tokens)


class PipelineTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.index = EvidenceIndex(root / "evidence.sqlite3")
        self.graph = ClaimGraph(root / "graph.sqlite3")
        self.index.ingest_records([source(
            "Congress passed the climate bill on September 18, 2026. "
            "Biden said 12 bills passed and did not say 13 bills passed."
        )])

    def tearDown(self):
        self.index.close()
        self.graph.db.close()
        self.directory.cleanup()

    def engine(self, model=None, scorer=None):
        model = model or FakeModel()
        scorer = scorer or FakeScorer()
        return VerificationEngine(self.index, self.graph, model, validate,
                                  "extract", "verify", scorer=scorer), model, scorer

    async def test_exact_cache_skips_all_model_generation_and_does_not_extend_ttl(self):
        engine, model, _ = self.engine()
        text = "Congress passed the climate bill on September 18, 2026."
        post = {"platform": "demo", "post_id": "p1", "relation": "unknown"}
        with patch("demo.claim_graph.time.time", return_value=1000):
            first = await engine.review(text, post=post)
        calls = len(model.calls)
        row = self.graph.db.execute("SELECT expires_at FROM reviews WHERE cache_key=?",
                                    (first["assessment_key"],)).fetchone()
        self.assertEqual(row["expires_at"], 1000 + TTL_SECONDS)

        with patch("demo.claim_graph.time.time", return_value=2000):
            second = await engine.review(text, post=post)
        self.assertEqual(len(model.calls), calls)
        self.assertEqual(second["reuse"]["kind"], "exact_reuse")
        reread = self.graph.db.execute("SELECT expires_at FROM reviews WHERE cache_key=?",
                                       (first["assessment_key"],)).fetchone()
        self.assertEqual(reread["expires_at"], 1000 + TTL_SECONDS)

    async def test_hard_qualifier_mutants_get_new_verification_never_claim_reuse(self):
        engine, model, _ = self.engine()
        canonical = "Biden said Congress passed 12 bills on September 18, 2026."
        await engine.review(canonical, post={"platform": "demo", "post_id": "canonical", "relation": "unknown"})
        mutants = (
            "Biden said Congress passed 13 bills on September 18, 2026.",
            "Biden said Congress passed 12 bills on September 19, 2026.",
            "Harris said Congress passed 12 bills on September 18, 2026.",
            "Biden said Congress did not pass 12 bills on September 18, 2026.",
        )
        for number, text in enumerate(mutants):
            before = len(model.calls)
            reviewed = await engine.review(text, post={"platform": "demo", "post_id": "m" + str(number), "relation": "unknown"})
            with self.subTest(text=text):
                self.assertGreater(len(model.calls), before)
                self.assertNotEqual(reviewed["reuse"]["kind"], "claim_reuse")

    async def test_below_threshold_semantic_candidate_is_reverified(self):
        scorer = FakeScorer(.98)
        engine, model, _ = self.engine(scorer=scorer)
        canonical = "Congress passed the climate bill."
        await engine.review(canonical, post={"platform": "demo", "post_id": "canonical", "relation": "unknown"})
        before = len(model.calls)
        reviewed = await engine.review("Congress passed the climate legislation.",
                                       post={"platform": "demo", "post_id": "variant", "relation": "unknown"})
        self.assertTrue(scorer.calls, "the near-match should reach the conservative scorer threshold")
        self.assertGreater(len(model.calls), before)
        self.assertNotEqual(reviewed["reuse"]["kind"], "claim_reuse")

    async def test_low_entailment_score_never_becomes_contradiction(self):
        scorer = FakeScorer(.1)
        engine, _, _ = self.engine(scorer=scorer)
        await engine.review("Congress passed the climate bill.",
                            post={"platform": "demo", "post_id": "canonical", "relation": "unknown"})
        engine.ask.verdict = "insufficient_evidence"
        reviewed = await engine.review("Congress passed the climate legislation.",
                                       post={"platform": "demo", "post_id": "variant", "relation": "unknown"})
        self.assertTrue(scorer.calls)
        self.assertEqual(reviewed["label"], "Unsure")
        self.assertEqual(reviewed["claims"][0]["verdict"], "insufficient_evidence")
        self.assertNotEqual(reviewed["rating"], 2)

    async def test_quote_wrapper_and_original_are_assessed_separately(self):
        engine, _, _ = self.engine()
        reviewed = await engine.review(
            "The quoted vote count is wrong.",
            post={"platform": "demo", "post_id": "quote", "relation": "quote",
                  "quoted_post_id": "original", "quoted_text": "Congress passed the climate bill."},
        )
        self.assertFalse(reviewed["whole_post_validated"])
        self.assertIn("original_assessment", reviewed)
        self.assertIn("does not verify the wrapper", reviewed["original_assessment"]["scope"])
        self.assertNotEqual(reviewed["assessment_key"], reviewed["original_assessment"]["assessment_key"])

    async def test_failed_original_review_cannot_leave_a_partial_wrapper_cache(self):
        model = FailOnceDuringOriginal()
        engine, _, _ = self.engine(model=model)
        text = "The quoted vote count is wrong."
        post = {"platform": "demo", "post_id": "quote", "relation": "quote",
                "quoted_post_id": "original", "quoted_text": "Congress passed the climate bill."}
        with self.assertRaisesRegex(RuntimeError, "original-review failure"):
            await engine.review(text, post=post)

        # The retry must finish the missing separately-scoped original rather
        # than treating the partially persisted wrapper as a complete cache hit.
        retried = await engine.review(text, post=post)
        self.assertIn("original_assessment", retried)

    async def test_unanchored_relative_time_abstains_even_if_model_supports(self):
        engine, _, _ = self.engine()
        reviewed = await engine.review("Congress passed the climate bill today.",
                                       post={"platform": "demo", "post_id": "relative", "relation": "unknown"})
        self.assertEqual(reviewed["reuse"]["kind"], "abstained")
        self.assertEqual(reviewed["claims"][0]["verdict"], "insufficient_evidence")
        self.assertIn("publication date", reviewed["claims"][0]["explanation"])

    async def test_source_revision_change_during_verification_fails_closed(self):
        replacement = source("Congress rejected the climate bill.")
        model = FakeModel(mutate_index=(self.index, replacement))
        engine, _, _ = self.engine(model=model)
        with self.assertRaisesRegex(ValueError, "Sources changed"):
            await engine.review("Congress passed the climate bill.",
                                post={"platform": "demo", "post_id": "race", "relation": "unknown"})
        self.assertEqual(self.graph.db.execute("SELECT COUNT(*) FROM reviews").fetchone()[0], 0)

    async def test_inherited_claim_expiry_is_capped_at_canonical_review_age(self):
        scorer = FakeScorer(1.0)
        engine, _, _ = self.engine(scorer=scorer)
        canonical = "Congress passed the climate bill."
        with patch("demo.claim_graph.time.time", return_value=1000):
            await engine.review(canonical, post={"platform": "demo", "post_id": "canonical", "relation": "unknown"})
        with patch("demo.claim_graph.time.time", return_value=2000):
            inherited = await engine.review("Congress passed the climate legislation.",
                                            post={"platform": "demo", "post_id": "variant", "relation": "unknown"})
        self.assertEqual(inherited["reuse"]["kind"], "claim_reuse")
        expiry = self.graph.db.execute("SELECT expires_at FROM reviews WHERE cache_key=?",
                                       (inherited["assessment_key"],)).fetchone()["expires_at"]
        self.assertEqual(expiry, 1000 + TTL_SECONDS)

    async def test_false_attribution_cannot_be_displayed_as_wording_confirmed(self):
        engine, _, _ = self.engine()
        evidence=[{'source_url':'https://www.federalregister.gov/example','document_version':'v1',
                   'source_kind':'official_federal_rule','quote':'The rule has no legal effect.'}]
        rows=[{'text':'The Department says the rule remains in effect.','verdict':'contradicted','evidence':evidence}]
        result=engine.finish(rows[0]['text'],False,{},self.index.revision,rows,[],'development','fresh_review','new review')
        self.assertEqual(result['label'],'Likely false')
        self.assertEqual(result['rating'],2)

    async def test_exact_source_quotation_needs_no_model_and_never_certifies_world_truth(self):
        engine, model, scorer = self.engine()
        quote='Congress passed the climate bill on September 18, 2026.'
        text='The document titled "Congressional record" states: "'+quote+'".'
        result=await engine.review(text)
        self.assertEqual(result['label'],'Source wording confirmed')
        self.assertFalse(result['whole_post_validated'])
        self.assertEqual(result['claims'][0]['evidence'][0]['quote'],quote)
        self.assertEqual(model.calls,[])
        self.assertEqual(scorer.calls,[])
        forged=await engine.review(text.replace('September 18','September 19'))
        self.assertEqual(forged['label'],'Unsure')
        self.assertFalse(forged['extracted_claims_resolved'])
        self.assertEqual(model.calls,[])


if __name__ == "__main__":
    unittest.main()
