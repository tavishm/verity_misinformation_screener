"""Behavior checks for provenance, active versions, and safe literal retrieval."""

import json
from pathlib import Path
import tempfile
import unittest

try:
    from .evidence import EvidenceIndex, chunk_spans, query_terms
except ImportError:
    from evidence import EvidenceIndex, chunk_spans, query_terms


def source(text, url="https://example.org/report", **extra):
    return dict(text=text, source_url=url, title="Source report", language="en",
                published_at="2026-09-19T08:00:00Z",
                fetched_at="2026-09-19T09:00:00Z", source_kind="primary", **extra)


class EvidenceIndexTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "index.sqlite3"
        self.index = EvidenceIndex(self.path)

    def tearDown(self):
        self.index.close()
        self.directory.cleanup()

    def test_source_offsets_preserve_negation_numbers_and_hindi(self):
        text = "  Students did not hit police. The count was 3.14, not 314.\r\n\r\nछात्रों ने पुलिस को नहीं मारा। संख्या १२ थी।  "
        record = source(text)
        record["language"] = "en-hi"
        self.index.ingest_records([record])
        for query in ("police", "छात्रों पुलिस नहीं", "१२"):
            results = self.index.search(query)
            self.assertTrue(results, query)
            for row in results:
                self.assertEqual(text[row["start_offset"]:row["end_offset"]], row["text"])
                self.assertEqual(row["language"], "en-hi")
                self.assertEqual(row["sourceURL"], record["source_url"])
                self.assertEqual(row["published_at"], record["published_at"])
                self.assertEqual(row["source_kind"], "primary")
        self.assertIn("not", query_terms("not 314"))
        self.assertIn("नहीं", query_terms("छात्रों ने नहीं मारा"))

    def test_empty_unrelated_and_fts_operators_are_safe(self):
        self.index.ingest_records([source("Student enrollment rose to 500 in September.")])
        for query in ("", "   ", "*()\"", "the and is", "quasars superconductivity"):
            self.assertEqual(self.index.search(query), [])
        # A raw FTS expression would have different semantics or raise an error.
        results = self.index.search('" OR enrollment NEAR(*) NOT')
        self.assertEqual(len(results), 1)
        self.assertIn("enrollment", results[0]["text"])
        self.assertEqual(self.index.stats()["documents"], 1)

    def test_exact_duplicate_does_not_create_version_or_chunks(self):
        record = source("The telescope measured six planets.")
        first = self.index.ingest_records([record])
        before = self.index.stats()
        again = self.index.ingest_records([dict(record)])
        self.assertEqual(first["inserted"], 1)
        self.assertEqual(again["unchanged"], 1)
        self.assertEqual(before, self.index.stats())

    def test_updated_source_invalidates_cached_hit_and_miss(self):
        self.index.ingest_records([source("The mission was called Artemis.")])
        old = self.index.search("Artemis")[0]
        self.assertEqual(self.index.search("Vulcan"), [])
        result = self.index.ingest_records([source("The mission was renamed Vulcan.")])
        self.assertEqual(result["updated"], 1)
        self.assertEqual(self.index.search("Artemis"), [])
        new = self.index.search("Vulcan")[0]
        self.assertNotEqual(old["document_version"], new["document_version"])
        self.assertEqual(old["document_id"], new["document_id"])
        self.assertEqual(self.index.stats()["versions"], 2)
        self.assertEqual(self.index.stats()["active_chunks"], 1)

    def test_other_connection_and_fetch_time_invalidate_cache(self):
        record = source("The observatory found a pulsar.")
        self.index.ingest_records([record])
        self.index.search("pulsar")
        with EvidenceIndex(self.path) as writer:
            record["fetched_at"] = "2026-09-19T10:00:00Z"
            update = writer.ingest_records([record])
            self.assertEqual(update["metadata_updated"], 1)
            self.assertEqual(writer.stats()["versions"], 1)
        self.assertEqual(self.index.search("pulsar")[0]["fetched_at"], record["fetched_at"])

    def test_reverting_to_earlier_document_reuses_its_version(self):
        first = source("The station was named Alpha.")
        self.index.ingest_records([first])
        version = self.index.search("Alpha")[0]["document_version"]
        self.index.ingest_records([source("The station was named Beta.")])
        report = self.index.ingest_records([first])
        self.assertEqual(report["versions_reused"], 1)
        self.assertEqual(self.index.stats()["versions"], 2)
        self.assertEqual(self.index.search("Beta"), [])
        self.assertEqual(self.index.search("Alpha")[0]["document_version"], version)

    def test_results_are_grouped_by_document_and_cache_cannot_be_mutated(self):
        self.index.ingest_records([
            source("Mars has satellites.\n\nMars has a thin atmosphere."),
            source("Mars is a planet.", "https://example.org/other"),
        ])
        rows = self.index.search("Mars")
        seen, previous = set(), None
        for row in rows:
            if row["document_id"] != previous:
                self.assertNotIn(row["document_id"], seen)
                seen.add(row["document_id"])
                previous = row["document_id"]
        rows[0]["text"] = "Invented replacement"
        self.assertNotEqual(self.index.search("Mars")[0]["text"], "Invented replacement")

    def test_jsonl_import_handles_alias_and_rolls_back_malformed_batch(self):
        good = Path(self.directory.name) / "good.jsonl"
        good.write_text(json.dumps({"url": "https://example.org/a", "text": "A comet appeared."}) + "\n", encoding="utf-8")
        self.assertEqual(self.index.ingest_jsonl(good)["inserted"], 1)
        bad = Path(self.directory.name) / "bad.jsonl"
        bad.write_text(json.dumps(source("A meteor appeared.")) + "\n{bad\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "line 2"):
            self.index.ingest_jsonl(bad)
        self.assertEqual(self.index.stats()["documents"], 1)
        self.assertEqual(self.index.search("meteor"), [])

    def test_invalid_records_are_counted_and_no_verdict_is_invented(self):
        report = self.index.ingest_records([None, {}, {"text": "something"},
                                            {"url": "javascript:alert(1)", "text": "x"},
                                            source("Rainfall was 50 mm.")])
        self.assertEqual(report["skipped"], 4)
        self.assertNotIn("verdict", self.index.search("rainfall")[0])

    def test_corpus_provenance_and_cross_instance_revision_are_preserved(self):
        record = {"url": "https://example.org/news", "text": "Monsoon rainfall increased.",
                  "source_type": "publisher_article", "publisher": "Example publication",
                  "rights": "CC BY 3.0", "rights_scope": "Publisher prose",
                  "text_origin": "rss_content_encoded", "evidence_id": "news:abc",
                  "date_warning": None, "text_truncated": False}
        initial_revision = self.index.revision
        with EvidenceIndex(self.path) as writer:
            writer.ingest_records([record])
        self.assertGreater(self.index.revision, initial_revision)
        row = self.index.search("monsoon")[0]
        self.assertEqual(row["source_kind"], "publisher_article")
        for key in ("publisher", "rights", "rights_scope", "text_origin", "evidence_id",
                    "date_warning", "text_truncated"):
            self.assertEqual(row[key], record[key])
        self.assertEqual(self.index.stats()["revision"], self.index.revision)

    def test_long_chunks_are_bounded_and_keep_all_nonspace_characters(self):
        text = ("अध्ययन में १२ प्रतिभागी नहीं आए। " * 100) + "\n\n" + "x" * 4000
        spans = chunk_spans(text, max_chars=120)
        chunks = [text[start:end] for start, end in spans]
        self.assertTrue(all(0 < len(chunk) <= 120 for chunk in chunks))
        self.assertEqual("".join(c for c in "".join(chunks) if not c.isspace()),
                         "".join(c for c in text if not c.isspace()))
        self.assertTrue(all(left[1] <= right[0] for left, right in zip(spans, spans[1:])))

    def test_context_expands_table_row_with_header_and_total(self):
        text = ("  Enrollment by campus\r\n\r\nCampus\tStudents\r\n\r\n"
                "North\t9000\r\n\r\nSouth\t15000\r\n\r\nTotal\t24000  ")
        self.index.ingest_records([source(text, publisher="Example university")])
        hit = self.index.search("9000")[0]
        original = dict(hit)
        expanded = self.index.context(hit)
        self.assertEqual(hit, original)
        self.assertEqual(self.index.search("9000")[0], original)
        self.assertEqual(expanded["text"], text[2:-2])
        self.assertIn("Campus\tStudents", expanded["text"])
        self.assertIn("Total\t24000", expanded["text"])
        self.assertEqual(expanded["match_start_offset"], hit["start_offset"])
        self.assertEqual(expanded["match_end_offset"], hit["end_offset"])
        for key in ("chunk_id", "document_id", "document_version", "publisher",
                    "rank", "source_url", "sourceURL", "published_at"):
            self.assertEqual(expanded[key], hit[key])
        self.assertFalse(expanded["context_truncated"])

    def test_context_offsets_preserve_original_unicode_whitespace_and_bound(self):
        before = "पहले का अलग विवरण। " * 60
        table = "शीर्षक\tनाम\r\n\r\nNorth\t9000\r\n\r\nकुल\t24000"
        after = "बाद का अलग विवरण। " * 60
        text = before + "\r\n\r\n" + table + "\r\n\r\n" + after
        self.index.ingest_records([source(text)])
        hit = self.index.search("9000")[0]
        expanded = self.index.context(hit, max_chars=200)
        self.assertIsNotNone(expanded)
        self.assertLessEqual(len(expanded["text"]), 200)
        self.assertIn(table, expanded["text"])
        start, end = expanded["start_offset"], expanded["end_offset"]
        self.assertEqual(expanded["text"], text[start:end])
        self.assertLessEqual(start, hit["start_offset"])
        self.assertGreaterEqual(end, hit["end_offset"])
        self.assertTrue(text[start - 1].isspace())
        self.assertTrue(text[end].isspace())
        self.assertTrue(expanded["context_truncated"])

    def test_context_preserves_complete_containing_paragraph(self):
        paragraph = "The campus total is 24000. " + "Enrollment details remain unchanged. " * 45
        text = "Separate introduction.\n\n" + paragraph + "\n\nSeparate conclusion."
        self.index.ingest_records([source(text)])
        hit = self.index.search("24000")[0]
        self.assertLess(len(hit["text"]), len(paragraph.strip()))
        expanded = self.index.context(hit, max_chars=1800)
        self.assertIn(paragraph.strip(), expanded["text"])
        self.assertLessEqual(len(expanded["text"]), 1800)
        self.assertEqual(text[expanded["start_offset"]:expanded["end_offset"]],
                         expanded["text"])

    def test_context_uses_historical_version_and_never_falls_back(self):
        old_text = "Original header\n\nNorth 9000\n\nTotal 24000"
        self.index.ingest_records([source(old_text)])
        old_hit = self.index.search("9000")[0]
        self.index.ingest_records([source("Revised header\n\nNorth 8000\n\nTotal 20000")])
        expanded = self.index.context(old_hit)
        self.assertEqual(expanded["text"], old_text)
        self.assertEqual(expanded["document_version"], old_hit["document_version"])
        self.assertNotIn("20000", expanded["text"])
        self.assertEqual(self.index.search("9000"), [])
        self.assertIsNone(self.index.context(dict(old_hit, document_version="missing")))
        self.assertIsNone(self.index.context(dict(old_hit, document_id=999999)))

    def test_context_rejects_mismatched_text_offsets_or_source_metadata(self):
        self.index.ingest_records([source("Original header\n\nNorth 9000\n\nTotal 24000")])
        hit = self.index.search("9000")[0]
        for changes in ({"text": "North 24000"}, {"start_offset": -1},
                        {"end_offset": 999999}, {"start_offset": True},
                        {"document_id": "1"}, {"document_version": None},
                        {"sourceURL": "https://example.org/different"}):
            with self.subTest(changes=changes):
                self.assertIsNone(self.index.context(dict(hit, **changes)))
        with self.assertRaises(TypeError):
            self.index.context(None)
        for bound in (0, 15, True, 20.5):
            with self.subTest(bound=bound), self.assertRaises(ValueError):
                self.index.context(hit, max_chars=bound)

    def test_context_does_not_discard_matched_text_to_satisfy_small_bound(self):
        self.index.ingest_records([source("The original complete statement contains 9000 students.")])
        hit = self.index.search("9000")[0]
        self.assertIsNone(self.index.context(hit, max_chars=20))

    def test_context_only_splits_unbroken_tokens_that_exceed_the_bound(self):
        long_text = "x" * 4000
        self.index.ingest_records([source(long_text)])
        hit = self.index.search("x" * 1600)[0]
        expanded = self.index.context(hit, max_chars=1800)
        self.assertEqual(len(expanded["text"]), 1800)
        self.assertEqual(expanded["text"], long_text[
            expanded["start_offset"]:expanded["end_offset"]])

        self.index.ingest_records([source("y" * 1700, "https://example.org/short-token")])
        hit = self.index.search("y" * 1600)[0]
        self.assertEqual(self.index.context(hit, max_chars=1800)["text"], "y" * 1700)


if __name__ == "__main__":
    unittest.main()
