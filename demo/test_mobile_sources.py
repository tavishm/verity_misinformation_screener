import asyncio
import json
from pathlib import Path
import tempfile
import unittest

from demo import mobile_sources


class Index:
    def __init__(self): self.rows = []
    def ingest_records(self, rows): self.rows.extend(rows); return {"documents": len(rows)}


def page(url):
    return {"url": url, "title": "Official reference", "text": "Official reference text with enough content for a bounded evidence record.", "truncated": False}


class MobileSourcesTests(unittest.TestCase):
    def setUp(self): mobile_sources._PUBLIC_DOCUMENT_CACHE.clear()

    def test_fixed_seed_has_provenance_but_no_verdicts(self):
        with tempfile.TemporaryDirectory() as directory:
            result = mobile_sources.fetch_mobile_sources(Path(directory), fetcher=page)
            self.assertEqual(len(result["records"]), 5)
            rows = [json.loads(line) for line in Path(directory, "corpus.jsonl").read_text().splitlines()]
            for row in rows:
                self.assertEqual(row["source_kind"], "medical_reference")
                self.assertTrue(row["retrieved_at"])
                self.assertEqual(len(row["text_sha256"]), 64)
                self.assertNotIn("verdict", row)
            autism = next(row for row in rows if "autism" in row["evidence_id"] or row["published_at"] == "2025-12-11")
            self.assertEqual(autism["published_at"], "2025-12-11")

    def test_routing_is_bounded_and_query_is_not_returned_or_persisted(self):
        index = Index()
        result = asyncio.run(mobile_sources.research_mobile("Does a vaccine cause autism?", index, fetcher=page))
        self.assertLessEqual(len(index.rows), 3)
        self.assertGreater(len(index.rows), 0)
        self.assertNotIn("query", result)
        self.assertNotIn("vaccine", json.dumps(result).casefold())
        self.assertEqual(mobile_sources.route_specs("unrelated gardening question"), [])

    def test_bounds_and_fetch_failure_stay_unspecified(self):
        with self.assertRaises(ValueError): mobile_sources.route_specs("x" * 301)
        def fail(_url): raise TimeoutError("offline")
        index = Index()
        result = asyncio.run(mobile_sources.research_mobile("covid myth", index, fetcher=fail))
        self.assertEqual(result["records"], 0)
        self.assertEqual(len(result["failures"]), 1)
        self.assertIn("remain unsure", result["coverage_note"])


if __name__ == "__main__":
    unittest.main()
