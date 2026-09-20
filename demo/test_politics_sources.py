import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import parse_qs, urlsplit

from demo import politics_sources


class FakeFederalRegister:
    def __init__(self, *, fail_api=False, fail_document_numbers=()):
        self.fail_api = fail_api
        self.fail_document_numbers = set(fail_document_numbers)
        self.calls = []

    def __call__(self, url, max_bytes):
        self.calls.append((url, max_bytes))
        parsed = urlsplit(url)
        if parsed.path == politics_sources.API_PATH:
            if self.fail_api:
                raise TimeoutError("simulated API timeout")
            query = parse_qs(parsed.query)
            kind = query.get("conditions[type][]", ["SEARCH"])[0]
            prefix = "P" if kind == "PRESDOCU" else ("R" if kind == "RULE" else "S")
            rows = []
            for number in range(1, 41):
                document_number = f"2026-{prefix}{number:04d}"
                rows.append({
                    "document_number": document_number,
                    "title": f"Official {kind} document {number}",
                    "publication_date": f"2026-09-{19 - (number % 10):02d}",
                    "html_url": f"https://www.federalregister.gov/documents/2026/09/19/{document_number}/official-document",
                    "raw_text_url": f"https://www.federalregister.gov/documents/full_text/text/2026/09/19/{document_number}.txt",
                    "full_text_xml_url": f"https://www.federalregister.gov/documents/full_text/xml/2026/09/19/{document_number}.xml",
                    "type": ("Presidential Document" if prefix == "P" else
                             ("Rule" if prefix == "R" else "Notice")),
                    "agencies": [{"name": "Test Agency"}],
                    "citation": "91 FR 1",
                })
            data = json.dumps({"results": rows}).encode()
            return data, {"status": 200, "response_bytes": len(data)}
        number = Path(parsed.path).stem
        if number in self.fail_document_numbers:
            raise TimeoutError("simulated document timeout")
        data = (f"<RULE><HD>Official source text for {number}.</HD>"
                "<P>This is the complete test document.</P></RULE>").encode()
        return data, {"status": 200, "response_bytes": len(data)}


class PoliticsSourceTests(unittest.TestCase):
    def test_refresh_is_bounded_ordered_and_contains_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            fake = FakeFederalRegister(fail_document_numbers={"2026-P0002"})
            report = politics_sources.refresh_current_politics(directory, 10, fetcher=fake)
            self.assertTrue(report["published"])
            rows = [json.loads(line) for line in
                    (Path(directory) / "corpus.jsonl").read_text().splitlines()]
            self.assertEqual(len(rows), 10)
            self.assertEqual(sum(row["source_kind"] == "official_presidential_document"
                                 for row in rows), 6)
            self.assertEqual(rows[0]["evidence_id"], "federal-register:2026-P0001")
            self.assertEqual(rows[1]["evidence_id"], "federal-register:2026-P0003")
            for row in rows:
                for field in ("evidence_id", "source_url", "title", "text", "publisher",
                              "source_kind", "language", "published_at", "retrieved_at",
                              "rights", "source_attribution", "text_sha256"):
                    self.assertTrue(row[field])
                self.assertEqual(len(row["text_sha256"]), 64)
                self.assertEqual(len(row["raw_response_sha256"]), 64)
                self.assertNotIn("verdict", row)
            log = json.loads((Path(directory) / "acquisition_log.json").read_text())
            self.assertIn("simulated document timeout", json.dumps(log))

    def test_failed_refresh_preserves_last_good_files_and_logs_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            output.joinpath("corpus.jsonl").write_text("last good corpus\n")
            output.joinpath("manifest.json").write_text('{"last_good": true}\n')
            report = politics_sources.refresh_current_politics(
                output, 3, fetcher=FakeFederalRegister(fail_api=True))
            self.assertFalse(report["published"])
            self.assertEqual(output.joinpath("corpus.jsonl").read_text(), "last good corpus\n")
            self.assertEqual(json.loads(output.joinpath("manifest.json").read_text()),
                             {"last_good": True})
            log = json.loads(output.joinpath("acquisition_log.json").read_text())
            self.assertFalse(log["published"])
            self.assertIn("simulated API timeout", json.dumps(log))

    def test_fixed_url_allowlist_and_limits(self):
        self.assertTrue(politics_sources._allowed_url(
            "https://www.federalregister.gov/api/v1/documents.json?order=newest"))
        self.assertFalse(politics_sources._allowed_url(
            "https://www.federalregister.gov.evil.test/api/v1/documents.json"))
        self.assertFalse(politics_sources._allowed_url("http://www.federalregister.gov/api/v1/documents.json"))
        self.assertFalse(politics_sources._allowed_document_url("https://example.com/documents/1"))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                politics_sources.refresh_current_politics(directory, 51, fetcher=FakeFederalRegister())

    def test_research_encodes_term_merges_corpus_and_uses_six_hour_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            old = {
                "evidence_id": "old:1", "source_url": "https://example.gov/old",
                "text": "Existing source remains present."
            }
            output.joinpath("corpus.jsonl").write_text(json.dumps(old) + "\n")
            fake = FakeFederalRegister()
            first = politics_sources.research_current_politics(
                "  veterans   benefits & employment  ", output, 3, fetcher=fake)
            self.assertEqual(len(first["records"]), 3)
            self.assertFalse(first["acquisition"]["cache_hit"])
            api_query = parse_qs(urlsplit(fake.calls[0][0]).query)
            self.assertEqual(api_query["conditions[term]"], ["veterans benefits & employment"])
            self.assertNotIn("conditions[type][]", api_query)
            self.assertEqual(first["acquisition"]["corpus_merge"]["added"], 3)
            merged = [json.loads(line) for line in output.joinpath("corpus.jsonl").read_text().splitlines()]
            self.assertEqual(len(merged), 4)
            self.assertEqual(merged[0], old)
            call_count = len(fake.calls)
            second = politics_sources.research_current_politics(
                "VETERANS BENEFITS & EMPLOYMENT", output, 2, fetcher=fake)
            self.assertTrue(second["acquisition"]["cache_hit"])
            self.assertEqual(len(fake.calls), call_count)
            self.assertEqual([row["source_url"] for row in first["records"][:2]],
                             [row["source_url"] for row in second["records"]])

    def test_research_bounds_and_failure_preserve_existing_corpus(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            original = '{"source_url":"https://example.gov/last-good","text":"good"}\n'
            output.joinpath("corpus.jsonl").write_text(original)
            failed = politics_sources.research_current_politics(
                "executive order", output, fetcher=FakeFederalRegister(fail_api=True))
            self.assertEqual(failed["records"], [])
            self.assertIn("simulated API timeout", failed["acquisition"]["error"])
            self.assertEqual(output.joinpath("corpus.jsonl").read_text(), original)
            with self.assertRaises(ValueError):
                politics_sources.research_current_politics("x" * 301, output)
            with self.assertRaises(ValueError):
                politics_sources.research_current_politics("valid", output, 11)
            with self.assertRaises(ValueError):
                politics_sources.research_current_politics("   ", output)


if __name__ == "__main__":
    unittest.main()
