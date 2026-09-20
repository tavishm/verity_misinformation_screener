"""Small, bounded official health-reference source pack for consented mobile checks.

This module never receives or writes a forwarded message. Topic routing is
ephemeral and can only select fixed WHO/NCI reference pages; it is not web
search and it produces evidence records, never truth labels or clinical advice.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile
from typing import Callable

from .mobile_links import fetch_page

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DIR = ROOT / "experiments" / "mobile"
MAX_SEED_DOCUMENTS = 5
MAX_RESEARCH_DOCUMENTS = 3
MAX_QUERY_CHARS = 300

# Fixed, public primary-reference pages. Tags select a document; they are not
# labels for user messages and do not establish a medical conclusion by itself.
SOURCE_SPECS = (
    {"id": "who-vaccine-safety", "publisher": "World Health Organization", "title": "Vaccine safety",
     "url": "https://www.who.int/news-room/questions-and-answers/item/vaccines-and-immunization-vaccine-safety",
     "topics": ("vaccine", "vaccination", "immunization", "immunisation")},
    {"id": "who-vaccines-autism-2025", "publisher": "World Health Organization", "title": "WHO analysis on vaccines and autism",
     "url": "https://www.who.int/news/item/11-12-2025-who-expert-group-s-new-analysis-reaffirms-there-is-no-link-between-vaccines-and-autism",
     "published_at": "2025-12-11", "topics": ("vaccine", "vaccination", "autism", "immunization", "immunisation")},
    {"id": "who-covid-mythbusters", "publisher": "World Health Organization", "title": "Coronavirus disease myth busters",
     "url": "https://www.who.int/emergencies/diseases/novel-coronavirus-2019/advice-for-public/myth-busters",
     "topics": ("covid", "coronavirus", "sars-cov-2", "pandemic")},
    {"id": "nci-cancer-myths", "publisher": "National Cancer Institute", "title": "Common Cancer Myths and Misconceptions",
     "url": "https://www.cancer.gov/about-cancer/causes-prevention/risk/myths",
     "topics": ("cancer", "tumor", "tumour", "carcinogen", "carcinogenic")},
    {"id": "nci-cancer-diets-supplements", "publisher": "National Cancer Institute", "title": "Cancer Diets, Supplements, and Other Alternative Therapies",
     "url": "https://www.cancer.gov/about-cancer/treatment/cam/diets-supplements",
     "topics": ("cancer", "tumor", "tumour", "diet", "supplement", "herbal", "alternative therapy")},
)
_PUBLIC_DOCUMENT_CACHE: dict[str, dict] = {}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _atomic_jsonl(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            for record in records:
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def record_from_page(spec: dict, page: dict, retrieved_at: str | None = None) -> dict:
    text = page.get("text")
    if not isinstance(text, str) or len(text.strip()) < 30:
        raise ValueError("official reference page did not expose readable text")
    url = page.get("url")
    if not isinstance(url, str) or not url.startswith("https://"):
        raise ValueError("official reference reader returned an invalid final URL")
    retrieved_at = retrieved_at or _now()
    return {
        "evidence_id": "mobile-reference:" + hashlib.sha256(url.encode("utf-8")).hexdigest()[:24],
        "source_url": url, "title": page.get("title") or spec["title"], "text": text.strip(),
        "publisher": spec["publisher"], "source_kind": "medical_reference", "language": "en",
        "published_at": spec.get("published_at"), "published_at_status": "provided_official_url_date" if spec.get("published_at") else "not_extracted",
        "retrieved_at": retrieved_at, "fetched_at": retrieved_at, "rights": "Official public health reference page; see source site terms",
        "rights_scope": "Official reference wording. It is not individual medical advice or proof of every claim about it.",
        "source_attribution": spec["publisher"], "text_origin": "bounded_public_page_reader",
        "text_sha256": hashlib.sha256(text.strip().encode("utf-8")).hexdigest(), "text_truncated": bool(page.get("truncated")),
        "verification_status": "raw_official_source_unverified",
        "scope_note": "Reference material for general factual context; not clinical advice or a universal health-claim verifier.",
        "reference_topics": list(spec["topics"]),
    }


def _merge(path: Path, records: list[dict]) -> dict:
    existing: list[dict] = []
    if path.exists():
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict) or not isinstance(value.get("source_url"), str):
                raise ValueError(f"invalid mobile corpus record on line {number}")
            existing.append(value)
    positions = {row["source_url"]: index for index, row in enumerate(existing)}
    added = updated = 0
    for record in records:
        position = positions.get(record["source_url"])
        if position is None:
            positions[record["source_url"]] = len(existing); existing.append(record); added += 1
        else:
            existing[position] = record; updated += 1
    if records:
        _atomic_jsonl(path, existing)
    return {"added": added, "updated": updated, "total_records": len(existing)}


def fetch_mobile_sources(output_dir: Path = DEFAULT_DIR, *, fetcher: Callable[[str], dict] = fetch_page) -> dict:
    """Fetch the five fixed official pages and atomically merge successful records."""
    output_dir = Path(output_dir)
    records, failures = [], []
    for spec in SOURCE_SPECS[:MAX_SEED_DOCUMENTS]:
        try:
            record = record_from_page(spec, fetcher(spec["url"]))
            _PUBLIC_DOCUMENT_CACHE[spec["url"]] = record
            records.append(record)
        except Exception as error:
            failures.append({"source_id": spec["id"], "error": f"{type(error).__name__}: {error}"})
    merge = _merge(output_dir / "corpus.jsonl", records)
    return {"records": records, "failures": failures, "corpus_merge": merge,
            "coverage_note": "Only a small set of official WHO/NCI reference pages is available; unmatched health claims remain unresolved."}


def route_specs(query: str, limit: int = MAX_RESEARCH_DOCUMENTS) -> list[dict]:
    """Select fixed reference pages using transient normalized keywords only."""
    if not isinstance(query, str):
        raise TypeError("query must be a string")
    value = " ".join(query.split()).casefold()
    if not value or len(value) > MAX_QUERY_CHARS:
        raise ValueError("mobile research query must be 1–300 characters")
    matches = [spec for spec in SOURCE_SPECS if any(token in value for token in spec["topics"])]
    return matches[:limit]


async def research_mobile(query: str, index, *, fetcher: Callable[[str], dict] = fetch_page) -> dict:
    """Fetch at most three routed public documents and add them to the live index.

    ``query`` is used only during this call. It is neither written to a corpus,
    log, cache key, nor returned in the result.
    """
    specs = route_specs(query)
    records, failures = [], []
    for spec in specs:
        try:
            record = _PUBLIC_DOCUMENT_CACHE.get(spec["url"])
            if record is None:
                page = await asyncio.to_thread(fetcher, spec["url"])
                record = record_from_page(spec, page)
                _PUBLIC_DOCUMENT_CACHE[spec["url"]] = record
            records.append(record)
        except Exception as error:
            failures.append({"source_id": spec["id"], "error": f"{type(error).__name__}: {error}"})
    ingestion = index.ingest_records(records) if records else {}
    return {"records": len(records), "ingestion": ingestion, "failures": failures,
            "coverage_note": "No general web search was run. Source shortage or a topic outside this small reference pack must remain unsure."}
