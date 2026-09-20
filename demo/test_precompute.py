import hashlib
from pathlib import Path
import tempfile
import unittest

from .evidence import EvidenceIndex
from .precompute import candidate_passages, validate_candidates


class PrecomputeSafetyTests(unittest.TestCase):
    def record(self, text):
        return {
            "evidence_id": "official:1",
            "source_url": "https://www.federalregister.gov/documents/example",
            "title": "Example Official Notice",
            "text": text,
            "text_sha256": hashlib.sha256(text.encode()).hexdigest(),
            "published_at": "2026-09-19",
        }

    def test_selected_passage_becomes_explicitly_attributed_claim(self):
        passage = "The agency states that the final rule takes effect on October 1, 2026."
        record = self.record(passage)
        choices = candidate_passages(record)
        accepted, rejected = validate_candidates(
            {"passage_ids": [choices[0]["passage_id"]]}, record, 1, choices)
        self.assertEqual(rejected, [])
        self.assertEqual(accepted[0]["source_quote"], passage)
        self.assertEqual(
            accepted[0]["text"],
            'The document titled "Example Official Notice" states: "' + passage + '".',
        )
        self.assertNotIn("verdict", accepted[0])

    def test_us_abbreviation_is_not_split_or_accepted_as_fragment(self):
        complete = "The agency restricted imports into the U.S. after completing its review."
        following = "The final rule takes effect on October 1, 2026."
        choices = candidate_passages(self.record(complete + " " + following))
        texts = [choice["text"] for choice in choices]
        self.assertIn(complete, texts)
        self.assertIn(following, texts)
        self.assertFalse(any(text.endswith("U.S.") for text in texts))

    def test_incomplete_trailing_fragment_is_rejected(self):
        choices = candidate_passages(self.record(
            "The agency issued a final rule after completing its review. duties imposed would offset the burden on U.S."))
        self.assertEqual([choice["text"] for choice in choices],
                         ["The agency issued a final rule after completing its review."])

    def test_incomplete_leading_fragment_is_rejected(self):
        choices = candidate_passages(self.record(
            ") to deal with the threat described elsewhere in the document. "
            "The agency completed its review and issued the final rule on October 1, 2026."))
        self.assertEqual([choice["text"] for choice in choices],
                         ["The agency completed its review and issued the final rule on October 1, 2026."])

    def test_first_person_source_sentences_are_not_candidate_choices(self):
        choices = candidate_passages(self.record(
            "My Administration has saved tens of thousands of lives. "
            "The Department published the final rule after completing its review."))
        self.assertEqual([choice["text"] for choice in choices],
                         ["The Department published the final rule after completing its review."])

    def test_exact_quote_lookup_returns_current_provenance_and_context_hit(self):
        with tempfile.TemporaryDirectory() as directory:
            index = EvidenceIndex(Path(directory) / "evidence.sqlite3")
            quote = "The agency issued the final rule on October 1, 2026."
            text = "Official notice heading.\n\n" + quote + "\n\nImplementation details follow."
            index.ingest_records([{
                "source_url": "https://www.federalregister.gov/documents/example",
                "title": "Example Official Notice", "text": text, "language": "en",
                "published_at": "2026-09-19", "fetched_at": "2026-09-19T12:00:00Z",
                "source_kind": "official_federal_rule", "publisher": "Federal Register",
                "evidence_id": "official:1", "scope_note": "Wording evidence only",
            }])
            hit = index.lookup_exact_quote("Example Official Notice", quote)
            self.assertIsNotNone(hit)
            self.assertEqual(hit["text"], quote)
            self.assertEqual(text[hit["start_offset"]:hit["end_offset"]], quote)
            for field in ("document_id", "document_version", "source_url", "sourceURL",
                          "title", "publisher", "source_kind", "language",
                          "published_at", "fetched_at", "evidence_id", "scope_note"):
                self.assertTrue(hit[field])
            expanded = index.context(hit, max_chars=500)
            self.assertIn(quote, expanded["text"])
            self.assertEqual(expanded["match_start_offset"], hit["start_offset"])
            index.close()

    def test_exact_quote_lookup_rejects_forgery_and_old_source_version(self):
        with tempfile.TemporaryDirectory() as directory:
            index = EvidenceIndex(Path(directory) / "evidence.sqlite3")
            url = "https://www.federalregister.gov/documents/versioned"
            title = "Versioned Notice"
            old_quote = "The old notice takes effect on October 1, 2026."
            new_quote = "The revised notice takes effect on November 1, 2026."
            index.ingest_records([{"source_url": url, "title": title, "text": old_quote,
                                   "source_kind": "official_notice"}])
            self.assertIsNotNone(index.lookup_exact_quote(title, old_quote))
            self.assertIsNone(index.lookup_exact_quote(title, "The notice invented a different result."))
            index.ingest_records([{"source_url": url, "title": title, "text": new_quote,
                                   "source_kind": "official_notice"}])
            self.assertIsNone(index.lookup_exact_quote(title, old_quote))
            self.assertIsNotNone(index.lookup_exact_quote(title, new_quote))
            index.close()


if __name__ == "__main__":
    unittest.main()
