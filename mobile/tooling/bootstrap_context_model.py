#!/usr/bin/env python3
"""Fetch the excluded contextual Android model and verify it before use.

The model is intentionally not a Git blob: it exceeds GitHub's 100 MiB limit.
A publisher supplies a HTTPS release-asset URL; this script accepts the file
only when it matches the committed manifest exactly.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = ROOT / "mobile/android/assets/context-model-manifest.json"


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=os.environ.get("FORWARD_CHECK_CONTEXT_MODEL_URL"),
                        help="HTTPS URL for the published model release asset")
    parser.add_argument("--force", action="store_true", help="replace an existing invalid file")
    args = parser.parse_args()
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    target = MANIFEST_PATH.parent / manifest["file"]
    expected = manifest["sha256"]

    if target.is_file() and digest(target) == expected:
        print("Context model already present and checksum verified:", target)
        return 0
    if target.exists() and not args.force:
        raise SystemExit("Existing context model has the wrong checksum; rerun with --force to replace it.")
    if not args.url:
        raise SystemExit("Set FORWARD_CHECK_CONTEXT_MODEL_URL or pass --url with a published HTTPS release asset.")
    parsed = urllib.parse.urlparse(args.url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise SystemExit("Model URL must use HTTPS.")

    partial = target.with_suffix(target.suffix + ".part")
    try:
        with urllib.request.urlopen(args.url, timeout=60) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
        actual = digest(partial)
        if actual != expected:
            raise SystemExit("Downloaded context model checksum does not match the committed manifest.")
        if partial.stat().st_size != manifest["bytes"]:
            raise SystemExit("Downloaded context model size does not match the committed manifest.")
        partial.replace(target)
        print("Context model downloaded and checksum verified:", target)
        return 0
    finally:
        partial.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
