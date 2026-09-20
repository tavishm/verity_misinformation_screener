import unittest
from .qualifiers import dates, numbers, sentence_claims, support_gap


class QualifierTests(unittest.TestCase):
    def test_hindi_month_swap_is_rejected_despite_same_numbers(self):
        self.assertIsNotNone(support_gap('वीडियो 3 दिसंबर 2025 को पोस्ट हुआ था।', ['वीडियो 3 नवंबर 2025 को पोस्ट हुआ था।']))

    def test_equivalent_english_hindi_dates_and_indian_grouping(self):
        self.assertIsNone(support_gap('२२ सितंबर २०२६ को राशि १६,७५० करोड़ है।', ['Amount 16,750 crore on September 22, 2026.']))
        self.assertEqual(numbers('1,23,45,678'), numbers('12,345,678'))

    def test_wrong_amount_and_missing_date_never_count_as_support(self):
        self.assertIsNotNone(support_gap('Total 34,000 crore.', ['91-day 9,000 crore. Total 24,000 crore.']))
        self.assertIsNotNone(support_gap('Amount 16,750 crore on 22 September 2026.', ['Amount 16,750 crore.']))

    def test_numbers_matching_is_not_a_truth_verdict(self):
        self.assertIsNone(support_gap('The number is 24.', ['The number is not 24.']))
        # Caller must still require entailment; this function only vetoes.

    def test_every_original_sentence_is_retained(self):
        text = 'The amount is ₹16,750 crore. The other total is ₹34,000 crore.'
        self.assertEqual(sentence_claims(text), ['The amount is ₹16,750 crore.', 'The other total is ₹34,000 crore.'])
        self.assertEqual(sentence_claims('दावा सही है। दूसरा दावा गलत है।'), ['दावा सही है।', 'दूसरा दावा गलत है।'])

    def test_uncomputable_derived_quantity_abstains(self):
        self.assertIsNotNone(support_gap('Growth was 10 percent.', ['Revenue rose from 20 to 22 million.']))

    def test_spelled_duration_cannot_match_a_different_numeric_duration(self):
        self.assertIsNotNone(support_gap('The notice continued the emergency for five years.',
                                        ['I am continuing for 1 year the national emergency.']))
        self.assertIsNone(support_gap('The notice continued the emergency for one year.',
                                     ['I am continuing for 1 year the national emergency.']))

    def test_us_abbreviations_preserve_the_complete_assertion(self):
        text='The U.S. Munitions List was amended. Sen. Smith said the rule changed.'
        self.assertEqual(sentence_claims(text),['The U.S. Munitions List was amended.','Sen. Smith said the rule changed.'])


if __name__ == '__main__':
    unittest.main()
