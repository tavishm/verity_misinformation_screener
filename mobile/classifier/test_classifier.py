import hashlib
import json
import math
import struct
from pathlib import Path
import unittest

from .runtime import Classifier
from .static_embedding import StaticEmbeddingGate
from .train import DIMENSIONS, features, fnv1a, normalize


ROOT = Path(__file__).parent


class ClassifierTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classifier = Classifier(ROOT / "forward_classifier.json")
        cls.fixtures = json.loads((ROOT / "parity_fixtures.json").read_text())

    def test_export_is_small_and_android_asset_is_identical(self):
        exported = (ROOT / "forward_classifier.json").read_bytes()
        android = (ROOT.parent / "android" / "assets" / "forward_classifier.json").read_bytes()
        self.assertLess(len(exported), 1_000_000)
        self.assertEqual(exported, android)
        for name in ("forward_embedding_projection.bin", "forward_embedding_vocab.txt"):
            self.assertEqual((ROOT / name).read_bytes(),
                             (ROOT.parent / "android" / "assets" / name).read_bytes())
        self.assertEqual(self.classifier.model["primary_model"],
                         "multilingual_static_embedding")

    def test_portable_feature_and_decision_fixtures(self):
        for fixture in self.fixtures:
            with self.subTest(text=fixture["text"]):
                indices = sorted({fnv1a(feature) % DIMENSIONS
                                  for feature in features(fixture["text"])})
                digest = hashlib.sha256(",".join(map(str, indices)).encode()).hexdigest()
                self.assertEqual(normalize(fixture["text"]), fixture["normalized"])
                self.assertEqual(len(indices), fixture["feature_count"])
                self.assertEqual(digest, fixture["indices_sha256"])
                actual = self.classifier.classify(fixture["text"])
                self.assertEqual(actual["action"], fixture["action"])
                self.assertEqual(actual["label"], fixture["label"])
                self.assertAlmostEqual(actual["score"], fixture["score"], places=8)

    def test_private_and_link_routes_do_not_require_model_truth(self):
        self.assertEqual(self.classifier.classify("OTP 998877")["action"], "personal_skip")
        self.assertEqual(self.classifier.classify("https://example.org/news")["action"],
                         "offer_link_check")

    def test_factual_clause_is_not_hidden_by_personal_or_opinion_preamble(self):
        messages = [
            "I love you, but eating chicken kills brain cells",
            "I believe vaccines cause infertility",
            "Proud of my kids. Lemon water cures cancer",
            "Call me tonight. The city increased water charges by 20 percent.",
            "Call me. The capital of Australia is Sydney.",
            "I am home. The moon is made of cheese.",
        ]
        for message in messages:
            with self.subTest(message=message):
                self.assertEqual(self.classifier.classify(message)["action"], "factual_offer")

    def test_social_only_messages_skip_in_english_hindi_and_hinglish(self):
        messages = [
            "Happy Diwali!", "I love you", "Proud of my kids.",
            "दीवाली की शुभकामनाएं", "मुझे तुमसे प्यार है",
            "मेरे बच्चों पर गर्व है", "Tumse pyaar hai", "Mere bacchon par garv hai",
            "So proud of my daughter for graduating today!",
            "Proud of my son for their birthday!", "मुझे बुखार है",
        ]
        for message in messages:
            with self.subTest(message=message):
                self.assertEqual(self.classifier.classify(message)["action"], "personal_skip")

    def test_hindi_health_assertions_and_mixed_milestones_offer(self):
        messages = [
            "मुर्गा खाने से दिमाग की कोशिकाएं मर जाती हैं।",
            "नींबू पानी से कैंसर ठीक हो जाता है।",
            "इस दवा से बुखार होता है।",
            "So proud of my daughter for graduating today! The city raised taxes by 8 percent.",
        ]
        for message in messages:
            with self.subTest(message=message):
                self.assertEqual(self.classifier.classify(message)["action"], "factual_offer")

    def test_representative_gate_cases(self):
        factual = [
            "The unemployment rate is now 4 percent.",
            "The ministry announced a new subsidy yesterday.",
            "यह रिपोर्ट दावा करती है कि बेरोजगारी बढ़ी है।",
        ]
        skip = ["I think this movie is wonderful.", "Thanks!", "Good night"]
        for message in factual:
            with self.subTest(factual=message):
                self.assertEqual(self.classifier.classify(message)["action"], "factual_offer")
        for message in skip:
            with self.subTest(skip=message):
                self.assertIn(self.classifier.classify(message)["action"],
                              {"personal_skip", "opinion_skip"})

    def test_runtime_limits_feature_work_to_5000_codepoints(self):
        prefix = "a" * 5000
        suffix = " vaccines cause infertility"
        self.assertEqual(self.classifier.score(prefix), self.classifier.score(prefix + suffix))
        self.assertEqual(self.classifier.classify(prefix), self.classifier.classify(prefix + suffix))

    def test_quantized_embedding_reference_values(self):
        gate = self.classifier.embedding_gate
        self.assertEqual(gate.ids("नमस्ते दुनिया"), [101, 566, 49611, 11354, 78932, 102])
        self.assertAlmostEqual(gate.score("The unemployment rate is now 4 percent."),
                               0.9299332754, places=7)

    def test_compiled_projection_matches_full_embedding_reference(self):
        raw = (ROOT / "forward_embedding_projection.bin").read_bytes()
        self.assertEqual(raw[:4], b"FCSP")
        version, count = struct.unpack_from("<II", raw, 4)
        self.assertEqual(version, 1)
        token_scores = struct.unpack_from(f"<{count}f", raw, 12)
        texts = [fixture["text"] for fixture in self.fixtures] + [
            "Cafe\u0301\tभारत\nnews", "$5+4=9^2|yes", "hello🙂",
            "Proud of my kids. Lemon water cures cancer",
        ]
        gate = self.classifier.embedding_gate
        for text in texts:
            ids = gate.ids(text)
            value = gate.head["intercept"] + sum(token_scores[i] for i in ids) / len(ids)
            projected = 1 / (1 + math.exp(-value))
            self.assertAlmostEqual(projected, gate.score(text), places=5)


if __name__ == "__main__":
    unittest.main()
