#!/usr/bin/env python3
"""Acquire a small, current corpus of official U.S. government documents.

This module has no generic URL-fetching interface.  It reads a fixed set of
FederalRegister.gov API searches and then downloads only official plain-text
document URLs returned by that API.  Records are raw source evidence, not
fact-check verdicts: they establish what an official document says.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile
from typing import Callable
from urllib.parse import urlencode, urlsplit
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET


API_ORIGIN = "https://www.federalregister.gov"
API_PATH = "/api/v1/documents.json"
TEXT_PATH_PREFIXES = ("/documents/full_text/text/", "/documents/full_text/xml/")
DEFAULT_MAX_RECORDS = 20
MAX_RECORDS = 50
MAX_INDEX_BYTES = 1_000_000
MAX_DOCUMENT_BYTES = 1_500_000
MAX_RESEARCH_QUERY_CHARS = 300
MAX_RESEARCH_RECORDS = 10
RESEARCH_CACHE_HOURS = 6
USER_AGENT = (
    "FactCheckerPoliticsDemo/0.1 "
    "(bounded official-document reader; contact the repository owner)"
)
RIGHTS = "U.S. federal government work; see 17 U.S.C. § 105"
RIGHTS_URL = "https://www.govinfo.gov/about/policies#copyright"
SOURCE_SPECS = (
    ("presidential_documents", "PRESDOCU", "official_presidential_document"),
    ("rules", "RULE", "official_federal_rule"),
)
FIELDS = (
    "document_number", "title", "publication_date", "html_url", "raw_text_url",
    "type", "agencies", "citation", "abstract", "significant", "full_text_xml_url",
)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _allowed_url(url: str, *, text: bool = False) -> bool:
    """Accept only the exact HTTPS host and known API/document path."""
    try:
        parsed = urlsplit(url)
    except ValueError:
        return False
    return (
        parsed.scheme == "https"
        and parsed.hostname == "www.federalregister.gov"
        and parsed.port is None
        and (parsed.path.startswith(TEXT_PATH_PREFIXES) if text else parsed.path == API_PATH)
        and parsed.username is None
        and parsed.password is None
    )


def _allowed_document_url(url: str) -> bool:
    try:
        parsed = urlsplit(url)
    except ValueError:
        return False
    return (
        parsed.scheme == "https"
        and parsed.hostname == "www.federalregister.gov"
        and parsed.port is None
        and parsed.username is None
        and parsed.password is None
        and parsed.path.startswith("/documents/")
    )


def _fetch_url(url: str, max_bytes: int) -> tuple[bytes, dict]:
    if not (_allowed_url(url) or _allowed_url(url, text=True)):
        raise ValueError("URL is outside the fixed Federal Register allowlist")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=25) as response:
        final_url = response.geturl()
        if not (_allowed_url(final_url) or _allowed_url(final_url, text=True)):
            raise ValueError("redirect left the fixed Federal Register allowlist")
        data = response.read(max_bytes + 1)
        metadata = {
            "status": response.status,
            "final_url": final_url,
            "content_type": response.headers.get("Content-Type"),
            "etag": response.headers.get("ETag"),
            "last_modified": response.headers.get("Last-Modified"),
            "response_bytes": len(data),
        }
    if len(data) > max_bytes:
        raise ValueError(f"response exceeds {max_bytes}-byte acquisition cap")
    return data, metadata


def _api_url(document_type: str, candidates: int) -> str:
    params: list[tuple[str, str | int]] = [
        ("per_page", min(100, candidates)), ("order", "newest"),
        ("conditions[type][]", document_type),
    ]
    params.extend(("fields[]", field) for field in FIELDS)
    return API_ORIGIN + API_PATH + "?" + urlencode(params)


def _research_api_url(query: str, candidates: int) -> str:
    params: list[tuple[str, str | int]] = [
        ("per_page", min(100, candidates)),
        ("order", "relevance"),
        ("conditions[term]", query),
    ]
    params.extend(("fields[]", field) for field in FIELDS)
    return API_ORIGIN + API_PATH + "?" + urlencode(params)


def _atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _atomic_jsonl(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            for record in records:
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _decode_document(data: bytes, url: str) -> tuple[str, str]:
    decoded = data.decode("utf-8-sig")
    if urlsplit(url).path.startswith("/documents/full_text/xml/"):
        root = ET.fromstring(decoded)
        # The XML is the official full document. Keep every textual node in
        # document order, changing only layout indentation into line breaks.
        rendered = ET.tostring(root, encoding="unicode", method="text")
        lines = [" ".join(line.split()) for line in rendered.splitlines()]
        text = "\n".join(line for line in lines if line)
        origin = "federal_register_full_text_xml"
    else:
        text = decoded
        origin = "federal_register_raw_text"
    if not text.strip():
        raise ValueError("official document contains no text")
    return text, origin


def _record_from_result(item: dict, api_url: str, raw: bytes,
                        response_metadata: dict, text_url: str) -> dict:
    text, text_origin = _decode_document(raw, text_url)
    retrieved = _now()
    number = item.get("document_number")
    agencies = [agency.get("name") for agency in item.get("agencies", [])
                if isinstance(agency, dict) and agency.get("name")]
    kind = {
        "Presidential Document": "official_presidential_document",
        "Rule": "official_federal_rule",
        "Proposed Rule": "official_federal_proposed_rule",
        "Notice": "official_federal_notice",
    }.get(item.get("type"), "official_federal_register_document")
    return {
        "evidence_id": f"federal-register:{number}",
        "source_url": item.get("html_url"),
        "title": item.get("title") or "",
        "text": text,
        "publisher": "Office of the Federal Register",
        "source_kind": kind,
        "language": "en",
        "published_at": item.get("publication_date"),
        "retrieved_at": retrieved,
        "fetched_at": retrieved,
        "rights": RIGHTS,
        "rights_url": RIGHTS_URL,
        "rights_scope": "Official U.S. federal government document text; third-party material, if any, may differ",
        "source_attribution": "Federal Register, National Archives and Records Administration",
        "text_origin": text_origin,
        "text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "document_number": number,
        "document_type": item.get("type"),
        "citation": item.get("citation"),
        "agencies": agencies,
        "api_url": api_url,
        "official_full_text_url": text_url,
        "raw_response_sha256": hashlib.sha256(raw).hexdigest(),
        "response_metadata": response_metadata,
        "text_truncated": False,
        "whitespace_normalized": text_origin.endswith("_xml"),
        "verification_status": "raw_official_source_unverified",
        "scope_note": "Evidence of what this official document states; not proof of every underlying claim",
    }


def _merge_corpus(path: Path, new_records: list[dict]) -> dict:
    """Atomically merge by source URL; never remove an existing valid record."""
    existing: list[dict] = []
    if path.exists():
        with path.open(encoding="utf-8") as stream:
            for line_number, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                value = json.loads(line)
                if not isinstance(value, dict) or not isinstance(value.get("source_url"), str):
                    raise ValueError(f"invalid existing corpus record on line {line_number}")
                existing.append(value)
    positions = {record["source_url"]: index for index, record in enumerate(existing)}
    added = updated = 0
    for record in new_records:
        position = positions.get(record["source_url"])
        if position is None:
            positions[record["source_url"]] = len(existing)
            existing.append(record)
            added += 1
        else:
            existing[position] = record
            updated += 1
    if new_records:
        _atomic_jsonl(path, existing)
    return {"previous_records": len(existing) - added, "added": added,
            "updated": updated, "total_records": len(existing)}


def research_current_politics(
    query: str,
    output_dir: Path,
    max_records: int = 5,
    *,
    fetcher: Callable[[str, int], tuple[bytes, dict]] = _fetch_url,
) -> dict:
    """Search official Federal Register documents and return ingestion records.

    User text is only encoded as ``conditions[term]`` on the fixed API endpoint;
    it can never select a host or document URL. Identical normalized searches
    reuse their successful result for six hours.
    """
    if not isinstance(query, str):
        raise TypeError("query must be a string")
    normalized = " ".join(query.split())
    if not normalized:
        raise ValueError("query must contain non-whitespace text")
    if len(normalized) > MAX_RESEARCH_QUERY_CHARS:
        raise ValueError(f"query must be at most {MAX_RESEARCH_QUERY_CHARS} characters")
    if not isinstance(max_records, int) or isinstance(max_records, bool):
        raise TypeError("max_records must be an integer")
    if not 1 <= max_records <= MAX_RESEARCH_RECORDS:
        raise ValueError(f"max_records must be between 1 and {MAX_RESEARCH_RECORDS}")
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    query_key = hashlib.sha256(normalized.casefold().encode("utf-8")).hexdigest()
    cache_path = output / ".politics_research_cache" / f"{query_key}.json"
    now = datetime.now(timezone.utc)
    if cache_path.exists():
        try:
            cached = json.loads(cache_path.read_text(encoding="utf-8"))
            cached_at = datetime.fromisoformat(cached["cached_at"])
            if (cached_at.tzinfo is not None
                    and now - cached_at.astimezone(timezone.utc) < timedelta(hours=RESEARCH_CACHE_HOURS)
                    and isinstance(cached.get("records"), list)
                    and len(cached["records"]) >= max_records):
                cached_records = cached["records"][:max_records]
                acquisition = dict(cached["acquisition"])
                acquisition.update(cache_hit=True, cache_checked_at=_now(),
                                   requested_records=max_records,
                                   selected=len(cached_records))
                try:
                    acquisition["corpus_merge"] = _merge_corpus(
                        output / "corpus.jsonl", cached_records)
                except Exception as error:
                    acquisition["corpus_merge_error"] = f"{type(error).__name__}: {error}"
                _atomic_json(output / "research_acquisition_log.json", acquisition)
                return {"records": cached_records, "acquisition": acquisition}
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            pass  # A bad cache is ignored; the official source is queried.

    candidates = min(30, max_records * 3)
    api_url = _research_api_url(normalized, candidates)
    acquisition: dict = {
        "started_at": _now(), "query": normalized, "query_sha256": query_key,
        "requested_records": max_records, "candidate_cap": candidates,
        "api_url": api_url, "cache_hit": False, "selected": 0, "excluded": [],
        "selection": "Federal Register relevance order for conditions[term]; no verdict filtering",
        "bounds": {"max_query_characters": MAX_RESEARCH_QUERY_CHARS,
                   "max_records": MAX_RESEARCH_RECORDS,
                   "max_index_bytes": MAX_INDEX_BYTES,
                   "max_document_bytes": MAX_DOCUMENT_BYTES,
                   "request_timeout_seconds": 25},
    }
    records: list[dict] = []
    try:
        data, api_metadata = fetcher(api_url, MAX_INDEX_BYTES)
        acquisition["api_response"] = api_metadata
        acquisition["api_response_sha256"] = hashlib.sha256(data).hexdigest()
        results = json.loads(data).get("results")
        if not isinstance(results, list):
            raise ValueError("API response has no results list")
        acquisition["available"] = len(results)
        seen: set[str] = set()
        for position, item in enumerate(results, 1):
            if len(records) >= max_records:
                break
            if not isinstance(item, dict):
                acquisition["excluded"].append({"position": position, "reason": "non-object result"})
                continue
            source_url = item.get("html_url")
            text_url = item.get("full_text_xml_url")
            if (not isinstance(source_url, str) or not _allowed_document_url(source_url)
                    or not isinstance(text_url, str) or not _allowed_url(text_url, text=True)):
                acquisition["excluded"].append({
                    "position": position, "document_number": item.get("document_number"),
                    "reason": "missing or non-allowlisted official XML URL",
                })
                continue
            if source_url in seen:
                acquisition["excluded"].append({"position": position, "reason": "duplicate source URL"})
                continue
            try:
                raw, text_metadata = fetcher(text_url, MAX_DOCUMENT_BYTES)
                record = _record_from_result(item, api_url, raw, text_metadata, text_url)
            except Exception as error:
                acquisition["excluded"].append({
                    "position": position, "document_number": item.get("document_number"),
                    "reason": f"{type(error).__name__}: {error}",
                })
                continue
            records.append(record)
            seen.add(source_url)
        acquisition["selected"] = len(records)
    except Exception as error:
        acquisition["error"] = f"{type(error).__name__}: {error}"
    acquisition["finished_at"] = _now()
    try:
        acquisition["corpus_merge"] = _merge_corpus(output / "corpus.jsonl", records)
    except Exception as error:
        acquisition["corpus_merge_error"] = f"{type(error).__name__}: {error}"
    _atomic_json(output / "research_acquisition_log.json", acquisition)
    if records:
        _atomic_json(cache_path, {"cached_at": _now(), "records": records,
                                  "acquisition": acquisition})
    return {"records": records, "acquisition": acquisition}


def refresh_current_politics(
    output_dir: str | Path,
    max_records: int = DEFAULT_MAX_RECORDS,
    *,
    fetcher: Callable[[str, int], tuple[bytes, dict]] = _fetch_url,
) -> dict:
    """Refresh the fixed official-source corpus, preserving a last good corpus.

    ``fetcher`` exists for deterministic tests; callers cannot supply source URLs.
    A refresh is published only when it reaches the requested bounded count.
    """
    if not isinstance(max_records, int) or isinstance(max_records, bool):
        raise TypeError("max_records must be an integer")
    if not 1 <= max_records <= MAX_RECORDS:
        raise ValueError(f"max_records must be between 1 and {MAX_RECORDS}")
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    started = _now()
    records: list[dict] = []
    seen: set[str] = set()
    source_logs: list[dict] = []

    # Presidential documents receive the first 60% of the target. Rules fill
    # the remainder and any presidential-document download failures.
    preferred = (max_records * 3 + 4) // 5
    for source_number, (source_id, document_type, source_kind) in enumerate(SOURCE_SPECS):
        desired_stop = preferred if source_number == 0 else max_records
        candidates = min(100, max(max_records * 3, 30))
        api_url = _api_url(document_type, candidates)
        log: dict = {
            "source_id": source_id, "api_url": api_url,
            "selection": "newest API results in publisher order",
            "attempted_at": _now(), "selected": 0, "excluded": [],
        }
        source_logs.append(log)
        try:
            data, response_metadata = fetcher(api_url, MAX_INDEX_BYTES)
            log["api_response"] = response_metadata
            log["api_response_sha256"] = hashlib.sha256(data).hexdigest()
            payload = json.loads(data)
            results = payload.get("results")
            if not isinstance(results, list):
                raise ValueError("API response has no results list")
            log["available"] = len(results)
            for position, item in enumerate(results, 1):
                if len(records) >= desired_stop:
                    break
                if not isinstance(item, dict):
                    log["excluded"].append({"position": position, "reason": "non-object result"})
                    continue
                source_url = item.get("html_url")
                text_url = item.get("full_text_xml_url") or item.get("raw_text_url")
                number = item.get("document_number")
                if (not isinstance(source_url, str)
                        or not _allowed_document_url(source_url)
                        or not _allowed_url(text_url or "", text=True)):
                    log["excluded"].append({
                        "position": position, "document_number": number,
                        "reason": "missing or non-allowlisted official text URL",
                    })
                    continue
                if source_url in seen:
                    log["excluded"].append({"position": position, "reason": "duplicate source URL"})
                    continue
                try:
                    raw, text_metadata = fetcher(text_url, MAX_DOCUMENT_BYTES)
                    text, text_origin = _decode_document(raw, text_url)
                except Exception as error:  # Keep per-document failures truthful and bounded.
                    log["excluded"].append({
                        "position": position, "document_number": number,
                        "reason": f"{type(error).__name__}: {error}",
                    })
                    continue
                retrieved = _now()
                digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
                agencies = [agency.get("name") for agency in item.get("agencies", [])
                            if isinstance(agency, dict) and agency.get("name")]
                record = {
                    "evidence_id": f"federal-register:{number}",
                    "source_url": source_url,
                    "title": item.get("title") or "",
                    "text": text,
                    "publisher": "Office of the Federal Register",
                    "source_kind": source_kind,
                    "language": "en",
                    "published_at": item.get("publication_date"),
                    "retrieved_at": retrieved,
                    "fetched_at": retrieved,
                    "rights": RIGHTS,
                    "rights_url": RIGHTS_URL,
                    "rights_scope": "Official U.S. federal government document text; third-party material, if any, may differ",
                    "source_attribution": "Federal Register, National Archives and Records Administration",
                    "text_origin": text_origin,
                    "text_sha256": digest,
                    "document_number": number,
                    "document_type": item.get("type"),
                    "citation": item.get("citation"),
                    "agencies": agencies,
                    "api_url": api_url,
                    "official_full_text_url": text_url,
                    "raw_response_sha256": hashlib.sha256(raw).hexdigest(),
                    "response_metadata": text_metadata,
                    "text_truncated": False,
                    "whitespace_normalized": text_origin.endswith("_xml"),
                    "verification_status": "raw_official_source_unverified",
                    "scope_note": "Evidence of what this official document states; not proof of every underlying claim",
                }
                records.append(record)
                seen.add(source_url)
                log["selected"] += 1
        except Exception as error:
            log["error"] = f"{type(error).__name__}: {error}"
            log["failed_at"] = _now()

    completed = len(records) == max_records
    manifest = {
        "started_at": started, "finished_at": _now(),
        "requested_records": max_records, "acquired_records": len(records),
        "published": completed,
        "selection": "Presidential documents first (60% target), then rules; newest API order; no topic or verdict filtering",
        "bounds": {"max_records": MAX_RECORDS, "max_index_bytes": MAX_INDEX_BYTES,
                   "max_document_bytes": MAX_DOCUMENT_BYTES},
        "sources": source_logs,
        "notes": [
            "Raw official evidence only; no generated or handwritten truth verdicts.",
            "A document establishes what its issuing source states, not universal truth.",
            "Full available plain text is retained; oversized or failed documents are excluded and logged.",
        ],
    }
    if completed:
        _atomic_jsonl(output / "corpus.jsonl", records)
        _atomic_json(output / "manifest.json", manifest)
    else:
        manifest["preservation"] = "Corpus and manifest were not replaced; last good files, if present, remain intact."
    # Every attempt is visible, including failures, without destroying last-good data.
    _atomic_json(output / "acquisition_log.json", manifest)
    return {
        "published": completed, "requested_records": max_records,
        "acquired_records": len(records), "output_dir": str(output),
        "source_summary": [
            {key: log.get(key) for key in ("source_id", "available", "selected", "error")}
            for log in source_logs
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path,
                        default=Path(__file__).resolve().parents[1] / "experiments" / "politics")
    parser.add_argument("--max-records", type=int, default=DEFAULT_MAX_RECORDS)
    args = parser.parse_args()
    report = refresh_current_politics(args.output_dir, args.max_records)
    print(json.dumps(report, indent=2))
    return 0 if report["published"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
