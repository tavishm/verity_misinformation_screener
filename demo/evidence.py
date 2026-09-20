"""Local source retrieval. No network requests, model calls, or truth verdicts."""

from __future__ import annotations

import argparse
from bisect import bisect_left, bisect_right
from collections import OrderedDict
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import threading
from typing import Iterable, Mapping
import unicodedata
from urllib.parse import urlsplit


_STOPWORDS = frozenset(
    "a an the and or is are was were be been being to of in on at for from by "
    "with as it its this that these those i you we they he she them their our "
    "your my me his her have has had do does did can could would should will "
    "what which who when where how please check fact verify true false claim "
    "है हैं था थी थे का की के को से में पर और या यह वह इस उस एक ने कि क्या".split()
)
_PROVENANCE_FIELDS = (
    "evidence_id", "publisher", "author", "rights", "rights_url", "rights_scope",
    "text_origin", "date_warning", "published_at_raw", "text_truncated", "verification_status",
    "retrieved_at", "text_sha256", "response_sha256", "scope_note",
)


def query_terms(query: str) -> list[str]:
    """Extract literal Unicode keywords; never interpret the user's FTS syntax."""
    words, current = [], []
    for char in unicodedata.normalize("NFKC", query).casefold():
        if unicodedata.category(char)[0] in "LMN":
            current.append(char)
        elif current:
            words.append("".join(current))
            current = []
    if current:
        words.append("".join(current))
    # Negation and numbers intentionally remain. Limit pathological query size.
    return list(dict.fromkeys(word for word in words if word not in _STOPWORDS))[:32]


def _trim_span(text: str, start: int, end: int) -> tuple[int, int]:
    while start < end and text[start].isspace():
        start += 1
    while end > start and text[end - 1].isspace():
        end -= 1
    return start, end


def chunk_spans(text: str, max_chars: int = 1600) -> list[tuple[int, int]]:
    """Return Python character offsets into untouched text, including Hindi.

    Prefer paragraph and sentence boundaries. Long sentences split at whitespace;
    an unbroken token can split at the size limit. No source text is normalized.
    """
    if max_chars < 16:
        raise ValueError("max_chars must be at least 16")
    spans = []
    paragraph_start = 0
    paragraph_ends = [
        (match.start(), match.end())
        for match in re.finditer(r"(?:\r?\n)[ \t]*(?:\r?\n)+", text)
    ] + [(len(text), len(text))]
    for paragraph_end, next_start in paragraph_ends:
        start, end = _trim_span(text, paragraph_start, paragraph_end)
        paragraph_start = next_start
        while start < end:
            ceiling = min(start + max_chars, end)
            cut = ceiling
            if ceiling < end:
                region = text[start:ceiling]
                boundaries = list(re.finditer(r"[.!?।॥]+(?=\s)|\n", region))
                if boundaries:
                    cut = start + boundaries[-1].end()
                else:
                    spaces = list(re.finditer(r"\s+", region))
                    if spaces:
                        cut = start + spaces[-1].start()
                if cut <= start:
                    cut = ceiling
            actual_start, actual_end = _trim_span(text, start, cut)
            if actual_start < actual_end:
                spans.append((actual_start, actual_end))
            start = cut
            while start < end and text[start].isspace():
                start += 1
    return spans


