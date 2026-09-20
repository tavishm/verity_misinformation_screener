#!/usr/bin/env python3
"""Host-side regression checks for message routing, acknowledgement and spam assets."""
from pathlib import Path
import json
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'mobile/classifier'))
from static_embedding import StaticEmbeddingGate


def main():
    assets = ROOT / 'mobile/android/assets'
    head = json.loads((assets / 'spam_classifier.json').read_text())
    model = StaticEmbeddingGate(head, ROOT / 'mobile/classifier/forward_embedding.bin', ROOT / 'mobile/classifier/forward_tokenizer.json')
    build = ROOT / '.cache/android/feature-tests'
    android_home = Path(os.environ.get('ANDROID_HOME', ROOT / '.cache/android/sdk'))
    sdk = android_home / 'platforms/android-35/android.jar'
    source = ROOT / 'mobile/android/src/org/fairc/forwardcheck'
    tests = ROOT / 'mobile/android/tests/org/fairc/forwardcheck'
    classes = ['ForwardExtractor', 'MessageExtractor', 'PromptHistory', 'LocalClassifier', 'TinyEmbedding', 'SpamClassifier', 'SpamPolicy']
    cases = ['ForwardExtractorTest', 'MessageExtractorTest', 'PromptHistoryTest', 'SpamClassifierTest']
    subprocess.run(['javac', '-cp', str(sdk), '-d', str(build), *[str(source / (c + '.java')) for c in classes], *[str(tests / (c + '.java')) for c in cases]], check=True)
    for case in cases:
        args = [str(assets), str(head['intercept']), str(head['threshold']), str(model.score('A synthetic test message.'))] if case == 'SpamClassifierTest' else []
        subprocess.run(['java', '-cp', str(build) + ':' + str(sdk), 'org.fairc.forwardcheck.' + case, *args], check=True)


if __name__ == '__main__':
    main()
