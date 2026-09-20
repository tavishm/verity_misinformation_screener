"""Train a small spam head over the existing frozen multilingual embedding.

Public UCI data only; no WhatsApp messages are collected or used for training.
Almeida & Hidalgo (2011), SMS Spam Collection, doi:10.24432/C5CC84, CC BY 4.0.
"""
from pathlib import Path
import hashlib
import io
import json
import re
import struct
import urllib.request
import zipfile

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import precision_recall_fscore_support, confusion_matrix
from sklearn.model_selection import train_test_split

from static_embedding import StaticEmbeddingGate

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "android/assets"
URL = "https://archive.ics.uci.edu/static/public/228/sms%2Bspam%2Bcollection.zip"


def main():
    cache = HERE.parent.parent / ".cache/spam-training"
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / "uci-sms-spam.zip"
    if not archive.exists():
        archive.write_bytes(urllib.request.urlopen(URL, timeout=45).read())
    data = zipfile.ZipFile(io.BytesIO(archive.read_bytes())).read("SMSSpamCollection")
    rows = {}
    raw_count = 0
    conflicts = set()
    for line in data.decode("utf-8").splitlines():
        if "\t" not in line:
            continue
        label, text = line.split("\t", 1)
        if label not in {"ham", "spam"}:
            continue
        raw_count += 1
        key = re.sub(r"\s+", " ", text).strip().casefold()
        if key in rows and rows[key][1] != int(label == "spam"):
            conflicts.add(key)
        rows[key] = (text, int(label == "spam"))
    for key in conflicts:
        rows.pop(key)
    texts, y = zip(*rows.values())
    y = np.asarray(y)
    base = StaticEmbeddingGate(ASSETS / "forward_classifier.json", HERE / "forward_embedding.bin", HERE / "forward_tokenizer.json")
    X = np.stack([base.embedding(text) for text in texts])
    train, rest = train_test_split(np.arange(len(y)), test_size=.30, random_state=20260920, stratify=y)
    val, test = train_test_split(rest, test_size=.50, random_state=20260920, stratify=y[rest])
    model = LogisticRegression(C=10, class_weight="balanced", max_iter=1000, random_state=20260920)
    model.fit(X[train], y[train])
    vp = model.predict_proba(X[val])[:, 1]
    choices = []
    for threshold in np.arange(.80, .996, .005):
        p, r, _, _ = precision_recall_fscore_support(y[val], vp >= threshold, average="binary", zero_division=0)
        if p >= .97 and (vp >= threshold).sum() >= 10:
            choices.append((r, -threshold, threshold))
    threshold = float(max(choices)[2]) if choices else .99
    def metrics(indices):
        pred = model.predict_proba(X[indices])[:, 1] >= threshold
        p, r, f, _ = precision_recall_fscore_support(y[indices], pred, average="binary", zero_division=0)
        return dict(precision=float(p), recall=float(r), f1=float(f), confusion_matrix=confusion_matrix(y[indices], pred).tolist())
    weights = model.coef_[0]
    head = dict(version=1, model="frozen multilingual static embedding + SMS spam logistic head", dimensions=128,
                vocab_size=base.head["vocab_size"], max_tokens=256, max_input_codepoints=5000,
                weights=weights.tolist(), intercept=float(model.intercept_[0]), threshold=threshold,
                source=URL, citation="Almeida & Hidalgo (2011), doi:10.24432/C5CC84", license="CC-BY-4.0 dataset; Apache-2.0 base embedding",
                archive_sha256=hashlib.sha256(archive.read_bytes()).hexdigest(), raw_rows=raw_count,
                unique_rows=len(y), conflicting_texts_removed=len(conflicts), train_rows=len(train), validation_rows=len(val), test_rows=len(test),
                seed=20260920, validation=metrics(val), held_out=metrics(test),
                limitations="Old English SMS spam benchmark, not a WhatsApp, modern scam, Hindi, or fraud-validation benchmark. Runtime also uses narrow risk patterns and cautionary-context guards.")
    (ASSETS / "spam_classifier.json").write_text(json.dumps(head, indent=2) + "\n")
    scores = ((base.table.astype(np.float32) * base.scales) @ weights).astype("<f4")
    (ASSETS / "spam_embedding_projection.bin").write_bytes(b"FCSP" + struct.pack("<II", 1, len(scores)) + scores.tobytes())
    report = {k: v for k, v in head.items() if k != "weights"}
    (HERE / "spam_training_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