def _context_span(text: str, start: int, end: int,
                  max_chars: int) -> tuple[int, int] | None:
    """Expand a verified span, keeping source blocks before partial words."""
    if end - start > max_chars:
        return None  # Never silently discard part of the matched evidence.

    def word_at(offset):
        left = right = offset
        while left > 0 and not text[left - 1].isspace():
            left -= 1
        while right < len(text) and not text[right].isspace():
            right += 1
        return left, right

    match_start, match_end = start, end
    # A stored chunk can split an unusually long token. Restore it if possible;
    # cutting within a token is allowed only when that token exceeds the cap.
    if start and not text[start - 1].isspace() and not text[start].isspace():
        left, right = word_at(start)
        if right - left <= max_chars:
            start = left
    if end < len(text) and not text[end - 1].isspace() and not text[end].isspace():
        left, right = word_at(end)
        if right - left <= max_chars:
            end = right
    if end - start > max_chars:
        return None

    blocks, previous = [], 0
    separators = list(re.finditer(r"(?:\r?\n)[ \t]*(?:\r?\n)+", text))
    for boundary, following in [(m.start(), m.end()) for m in separators] + [
        (len(text), len(text))
    ]:
        block = _trim_span(text, previous, boundary)
        if block[0] < block[1]:
            blocks.append(block)
        previous = following
    starts, ends = [a for a, _ in blocks], [b for _, b in blocks]
    first = bisect_right(ends, start)
    last = bisect_left(starts, end) - 1
    if first <= last and ends[last] - starts[first] <= max_chars:
        start, end = starts[first], ends[last]

    left_index = bisect_left(starts, start) - 1
    right_index = bisect_right(ends, end)
    while True:
        candidates = []
        if left_index >= 0 and end - starts[left_index] <= max_chars:
            candidates.append((starts[left_index], end, "left"))
        if right_index < len(ends) and ends[right_index] - start <= max_chars:
            candidates.append((start, ends[right_index], "right"))
        if not candidates:
            break
        start, end, direction = min(candidates, key=lambda span: (
            abs((match_start - span[0]) - (span[1] - match_end)),
            span[1] - span[0],
        ))
        if direction == "left":
            left_index -= 1
        else:
            right_index += 1

    spare = max_chars - (end - start)
    left = max(0, start - spare // 2)
    right = min(len(text), end + spare - (start - left))
    left = max(0, start - (spare - (right - end)))
    if left and not text[left - 1].isspace() and not text[left].isspace():
        word_start, word_end = word_at(left)
        if word_end - word_start <= max_chars:
            left = word_end
    if right < len(text) and not text[right - 1].isspace() and not text[right].isspace():
        word_start, word_end = word_at(right)
        if word_end - word_start <= max_chars:
            right = word_start
    left, right = _trim_span(text, left, right)
    if left > match_start or right < match_end:
        return None
    return left, right


class EvidenceIndex:
    """Versioned local evidence with bounded, revision-aware retrieval caching."""

    def __init__(self, path: str | Path):
        if str(path) != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(path), timeout=30, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.execute("PRAGMA journal_mode = WAL")
        self._cache: OrderedDict[tuple[str, int], list[dict]] = OrderedDict()
        self._cache_revision = -1
        with self._db:
            self._db.executescript("""
                CREATE TABLE IF NOT EXISTS evidence_meta (
                    key TEXT PRIMARY KEY, value INTEGER NOT NULL
                );
                INSERT OR IGNORE INTO evidence_meta VALUES ('revision', 0);
                INSERT OR IGNORE INTO evidence_meta VALUES ('schema_version', 2);
                CREATE TABLE IF NOT EXISTS documents (
                    id INTEGER PRIMARY KEY,
                    source_url TEXT NOT NULL UNIQUE,
                    current_version INTEGER
                );
                CREATE TABLE IF NOT EXISTS document_versions (
                    id INTEGER PRIMARY KEY,
                    document_id INTEGER NOT NULL REFERENCES documents(id),
                    version_hash TEXT NOT NULL,
                    text TEXT NOT NULL,
                    title TEXT NOT NULL,
                    language TEXT NOT NULL,
                    published_at TEXT,
                    fetched_at TEXT,
                    source_kind TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    UNIQUE(document_id, version_hash)
                );
                CREATE TABLE IF NOT EXISTS chunks (
                    id INTEGER PRIMARY KEY,
                    version_id INTEGER NOT NULL REFERENCES document_versions(id),
                    start_offset INTEGER NOT NULL,
                    end_offset INTEGER NOT NULL,
                    text TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS chunks_by_version ON chunks(version_id);
                CREATE VIRTUAL TABLE IF NOT EXISTS chunk_search USING fts5(
                    text,
                    tokenize="unicode61 remove_diacritics 0 categories 'L* N* Co M*'"
                );
            """)
            columns = {row["name"] for row in self._db.execute(
                "PRAGMA table_info(document_versions)"
            )}
            if "metadata_json" not in columns:
                self._db.execute(
                    "ALTER TABLE document_versions ADD COLUMN "
                    "metadata_json TEXT NOT NULL DEFAULT '{}'"
                )
                self._db.execute(
                    "UPDATE evidence_meta SET value=value+1 WHERE key='revision'"
                )
            self._db.execute(
                "UPDATE evidence_meta SET value=2 WHERE key='schema_version'"
            )

    @staticmethod
    def _record(record: Mapping) -> dict | None:
        if not isinstance(record, Mapping):
            return None
        text = record.get("text")
        source_url = record.get("source_url") or record.get("url")
        if not isinstance(text, str) or not text.strip():
            return None
        if not isinstance(source_url, str):
            return None
        source_url = source_url.strip()
        try:
            parsed_url = urlsplit(source_url)
        except ValueError:
            return None
        if parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc:
            return None
        result = {"text": text, "source_url": source_url}
        for key, default in (("title", ""), ("language", "und"),
                             ("published_at", None), ("fetched_at", None),
                             ("source_kind", "unknown")):
            value = (record.get("source_kind") or record.get("source_type", default)
                     if key == "source_kind" else record.get(key, default))
            result[key] = value if isinstance(value, str) else default
        metadata = {key: record[key] for key in _PROVENANCE_FIELDS if key in record}
        if "rights_scope" not in metadata and "rightsscope" in record:
            metadata["rights_scope"] = record["rightsscope"]
        try:
            result["metadata_json"] = json.dumps(metadata, ensure_ascii=False, sort_keys=True)
        except (TypeError, ValueError):
            return None
        return result

    def ingest_records(self, records: Iterable[Mapping]) -> dict:
        """Ingest records atomically. Invalid records are counted and skipped.

        Source text and bibliographic metadata define a version; a new fetch time
        alone updates metadata without duplicating text/chunks. Earlier versions
        remain stored for audit but are excluded from retrieval.
        """
        report = dict(records_seen=0, inserted=0, updated=0, unchanged=0,
                      metadata_updated=0, skipped=0, versions_created=0,
                      versions_reused=0, chunks_created=0)
        changed = False
        with self._lock, self._db:
            for original in records:
                report["records_seen"] += 1
                record = self._record(original)
                if record is None:
                    report["skipped"] += 1
                    continue
                version_fields = {key: value for key, value in record.items()
                                  if key not in {"source_url", "fetched_at"}}
                digest = hashlib.sha256(json.dumps(
                    version_fields, ensure_ascii=False, sort_keys=True
                ).encode("utf-8")).hexdigest()
                doc = self._db.execute(
                    "SELECT id, current_version FROM documents WHERE source_url=?",
                    (record["source_url"],),
                ).fetchone()
                is_new = doc is None
                if is_new:
                    cursor = self._db.execute(
                        "INSERT INTO documents(source_url) VALUES (?)",
                        (record["source_url"],),
                    )
                    doc_id, old_version = cursor.lastrowid, None
                else:
                    doc_id, old_version = doc["id"], doc["current_version"]
                version = self._db.execute(
                    "SELECT id, fetched_at FROM document_versions "
                    "WHERE document_id=? AND version_hash=?", (doc_id, digest)
                ).fetchone()
                if version is not None and version["id"] == old_version:
                    report["unchanged"] += 1
                    if (record["fetched_at"] is not None
                            and record["fetched_at"] != version["fetched_at"]):
                        self._db.execute(
                            "UPDATE document_versions SET fetched_at=? WHERE id=?",
                            (record["fetched_at"], old_version),
                        )
                        report["metadata_updated"] += 1
                        changed = True
                    continue
                if version is None:
                    cursor = self._db.execute(
                        "INSERT INTO document_versions "
                        "(document_id,version_hash,text,title,language,published_at,"
                        "fetched_at,source_kind,metadata_json) VALUES (?,?,?,?,?,?,?,?,?)",
                        (doc_id, digest, record["text"], record["title"],
                         record["language"], record["published_at"],
                         record["fetched_at"], record["source_kind"],
                         record["metadata_json"]),
                    )
                    version_id = cursor.lastrowid
                    spans = chunk_spans(record["text"])
                    self._db.executemany(
                        "INSERT INTO chunks(version_id,start_offset,end_offset,text) "
                        "VALUES (?,?,?,?)",
                        ((version_id, start, end, record["text"][start:end])
                         for start, end in spans),
                    )
                    report["versions_created"] += 1
                    report["chunks_created"] += len(spans)
                else:
                    version_id = version["id"]
                    report["versions_reused"] += 1
                    if record["fetched_at"] is not None:
                        self._db.execute(
                            "UPDATE document_versions SET fetched_at=? WHERE id=?",
                            (record["fetched_at"], version_id),
                        )
                if old_version is not None:
                    self._db.execute(
                        "DELETE FROM chunk_search WHERE rowid IN "
                        "(SELECT id FROM chunks WHERE version_id=?)", (old_version,),
                    )
                self._db.execute(
                    "INSERT INTO chunk_search(rowid,text) "
                    "SELECT id,text FROM chunks WHERE version_id=?", (version_id,),
                )
                self._db.execute(
                    "UPDATE documents SET current_version=? WHERE id=?",
                    (version_id, doc_id),
                )
                report["inserted" if is_new else "updated"] += 1
                changed = True
            if changed:
                self._db.execute(
                    "UPDATE evidence_meta SET value=value+1 WHERE key='revision'"
                )
            report["revision"] = self._revision()
        return report

    def ingest_jsonl(self, path: str | Path) -> dict:
        """Read a local corpus; malformed JSON raises with its line number."""
        def records():
            with Path(path).open(encoding="utf-8") as handle:
                for line_number, line in enumerate(handle, 1):
                    if not line.strip():
                        continue
                    try:
                        yield json.loads(line)
                    except json.JSONDecodeError as error:
                        raise ValueError(f"Invalid JSON on line {line_number}: {error.msg}") from error
        return self.ingest_records(records())

    def _revision(self) -> int:
        return self._db.execute(
            "SELECT value FROM evidence_meta WHERE key='revision'"
        ).fetchone()[0]

    @property
    def revision(self) -> int:
        """Current persistent revision, including writes by other instances."""
        with self._lock:
            return self._revision()

    def search(self, query: str, limit: int = 6) -> list[dict]:
        """Retrieve literal keyword matches, ranked then grouped by document.

        Offsets are Unicode character positions in the exact ingested document.
        Retrieval rank is relevance only; it is not a factuality confidence.
        """
        if not isinstance(query, str):
            raise TypeError("query must be a string")
        if not isinstance(limit, int) or isinstance(limit, bool) or not 0 <= limit <= 100:
            raise ValueError("limit must be an integer between 0 and 100")
        terms = query_terms(query)
        if not terms or limit == 0:
            return []
        match_query = " OR ".join('"' + term.replace('"', '""') + '"' for term in terms)
        key = (match_query, limit)
        with self._lock:
            revision = self._revision()
            if revision != self._cache_revision:
                self._cache.clear()
                self._cache_revision = revision
            if key in self._cache:
                self._cache.move_to_end(key)
                return [dict(row) for row in self._cache[key]]
            rows = self._db.execute("""
                SELECT c.id AS chunk_id,c.start_offset,c.end_offset,c.text,
                       d.id AS document_id,d.source_url,
                       v.version_hash AS document_version,v.title,v.language,
                       v.published_at,v.fetched_at,v.source_kind,v.metadata_json,
                       bm25(chunk_search) AS rank
                FROM chunk_search
                JOIN chunks c ON c.id=chunk_search.rowid
                JOIN document_versions v ON v.id=c.version_id
                JOIN documents d ON d.id=v.document_id AND d.current_version=v.id
                WHERE chunk_search MATCH ?
                ORDER BY rank,c.id LIMIT ?
            """, (match_query, limit)).fetchall()
            results = [dict(row) for row in rows]
            document_order = {}
            for row in results:
                document_order.setdefault(row["document_id"], len(document_order))
                row["sourceURL"] = row["source_url"]
                row.update(json.loads(row.pop("metadata_json")))
            results.sort(key=lambda row: (
                document_order[row["document_id"]], row["start_offset"]
            ))
            self._cache[key] = results
            if len(self._cache) > 256:
                self._cache.popitem(last=False)
            return [dict(row) for row in results]

    def lookup_exact_quote(self, title: str, quote: str) -> dict | None:
        """Locate literal wording in the current version of an exact-titled source.

        This proves only that the indexed document contains these exact
        characters. It does not establish the quoted statement as world truth,
        normalize either input, or fall back to historical document versions.
        The result is shaped like a retrieval hit and may be passed to
        :meth:`context` for bounded surrounding text.
        """
        if not isinstance(title, str):
            raise TypeError("title must be a string")
        if not isinstance(quote, str):
            raise TypeError("quote must be a string")
        if not title:
            raise ValueError("title must not be empty")
        if not 12 <= len(quote) <= 5000:
            raise ValueError("quote must be between 12 and 5000 characters")
        with self._lock:
            row = self._db.execute("""
                SELECT d.id AS document_id,d.source_url,
                       v.version_hash AS document_version,v.title,v.text AS document_text,
                       v.language,v.published_at,v.fetched_at,v.source_kind,v.metadata_json
                FROM documents d
                JOIN document_versions v ON v.id=d.current_version
                WHERE v.title=? AND instr(v.text,?)>0
                ORDER BY d.id LIMIT 1
            """, (title, quote)).fetchone()
        if row is None:
            return None
        result = dict(row)
        document_text = result.pop("document_text")
        start = document_text.find(quote)
        if start < 0:  # Defensive agreement check with SQLite's literal match.
            return None
        result.update(text=quote, start_offset=start, end_offset=start + len(quote),
                      sourceURL=result["source_url"])
        result.update(json.loads(result.pop("metadata_json")))
        return result

    def context(self, hit: Mapping, max_chars: int = 1800) -> dict | None:
        """Return bounded contiguous context from this hit's exact source version.

        The returned copy retains hit metadata, replaces text/source offsets,
        and records the incoming offsets as match_start_offset/match_end_offset.
        Paragraphs and neighboring table rows are kept whole where they fit;
        partial expansion stops at whitespace, unless a single token exceeds
        the bound. All offsets are Python character positions in untouched text.

        Return None for unknown/mismatched provenance, invalid source offsets,
        or a bound too small to retain the complete hit at valid boundaries.
        Historical versions remain usable; never substitute the current version.
        This method does not fetch, normalize, cache, or modify source records.
        """
        if not isinstance(hit, Mapping):
            raise TypeError("hit must be a mapping")
        if (not isinstance(max_chars, int) or isinstance(max_chars, bool)
                or max_chars < 16):
            raise ValueError("max_chars must be an integer of at least 16")
        document_id, version = hit.get("document_id"), hit.get("document_version")
        start, end = hit.get("start_offset"), hit.get("end_offset")
        if (not isinstance(document_id, int) or isinstance(document_id, bool)
                or not isinstance(version, str) or not version
                or not isinstance(start, int) or isinstance(start, bool)
                or not isinstance(end, int) or isinstance(end, bool)):
            return None
        with self._lock:
            source = self._db.execute("""
                SELECT v.text,d.source_url FROM document_versions v
                JOIN documents d ON d.id=v.document_id
                WHERE v.document_id=? AND v.version_hash=?
            """, (document_id, version)).fetchone()
        if source is None:
            return None
        text = source["text"]
        if (not 0 <= start < end <= len(text)
                or hit.get("text") != text[start:end]
                or any(key in hit and hit[key] != source["source_url"]
                       for key in ("source_url", "sourceURL"))):
            return None
        span = _context_span(text, start, end, max_chars)
        if span is None:
            return None
        left, right = span
        full_start, full_end = _trim_span(text, 0, len(text))
        return dict(hit, text=text[left:right], start_offset=left, end_offset=right,
                    match_start_offset=start, match_end_offset=end,
                    context_truncated=left > full_start or right < full_end)

    def stats(self) -> dict:
        with self._lock:
            result = {key: self._db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                      for key, table in (("documents", "documents"),
                                         ("versions", "document_versions"),
                                         ("chunks", "chunks"),
                                         ("active_chunks", "chunk_search"))}
            result["revision"] = self._revision()
            return result

    def close(self) -> None:
        with self._lock:
            self._db.close()

    def __enter__(self) -> "EvidenceIndex":
        return self

    def __exit__(self, *_args) -> None:
        self.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, help="Local SQLite database path")
    parser.add_argument("--jsonl", help="Local source corpus to ingest")
    parser.add_argument("--query", help="Literal keyword query")
    parser.add_argument("--limit", type=int, default=6)
    args = parser.parse_args()
    with EvidenceIndex(args.db) as index:
        result = {}
        if args.jsonl:
            result["ingest"] = index.ingest_jsonl(args.jsonl)
        if args.query is not None:
            result["results"] = index.search(args.query, args.limit)
        result["stats"] = index.stats()
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
