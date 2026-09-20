#!/usr/bin/env python3
"""Train/export the tiny forward-message check-worthiness classifier."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import unicodedata
import urllib.request

import numpy as np
from scipy.sparse import csr_matrix
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (accuracy_score, average_precision_score,
                             confusion_matrix, precision_recall_fscore_support,
                             roc_auc_score)


DIMENSIONS = 16_384
COMMIT = "332bba0819d79f8e42d0a70277597dfe97036873"
BASE = f"https://raw.githubusercontent.com/utaresearch/claimbuster-spotter/{COMMIT}/data/two_class"
FILES = {
    "train.json": (BASE + "/train.json", "4fd0b54f265f6fa7997a2a7daad07cce6628e98b26534eebc639454272b3191b"),
    "test.json": (BASE + "/test.json", "efd9b9944b680715b03ffe4ba190e96f691f6a6926c7ceb9ba4e0fe8e7e6168b"),
}


def normalize(text: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", text).lower().split())


def features(text: str) -> set[str]:
    value = normalize(text)
    words, current = [], []
    for character in value:
        if character.isalnum():
            current.append(character)
        elif current:
            words.append("".join(current)); current = []
    if current:
        words.append("".join(current))
    output = set()
    for size in (1, 2):
        output.update("w:" + "\x1f".join(words[i:i + size])
                      for i in range(len(words) - size + 1))
    padded = " " + value + " "
    for size in (3, 4, 5):
        output.update(f"c{size}:" + padded[i:i + size]
                      for i in range(len(padded) - size + 1))
    return output


def fnv1a(value: str) -> int:
    result = 2_166_136_261
    for byte in value.encode("utf-8"):
        result = ((result ^ byte) * 16_777_619) & 0xFFFFFFFF
    return result


def matrix(rows: list[dict]) -> csr_matrix:
    indices, pointers = [], [0]
    for row in rows:
        indices.extend(sorted({fnv1a(feature) % DIMENSIONS for feature in features(row["text"])}))
        pointers.append(len(indices))
    return csr_matrix((np.ones(len(indices), dtype=np.float64), indices, pointers),
                      shape=(len(rows), DIMENSIONS))


def fetch(directory: Path) -> dict[str, Path]:
    directory.mkdir(parents=True, exist_ok=True)
    result = {}
    for name, (url, expected) in FILES.items():
        path = directory / name
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            request = urllib.request.Request(url, headers={"User-Agent": "ForwardCheckTrainer/0.1"})
            with urllib.request.urlopen(request, timeout=120) as response:
                data = response.read(10_000_000)
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError(f"hash mismatch for {name}")
            path.write_bytes(data)
        result[name] = path
    return result


def metrics(labels, probabilities, threshold=.4) -> dict:
    predictions = probabilities >= threshold
    precision, recall, f1, _ = precision_recall_fscore_support(
        labels, predictions, average="binary", zero_division=0)
    return {
        "threshold": threshold, "accuracy": round(float(accuracy_score(labels, predictions)), 6),
        "precision": round(float(precision), 6), "recall": round(float(recall), 6),
        "f1": round(float(f1), 6), "roc_auc": round(float(roc_auc_score(labels, probabilities)), 6),
        "average_precision": round(float(average_precision_score(labels, probabilities)), 6),
        "confusion_matrix_tn_fp_fn_tp": confusion_matrix(labels, predictions).ravel().tolist(),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=Path("/tmp/forward-check-classifier"))
    parser.add_argument("--output", type=Path,
                        default=Path(__file__).with_name("forward_classifier_ngram_fallback.json"))
    args = parser.parse_args()
    paths = fetch(args.cache)
    train = json.loads(paths["train.json"].read_text())
    test = json.loads(paths["test.json"].read_text())
    test_texts = {normalize(row["text"]) for row in test}
    overlap = {normalize(row["text"]) for row in train} & test_texts
    # Preserve the official held-out set and remove its two duplicated strings
    # from training rather than leaking them across the evaluation boundary.
    train = [row for row in train if normalize(row["text"]) not in test_texts]
    x_train, x_test = matrix(train), matrix(test)
    y_train = np.asarray([row["label"] for row in train])
    y_test = np.asarray([row["label"] for row in test])
    model = LogisticRegression(C=.1, class_weight="balanced", max_iter=300,
                               solver="liblinear", random_state=1729).fit(x_train, y_train)
    probabilities = model.predict_proba(x_test)[:, 1]
    artifact = {
        "format": "fairc-forward-check-hashed-logistic-v1",
        "dimensions": DIMENSIONS,
        "intercept": round(float(model.intercept_[0]), 8),
        "weights": [round(float(value), 7) for value in model.coef_[0]],
        "thresholds": {"factual_offer": .4, "uncertain_offer": .3},
        "feature_spec": {
            "normalization": "Unicode NFKC, Unicode lowercase, collapse whitespace with single ASCII spaces",
            "words": "Unicode letter-or-digit runs; word unigrams and bigrams joined by U+001F, prefixes w:",
            "characters": "pad normalized text with one ASCII space each side; character 3/4/5-grams, prefixes c3:/c4:/c5:",
            "value": "binary presence after collisions",
            "hash": "32-bit unsigned FNV-1a over UTF-8; index = hash modulo dimensions",
            "score": "sigmoid(intercept + sum(weights[unique feature indices]))",
        },
        "training": {
            "dataset": "ClaimBuster Spotter two_class official train/test split",
            "repository": "https://github.com/utaresearch/claimbuster-spotter",
            "commit": COMMIT, "license": "GPL-3.0 (repository COPYING)",
            "upstream_dataset_doi": "10.5281/zenodo.3609356",
            "train_rows": len(train), "test_rows": len(test),
            "train_distribution": {str(v): int((y_train == v).sum()) for v in (0, 1)},
            "test_distribution": {str(v): int((y_test == v).sum()) for v in (0, 1)},
            "removed_train_rows_overlapping_test": len(overlap),
            "normalized_text_overlap_after_filter": 0,
            "metrics": metrics(y_test, probabilities),
            "limitations": [
                "Political debate sentences, not WhatsApp forwards or phone-user validation.",
                "English supervised data only; Hindi behavior is limited to explicit runtime guards and cues.",
                "Predicts check-worthiness, never truth, falsity, harm, or source reliability.",
            ],
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(artifact, ensure_ascii=False, separators=(",", ":")) + "\n")
    if args.output.stat().st_size > 1_000_000:
        raise ValueError("export exceeds 1 MB")
    print(json.dumps({"output": str(args.output), "bytes": args.output.stat().st_size,
                      "metrics": artifact["training"]["metrics"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
