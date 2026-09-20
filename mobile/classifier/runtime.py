"""Reference implementation of the portable classifier and local-only guards."""
from __future__ import annotations
import json
import math
from pathlib import Path
import re

try:
    from .train import features, fnv1a, normalize
    from .static_embedding import StaticEmbeddingGate
except ImportError:
    from train import features, fnv1a, normalize
    from static_embedding import StaticEmbeddingGate

URL = re.compile(r"(?i)\b(?:https?://|www\.)\S+|\b[a-z0-9.-]+\.(?:com|org|net|in|io|co)\b")
MAX_INPUT_CODEPOINTS = 5_000
SOCIAL_ONLY = re.compile(
    r"(?i)^\s*(?:(?:hi|hello|hey|good\s+(?:morning|evening|night)|thanks?|thank\s+you)"
    r"|(?:happy\s+(?:diwali|deepavali|holi|eid|birthday|new\s+year))"
    r"|(?:diwali|deepavali|eid)\s+mubarak|(?:i\s+)?love\s+you"
    r"|(?:i(?:'m|\s+am)\s+)?proud\s+of\s+(?:my\s+|our\s+)?(?:kids?|children|you)"
    r"|(?:so\s+)?proud\s+of\s+(?:my|our)\s+(?:daughter|son|child|kid)\s+for\s+(?:graduating|their\s+(?:graduation|birthday)|turning\s+\d+)(?:\s+today)?"
    r"|नमस्ते|हाय|धन्यवाद|(?:शुभ\s+)?(?:दीवाली|दिवाली)\s*(?:मुबारक|की\s+शुभकामनाएं)?"
    r"|मुझे\s+तुमसे\s+प्यार\s+है|(?:मेरे|हमारे)\s+बच्चों\s+पर\s+गर्व\s+है"
    r"|(?:mujhe\s+)?tumse\s+pya+r\s+hai|(?:mere\s+)?bac+hon\s+par\s+garv\s+hai"
    r"|मुझे\s+(?:बुखार|ज़ुकाम|जुकाम)\s+है)[!.।\s]*$")
PRIVATE = re.compile(r"(?i)(?:\b(?:otp|password|passcode|pin)\b|[\w.+-]+@[\w.-]+\.\w+|(?:\+?\d[\d -]{7,}\d)|\b(?:call|text|meet|pick me up|i am home|i'm home|where are you)\b|(?:मुझे फोन|कॉल करो|मैं घर|कहाँ हो|ओटीपी|पासवर्ड))")
PRIVATE_ONLY = re.compile(
    r"(?i)^\s*(?:(?:my\s+)?(?:otp|password|passcode|pin)\D*\d+"
    r"|(?:can\s+you\s+)?(?:call|text|meet|pick\s+me\s+up)[^.!?।]*"
    r"|(?:i\s+am|i'm)\s+home|where\s+are\s+you|[\w.+-]+@[\w.-]+\.\w+"
    r"|(?:मुझे\s+फोन|कॉल\s+करो|मैं\s+घर|कहाँ\s+हो|ओटीपी|पासवर्ड)[^.!?।]*)[.!?।\s]*$")
OPINION = re.compile(r"(?i)^\s*(?:i\s+(?:think|feel|believe|love|hate|prefer)|in my opinion|मुझे लगता है|मेरे विचार|मुझे पसंद|मुझे नफरत)")
HINDI_FACT = re.compile(r"(?:सरकार|रिपोर्ट|चुनाव|अदालत|मंत्रालय|प्रतिशत|करोड़|लाख|घोषणा|कानून|आंकड़े|दावा).*(?:है|हैं|था|थी|हुआ|किया)")
ASSERTION_CUE = re.compile(
    r"(?i)(?:\b(?:cause[sd]?|cure[sd]?|kill(?:s|ed)?|prevent[sd]?|increase[sd]?|reduce[sd]?|"
    r"announc(?:e[sd]?|ed)|reported?|claim(?:s|ed)?|result(?:s|ed)?\s+in)\b"
    r"|(?:कारण|इलाज|ठीक करता|ठीक हो जाता|मारता|मर (?:जाता|जाती|जाते)|रोकता|बढ़ाता|घटाता|घोषणा|प्रतिशत|करोड़|लाख)"
    r"|खाने से.{0,80}(?:मर|होता|होती|होते)|से.{1,80}होता है)")
NUMBER_CUE = re.compile(r"(?i)\b\d+(?:[.,]\d+)?\s*(?:%|percent|million|billion|lakh|crore)?\b")
CLAUSE = re.compile(r"(?i)[.!?।]+|\b(?:but|however|although|लेकिन|मगर|पर)\b")


class Classifier:
    def __init__(self, artifact: str | Path | dict):
        path = None if isinstance(artifact, dict) else Path(artifact)
        self.model = artifact if isinstance(artifact, dict) else json.loads(path.read_text())
        self.embedding_gate = None
        if self.model.get("format") == "fairc-forward-static-embedding-v1":
            if path is None:
                raise ValueError("static embedding model requires an artifact path")
            self.embedding_gate = StaticEmbeddingGate(
                self.model, path.with_name("forward_embedding.bin"),
                path.with_name("forward_tokenizer.json"))

    def score(self, text: str) -> float:
        text = (text or "")[:MAX_INPUT_CODEPOINTS]
        if self.embedding_gate is not None:
            return self.embedding_gate.score(text)
        indices = {fnv1a(feature) % self.model["dimensions"] for feature in features(text)}
        value = self.model["intercept"] + sum(self.model["weights"][index] for index in indices)
        return 1 / (1 + math.exp(-max(-35, min(35, value))))

    def classify(self, text: str) -> dict:
        raw = (text or "")[:MAX_INPUT_CODEPOINTS]
        value = normalize(raw)
        if not value:
            return decision("personal_skip", "No message", "Nothing was sent off-device.", 0)
        if URL.search(value):
            return decision("offer_link_check", "Link detected", "Offer a separate link check; do not fetch until the user agrees.", 1)
        if SOCIAL_ONLY.search(value):
            return decision("personal_skip", "Personal or greeting", "A greeting does not need a fact check.", 0)
        semantic_cue = bool(ASSERTION_CUE.search(value) or HINDI_FACT.search(value))
        candidates = [raw] + [part.strip() for part in CLAUSE.split(raw) if part.strip()]
        too_many_clauses = len(candidates) > 13
        score = max(self.score(part) for part in candidates[:13])
        factual_cue = semantic_cue or bool(NUMBER_CUE.search(value))
        if PRIVATE_ONLY.search(value):
            return decision("personal_skip", "Likely private", "Keep personal details on the phone and skip upload.", 0)
        if score >= self.model["thresholds"]["factual_offer"]:
            return decision("factual_offer", "Factual claim", "Offer a fact check; upload only after Yes.", score)
        if score >= self.model["thresholds"]["uncertain_offer"] or factual_cue:
            return decision("factual_offer", "Possibly factual", "Ask whether to check; upload only after Yes.", score)
        if too_many_clauses:
            return decision("factual_offer", "Possibly factual", "Long message: ask whether to check; upload only after Yes.", score)
        if OPINION.search(value):
            return decision("opinion_skip", "Opinion", "Personal opinions are not truth-checked.", score)
        return decision("opinion_skip", "No check-worthy claim detected", "Keep the message local and skip by default.", score)


def decision(action, label, reason, score):
    return {"action": action, "label": label, "reason": reason, "score": round(float(score), 9)}
