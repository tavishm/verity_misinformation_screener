"""Mobile review keeps forwarded content scoped and out of shared state."""
import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from demo.evidence import EvidenceIndex
from demo.mobile_review import MobileReviewer


class _OverclaimingEngine:
    """A deliberately unsafe engine result verifies the boundary override."""
    received = []

    def __init__(self, *_args, **_kwargs):
        pass

    async def review(self, text, *_args, **_kwargs):
        self.received.append(text)
        return {
            'label': 'Supported', 'rating': 5, 'claims': [{'text': 'A supplied claim'}],
            'whole_post_validated': True, 'assessment_key': 'private-key',
        }


class MobileReviewScopeTests(unittest.TestCase):
    def setUp(self):
        self.index = EvidenceIndex(':memory:')
        async def ask(_prompt, payload, _tokens):
            return {'sentence_ids': [entry['id'] for entry in payload.get('sentences', [])]}, {}
        self.reviewer = MobileReviewer(self.index, ask, lambda *_args: None, '', '')
        _OverclaimingEngine.received.clear()

    def tearDown(self):
        self.index._db.close()

    def test_message_result_never_claims_whole_post_validation(self):
        with patch('demo.mobile_review.VerificationEngine', _OverclaimingEngine):
            result = asyncio.run(self.reviewer(
                'This is a declared factual statement that needs checking.', False, lambda _stage: None))
        self.assertFalse(result['whole_post_validated'])
        self.assertIn('selected, unchanged factual statements', result['scope'])
        self.assertNotIn('assessment_key', result)

    def test_mixed_message_sends_only_selected_original_factual_sentence(self):
        async def select_factual(_prompt, _payload, _tokens):
            return {'sentence_ids': [2]}, {}
        reviewer = MobileReviewer(self.index, select_factual, lambda *_args: None, '', '')
        with patch('demo.mobile_review.VerificationEngine', _OverclaimingEngine):
            result = asyncio.run(reviewer('I love you. Lemon water cures cancer.', False, lambda _stage: None))
        self.assertEqual(_OverclaimingEngine.received, ['Lemon water cures cancer.'])
        self.assertFalse(result['whole_post_validated'])
        self.assertIn('selected, unchanged factual statements', result['scope'])

    def test_personal_message_returns_no_factual_claim_without_a_verdict(self):
        async def select_none(_prompt, _payload, _tokens):
            return {'sentence_ids': []}, {}
        reviewer = MobileReviewer(self.index, select_none, lambda *_args: None, '', '')
        result = asyncio.run(reviewer('I love you and hope you are well.', False, lambda _stage: None))
        self.assertEqual(result['label'], 'No factual claim')
        self.assertIsNone(result['rating'])
        self.assertFalse(result['whole_post_validated'])
        self.assertEqual(_OverclaimingEngine.received, [])

    def test_more_than_two_link_only_message_opens_nothing(self):
        message = 'https://one.example/a https://two.example/b https://three.example/c'
        with patch('demo.mobile_review.fetch_page', new=AsyncMock()) as fetch:
            result = asyncio.run(self.reviewer(message, False, lambda _stage: None))
        fetch.assert_not_awaited()
        self.assertEqual(result['label'], 'Unsure')
        self.assertFalse(result['whole_post_validated'])
        self.assertIn('more than two links', result['scope'])

    def test_selector_failure_is_not_a_no_factual_claim_result(self):
        for answer in ({}, {'sentence_ids': [True]}, {'sentence_ids': [1, 1]}, {'sentence_ids': '1'}):
            async def malformed(*_args):
                return answer, {}
            reviewer = MobileReviewer(self.index, malformed, lambda *_args: None, '', '')
            with self.assertRaises(ValueError):
                asyncio.run(reviewer('A checkable factual assertion.', False, lambda _stage: None))


if __name__ == '__main__':
    unittest.main()
