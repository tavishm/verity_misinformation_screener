import unittest
from .evidence_policy import official_scope_gap,official_claim_type,contradiction_scope_gap

OFFICIAL=[{'source_kind':'official_presidential_document'}]

class EvidencePolicyTests(unittest.TestCase):
    def test_political_outcome_assertion_is_not_ground_truth(self):
        text='After 4 years of open border chaos, seizures of fentanyl were reduced by more than half and drug overdose deaths plunged.'
        self.assertIsNotNone(official_scope_gap(text,OFFICIAL))

    def test_opinion_and_fragments_are_not_official_acts(self):
        for text in ['Duties would better serve the public interest.','duties imposed in Proclamation 11048 on U.S.']:
            self.assertIsNotNone(official_scope_gap(text,OFFICIAL))

    def test_explicit_attribution_has_a_separate_scope(self):
        text='The document titled "Drug policy" states: "Deaths have plunged".'
        self.assertIsNone(official_scope_gap(text,OFFICIAL))
        self.assertEqual(official_claim_type(text,OFFICIAL),'attribution_only')

    def test_own_official_action_can_be_verified(self):
        for text in ['A presidential notice continued the national emergency for one year.',
                     'The State Department amended ITAR to remove certain uncrewed underwater vehicles.',
                     'The presidential determination identified India as a major drug transit country.']:
            self.assertIsNone(official_scope_gap(text,OFFICIAL))
            self.assertEqual(official_claim_type(text,OFFICIAL),'official_act')

    def test_statistical_and_reporting_sources_keep_their_verification_route(self):
        self.assertIsNone(official_scope_gap('The measured count was 12.',[{'source_kind':'original_dataset'}]))

    def test_another_rule_is_not_contradiction_of_an_unanchored_past_claim(self):
        self.assertIsNotNone(contradiction_scope_gap('The State Department amended ITAR to remove crewed aircraft.',OFFICIAL))
        self.assertIsNone(contradiction_scope_gap('The September 2026 determination did not identify India.',OFFICIAL))

if __name__=='__main__':unittest.main()
