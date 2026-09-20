#!/usr/bin/env python3
"""Fetch a bounded, deterministic RSS evidence sample; never generate verdicts.

Sources are publisher-provided feeds. Selection is the first N items in each feed's
order, irrespective of category, headline, topic, or any later model conclusion.
No page crawling, media downloads, paid APIs, authentication, or retry/bypass.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET


MAX_BYTES = 1_048_576
MAX_TEXT_CHARS = 40_000
USER_AGENT = (
    "FactCheckerEvidencePilot/0.1 "
    "(bounded read-only RSS reader; contact the repository owner)"
)
CONTENT = "{http://purl.org/rss/1.0/modules/content/}encoded"
CREATOR = "{http://purl.org/dc/elements/1.1/}creator"
SOURCES = [
    dict(id="altnews_en", publisher="Alt News", language="en", limit=10,
         url="https://www.altnews.in/feed/", source_type="publisher_article",
         rights="CC BY 3.0 except otherwise noted and third-party materials",
         rights_url="https://www.altnews.in/about/"),
    dict(id="altnews_hi", publisher="Alt News", language="hi", limit=10,
         url="https://www.altnews.in/hindi/feed/", source_type="publisher_article",
         rights="CC BY 3.0 except otherwise noted and third-party materials",
         rights_url="https://www.altnews.in/about/"),
    dict(id="rbi_releases", publisher="Reserve Bank of India", language="en", limit=5,
         url="https://rbi.org.in/pressreleases_rss.xml", source_type="official_release_feed_text",
         rights="Publisher-provided RSS; no blanket open-content license established",
         rights_url="https://www.rbi.org.in/Scripts/rss.aspx"),
    dict(id="rbi_notifications", publisher="Reserve Bank of India", language="en", limit=5,
         url="https://rbi.org.in/notifications_rss.xml", source_type="official_notification_feed_text",
         rights="Publisher-provided RSS; no blanket open-content license established",
         rights_url="https://www.rbi.org.in/Scripts/rss.aspx"),
    dict(id="pib_en", publisher="Press Information Bureau", language="en", limit=2,
         url="https://pib.gov.in/RssMain.aspx?ModId=6&Lang=1&Regid=3", source_type="official_release_feed_text",
         rights="PIB reproduction policy: attribution, accurate context, third-party exclusions",
         rights_url="https://www.pib.gov.in/Content/102_2_Copyright-Policy.aspx?lang=1&reg=3"),
    dict(id="pib_hi", publisher="Press Information Bureau", language="hi", limit=2,
         url="https://pib.gov.in/RssMain.aspx?ModId=6&Lang=2&Regid=3", source_type="official_release_feed_text",
         rights="PIB reproduction policy: attribution, accurate context, third-party exclusions",
         rights_url="https://www.pib.gov.in/Content/102_2_Copyright-Policy.aspx?lang=1&reg=3"),
]


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class TextOnly(HTMLParser):
    """Retain supplied prose and tables, omit obvious external/media containers."""

    OMIT = {"script", "style", "noscript", "iframe", "figure", "figcaption", "blockquote",
            "q", "video", "audio", "picture", "svg", "object", "embed"}
    VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link",
            "meta", "param", "source", "track", "wbr"}
    BLOCK = {"p", "div", "h1", "h2", "h3", "h4", "li", "ul", "ol", "tr", "table", "section"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.stack: list[tuple[str, bool]] = []
        self.parts: list[str] = []
        self.omitted: dict[str, int] = {}

    def suppressed(self) -> bool:
        return any(x[1] for x in self.stack)

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        klass = (values.get("class") or "").lower()
        exclude = tag in self.OMIT or any(x in klass for x in (
            "twitter-tweet", "instagram-media", "wp-block-embed", "fb-post"))
        if exclude or tag in {"img", "source", "track"}:
            self.omitted[tag] = self.omitted.get(tag, 0) + 1
        if tag not in self.VOID:
            self.stack.append((tag, exclude))
        if not self.suppressed():
            if tag in self.BLOCK or tag == "br":
                self.parts.append("\n")
            elif tag in {"td", "th"}:
                self.parts.append(" | ")

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                break
        if tag in self.BLOCK and not self.suppressed():
            self.parts.append("\n")

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID:
            self.handle_endtag(tag)

    def handle_data(self, data):
        if not self.suppressed():
            self.parts.append(data)

    def text(self) -> str:
        lines = [re.sub(r"[\t\r \u00a0]+", " ", x).strip() for x in "".join(self.parts).split("\n")]
        return "\n\n".join(x for x in lines if x)


def clean(s: str) -> tuple[str, dict]:
    parser = TextOnly()
    parser.feed(s)
    parser.close()
    return parser.text(), parser.omitted


def date_info(value: str) -> tuple[str | None, str | None]:
    if not value:
        return None, "not supplied"
    try:
        parsed = parsedate_to_datetime(value)
        if parsed.tzinfo is None:
            return None, "timezone absent; original publisher timestamp retained without guessing"
        return parsed.astimezone(timezone.utc).isoformat(), None
    except (ValueError, TypeError, OverflowError):
        return None, "unparsed publisher timestamp retained"


def acquire(output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    rows = []
    run = {"started_at": now(), "selection": "first N feed entries in publisher order; no verdict/topic filtering",
           "max_response_bytes": MAX_BYTES, "max_text_characters_per_item": MAX_TEXT_CHARS,
           "user_agent": USER_AGENT, "sources": [], "notes": [
               "Raw evidence only; no generated or handwritten truth verdicts.",
               "Article text comes from RSS; no article or embedded third-party URL was fetched.",
               "Removal of obvious embed/quotation containers is a bounded HTML rule, not a complete rights audit.",
               "RBI rows retain publisher-supplied feed descriptions, not independently fetched full articles; external republication rights remain unestablished.",
               "Cross-language versions and syndicated reporting are not independent evidence.",
           ]}
    seen = set()
    for src in SOURCES:
        log = {"source_id": src["id"], "feed_url": src["url"], "limit": src["limit"],
               "attempted_at": now(), "selected": 0, "excluded": []}
        run["sources"].append(log)
        try:
            req = urllib.request.Request(src["url"], headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=25) as response:
                data = response.read(MAX_BYTES + 1)
                log.update(status=response.status, fetched_at=now(), final_url=response.url,
                           content_type=response.headers.get("Content-Type"),
                           etag=response.headers.get("ETag"), last_modified=response.headers.get("Last-Modified"),
                           response_bytes=len(data))
            if len(data) > MAX_BYTES:
                log["error"] = "response exceeds acquisition byte cap; feed excluded"
                continue
            log["response_sha256"] = hashlib.sha256(data).hexdigest()
            root = ET.fromstring(data)
            items = root.findall("./channel/item")
            log["feed_items_available"] = len(items)
            log["feed_language"] = root.findtext("./channel/language")
            for position, item in enumerate(items[:src["limit"]], 1):
                url = (item.findtext("link") or "").strip()
                if not url or url in seen:
                    log["excluded"].append({"position": position, "url": url, "reason": "missing URL or duplicate canonical URL"})
                    continue
                full = item.findtext(CONTENT)
                supplied = full or item.findtext("description") or ""
                text, omitted = clean(supplied)
                if not text:
                    log["excluded"].append({"position": position, "url": url, "reason": "no retained feed text"})
                    continue
                truncated = len(text) > MAX_TEXT_CHARS
                text = text[:MAX_TEXT_CHARS]
                published_raw = item.findtext("pubDate") or ""
                published, date_warning = date_info(published_raw)
                title, _ = clean(item.findtext("title") or "")
                rows.append({
                    "evidence_id": src["id"] + ":" + hashlib.sha256(url.encode()).hexdigest()[:16],
                    "source_url": url, "title": title, "publisher": src["publisher"],
                    "author": item.findtext(CREATOR), "language": src["language"],
                    "source_type": src["source_type"], "feed_url": src["url"],
                    "feed_position": position, "categories": [x.text for x in item.findall("category") if x.text],
                    "published_at": published, "published_at_raw": published_raw, "date_warning": date_warning,
                    "fetched_at": log["fetched_at"], "rights": src["rights"], "rights_url": src["rights_url"],
                    "rights_scope": "publisher's own prose; obvious third-party/media containers removed",
                    "text_origin": "rss_content_encoded" if full else "rss_description",
                    "text": text, "text_sha256": hashlib.sha256(text.encode()).hexdigest(),
                    "omitted_html_containers": omitted, "text_truncated": truncated,
                    "feed_response_sha256": log["response_sha256"],
                    "verification_status": "raw_source_evidence_unverified",
                })
                log["selected"] += 1
                seen.add(url)
        except urllib.error.HTTPError as error:
            log.update(status=error.code, error=str(error), failed_at=now())
        except Exception as error:
            log.update(error=f"{type(error).__name__}: {error}", failed_at=now())
    run.update(finished_at=now(), corpus_rows=len(rows))
    with (output / "corpus.jsonl").open("w", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    (output / "acquisition_log.json").write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"corpus_rows": len(rows), "sources": [{k: s.get(k) for k in ("source_id", "status", "selected", "error")} for s in run["sources"]]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    print(json.dumps(acquire(args.output_dir), indent=2))
