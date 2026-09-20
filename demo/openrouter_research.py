"""Opt-in, bounded OpenRouter research for the mobile demo.

This module is deliberately independent of the local checker. It requires a
separate cloud-consent signal and requests provider-side data-collection denial.
Contributor models retain their stricter, separate training-data consent gate.
The ledger contains accounting metadata only; it never stores a message, query,
URL, provider response, or source text.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import fcntl
import json
import math
from pathlib import Path
import re
import threading
import uuid
import unicodedata

import httpx

from .mobile_links import fetch_page
from .qualifiers import sentence_claims


MODEL = "deepseek/deepseek-v4.1-flash"
# Catalog rates, retained for display/planning only.  The ledger never
# estimates billed cost from these values; it settles solely on generation data.
PROMPT_TOKEN_PRICE = 1.5e-7
COMPLETION_TOKEN_PRICE = 6e-7
WEB_SEARCH_PRICE = 0.0025
KEY_PATH = Path.home() / ".config/forward-check/openrouter.key"
DEFAULT_LEDGER = Path(__file__).resolve().parent / "data/openrouter-cost-ledger.jsonl"
RESERVATION = 0.10
DAILY_LIMIT = 10.00
CUMULATIVE_LIMIT = 15.00
CONTRIBUTOR_PRIVACY_NOTICE = (
    "Muse Spark 1.3 Contributor may use prompts and outputs to improve Meta products. "
    "It must only receive a message after the person explicitly opts into this provider."
)

SYSTEM_PROMPT = """You are a cautious research assistant. Use public web search or public web fetch only when useful.
Return JSON only: {"claims":[{"text":"an unchanged complete sentence from the supplied text","verdict":"supported|contradicted|insufficient_evidence","citations":[{"url":"https://...","quote":"literal passage from that URL"}]}]}.
Select at most four factual external assertions. Do not select greetings, opinions, personal experiences, questions, instructions, or advice. Do not rewrite, merge, translate, or infer claims. A web result is not ground truth. Include a citation only when its literal quoted passage directly bears on the selected assertion. If evidence is missing or incomplete, use insufficient_evidence."""


class ResearchUnavailable(RuntimeError):
    pass


class CostBlocked(ResearchUnavailable):
    pass


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _safe_owner(value: str | None) -> str:
    value = value or "anonymous"
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value):
        return "anonymous"
    return value


class CostLedger:
    """Small append-only accounting ledger with conservative reservations."""
    def __init__(self, path: Path = DEFAULT_LEDGER):
        self.path = Path(path)
        self._lock = threading.Lock()

    def _locked_rows(self, handle):
        handle.seek(0)
        rows = []
        for line in handle:
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(row, dict):
                rows.append(row)
        return rows

    @staticmethod
    def _states(rows):
        states = {}
        for row in rows:
            job_id = row.get("job_id")
            if isinstance(job_id, str):
                states[job_id] = row
        return states

    @staticmethod
    def _totals(states, day):
        daily = cumulative = 0.0
        for row in states.values():
            amount = float(row.get("cost", row.get("reserved_cost", 0)) or 0)
            cumulative += amount
            if row.get("cost_bucket") == day:
                daily += amount
        return daily, cumulative

    def reserve(self, job_id: str | None, owner: str | None, model: str) -> str:
        try:
            checked_id = str(uuid.UUID(str(job_id))) if job_id else str(uuid.uuid4())
        except (TypeError, ValueError, AttributeError):
            checked_id = str(uuid.uuid4())
        now = _utc_now()
        row = {"job_id": checked_id, "owner_pseudonym": _safe_owner(owner),
               "cost_bucket": now.date().isoformat(), "model": model,
               "cost_state": "reserved", "reserved_cost": RESERVATION,
               "timestamp": now.isoformat()}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._lock, self.path.open("a+", encoding="utf-8") as handle:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
            try:
                states = self._states(self._locked_rows(handle))
                if checked_id in states:
                    raise CostBlocked("This research request already has a reserved or reconciled charge.")
                daily, cumulative = self._totals(states, row["cost_bucket"])
                if daily + RESERVATION > DAILY_LIMIT or cumulative + RESERVATION > CUMULATIVE_LIMIT:
                    raise CostBlocked("The optional research budget is exhausted.")
                handle.seek(0, 2)
                handle.write(json.dumps(row, separators=(",", ":")) + "\n")
                handle.flush()
            finally:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        return checked_id

    def settle(self, job_id: str, owner: str | None, *, model: str, cost: float | None,
               generation_id: str | None, usage: dict | None, cost_state: str):
        """Record only confirmed provider accounting, or retain an unknown reserve."""
        now = _utc_now()
        usage = usage if isinstance(usage, dict) else {}
        row = {"job_id": job_id, "owner_pseudonym": _safe_owner(owner),
               "cost_bucket": now.date().isoformat(), "model": model,
               "cost_state": cost_state, "timestamp": now.isoformat()}
        if cost is None:
            row["reserved_cost"] = RESERVATION
        else:
            row["cost"] = float(cost)
        if isinstance(generation_id, str) and generation_id:
            row["generation_id"] = generation_id[:200]
        # Names and numbers only: no request/response text, source URL, or query.
        for outgoing, incoming in (("prompt_tokens", "prompt_tokens"),
                                   ("completion_tokens", "completion_tokens"),
                                   ("reasoning_tokens", "reasoning_tokens"),
                                   ("web_search_requests", "web_search_requests"),
                                   ("web_fetch_requests", "web_fetch_requests"),
                                   ("web_search_results", "web_search_results")):
            value = usage.get(incoming)
            if isinstance(value, int) and value >= 0:
                row[outgoing] = value
        with self._lock, self.path.open("a+", encoding="utf-8") as handle:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
            try:
                handle.seek(0, 2)
                handle.write(json.dumps(row, separators=(",", ":")) + "\n")
                handle.flush()
            finally:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        return row


class OpenRouterResearch:
    """An off-by-default single-request research adapter."""
    completion_timeout = 90
    receipt_timeout = 30
    def __init__(self, *, enabled: bool = False, cloud_data_opt_in: bool = False,
                 cloud_opt_in: bool | None = None,
                 contributor_data_opt_in: bool = False, model: str = MODEL,
                 key_path: Path = KEY_PATH, ledger: CostLedger | None = None,
                 requester=None):
        self.enabled = enabled
        self.cloud_opt_in = cloud_data_opt_in if cloud_opt_in is None else cloud_opt_in
        self.contributor_data_opt_in = contributor_data_opt_in
        self.model = model
        self.key_path = Path(key_path)
        self.ledger = ledger or CostLedger()
        self.requester = requester

    @property
    def ready(self):
        contributor = self.model.endswith("-contributor")
        return self.enabled and self.cloud_opt_in and (not contributor or self.contributor_data_opt_in) and self.key_path.is_file()

    def _key(self):
        if not self.ready:
            contributor = self.model.endswith("-contributor")
            needed = "Contributor training-data consent" if contributor else "cloud research consent"
            raise ResearchUnavailable("Optional provider research is disabled pending explicit " + needed + ".")
        key = self.key_path.read_text(encoding="utf-8").strip()
        if not key:
            raise ResearchUnavailable("Optional provider research key is unavailable.")
        return key

    def request_body(self, text: str):
        return {"model": self.model, "stream": False, "max_tokens": 1500,
                "temperature": 0, "max_tool_calls": 2,
                "reasoning": {"effort": "low"},
                "provider": {"data_collection": "deny", "allow_fallbacks": True},
                "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                             {"role": "user", "content": text}],
                "response_format": {"type": "json_object"},
                "tools": [
                    {"type": "openrouter:web_search",
                     "parameters": {"max_uses": 2, "max_results": 2, "max_total_results": 4}},
                    {"type": "openrouter:web_fetch", "parameters": {"max_uses": 2}},
                ]}

    async def _request(self, method, url, *, headers, payload=None, params=None):
        if self.requester:
            return await self.requester(method, url, headers=headers, json=payload, params=params)
        # Keep the provider interaction to a completion and one generation
        # receipt request.  Do not require the optional ``h2`` package for a
        # demo that otherwise has no new dependency.
        async with httpx.AsyncClient(timeout=45) as client:
            response = await client.request(method, url, headers=headers, json=payload, params=params)
            response.raise_for_status()
            return response

    @staticmethod
    def _json(response):
        value = response.json()
        if not isinstance(value, dict):
            raise ValueError("Provider response was not an object.")
        return value

    @staticmethod
    def _content(data):
        choices = data.get("choices", [])
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            raise ValueError("Provider returned no research response.")
        content = choices[0].get("message", {}).get("content")
        if isinstance(content, list):
            content = "".join(part.get("text", "") for part in content if isinstance(part, dict))
        if not isinstance(content, str):
            raise ValueError("Provider response had no JSON content.")
        # Providers can surround otherwise valid structured output with a
        # fenced block or reasoning preface. Parse one object only; never use
        # prose as a claim or citation.
        start = content.find("{")
        if start < 0:
            raise ValueError("Provider response had no JSON object.")
        value, _ = json.JSONDecoder().raw_decode(content[start:])
        if not isinstance(value, dict):
            raise ValueError("Provider JSON was not an object.")
        return value

    async def _verified_sources(self, citations):
        if not isinstance(citations, list):
            return []
        def normalized(value):
            return ' '.join(unicodedata.normalize('NFKC', value).split())
        async def verify(item):
            if not isinstance(item, dict):
                return None
            url, quote = item.get("url"), item.get("quote")
            if not isinstance(url, str) or not isinstance(quote, str) or not 20 <= len(quote) <= 1200:
                return None
            try:
                page = await asyncio.wait_for(asyncio.to_thread(fetch_page, url), timeout=5)
            except Exception:
                return None
            # Web layout whitespace can differ; words, punctuation and negation
            # must still match. Never use fuzzy/semantic quote matching.
            if normalized(quote) not in normalized(page.get("text", "")):
                return None
            if getattr(self, '_needs_recent_sources', False):
                try:
                    published = datetime.fromisoformat(page.get('published_at', '').replace('Z', '+00:00'))
                    if published.tzinfo is None: published = published.replace(tzinfo=timezone.utc)
                    age = (_utc_now() - published).total_seconds()
                    if not -300 <= age <= 86400: return None
                except (ValueError, TypeError, AttributeError):
                    return None
            return {"source_url": page["url"], "title": page["title"],
                    "quote": quote, "source_kind": "public_web", "published_at": page.get('published_at')}
        return [value for value in await asyncio.gather(*(verify(item) for item in citations[:2])) if value]

    async def review(self, text: str, stage=lambda _message: None, job_id: str | None = None,
                     owner_pseudonym: str | None = None):
        """Research one explicitly opted-in message; never retries an ambiguous charge."""
        if not isinstance(text, str) or not text.strip():
            return self._unsure("No checkable factual statement was supplied.")
        candidates = sentence_claims(text)
        if not 1 <= len(candidates) <= 12:
            return self._unsure("The supplied text has too many statements for this bounded review.")
        try:
            key = self._key()
            ledger_job = self.ledger.reserve(job_id, owner_pseudonym, self.model)
        except ResearchUnavailable as error:
            return self._unsure(str(error), cost_state="configured_blocked")
        headers = {"Authorization": "Bearer " + key, "Content-Type": "application/json"}
        generation_id, usage, receipt_row = None, {}, None
        try:
            stage("Researching public sources with the optional provider")
            response = await asyncio.wait_for(self._request("POST", "https://openrouter.ai/api/v1/chat/completions",
                headers=headers, payload=self.request_body(text)), timeout=self.completion_timeout)
            data = self._json(response)
            generation_id = data.get("id") if isinstance(data.get("id"), str) else None
            usage = data.get("usage") if isinstance(data.get("usage"), dict) else {}
            # The completed response includes the actual provider-reported
            # charge. Generation receipts can lag behind it and return 404;
            # that delay must not discard an otherwise valid cited result.
            if not generation_id:
                raise ValueError("No generation identifier was returned for cost reconciliation.")
            reported_cost = usage.get("cost")
            if isinstance(reported_cost, (int, float)) and not isinstance(reported_cost, bool) and math.isfinite(reported_cost) and reported_cost >= 0:
                billing = {"total_cost": reported_cost}
            else:
                receipt = await asyncio.wait_for(self._request("GET", "https://openrouter.ai/api/v1/generation",
                    headers=headers, params={"id": generation_id}), timeout=self.receipt_timeout)
                billing = self._json(receipt).get("data")
            if not isinstance(billing, dict) or not isinstance(billing.get("total_cost"), (int, float)) or not math.isfinite(billing["total_cost"]) or billing["total_cost"] < 0:
                raise ValueError("Provider did not return an exact generation cost.")
            server_tools = usage.get("server_tool_use_details", usage.get("server_tool_use", {}))
            server_tools = server_tools if isinstance(server_tools, dict) else {}
            completion_details = usage.get("completion_tokens_details", {})
            completion_details = completion_details if isinstance(completion_details, dict) else {}
            usage = {"prompt_tokens": billing.get("tokens_prompt", usage.get("prompt_tokens")),
                     "completion_tokens": billing.get("tokens_completion", usage.get("completion_tokens")),
                     "reasoning_tokens": billing.get("native_tokens_reasoning", completion_details.get("reasoning_tokens", usage.get("reasoning_tokens"))),
                     "web_search_requests": server_tools.get("web_search_requests", 0),
                     "web_fetch_requests": billing.get("num_fetches", server_tools.get("web_fetch_requests", 0)),
                     "web_search_results": billing.get("num_search_results", 0)}
            receipt_row = self.ledger.settle(ledger_job, owner_pseudonym, model=self.model, cost=float(billing["total_cost"]),
                               generation_id=generation_id, usage=usage, cost_state="charged")
            parsed = self._content(data)
        except httpx.HTTPStatusError as error:
            # A completed 4xx response before any generation ID is a confirmed
            # non-generation; unlike a timeout, it is not an ambiguous charge.
            status = error.response.status_code
            if 400 <= status < 500 and not generation_id:
                self.ledger.settle(ledger_job, owner_pseudonym, model=self.model, cost=0.0, generation_id=None,
                                   usage=usage, cost_state="rejected_no_charge")
                return self._unsure("The optional provider rejected this research request before generation.",
                                    billing_job_id=ledger_job, cost_state="rejected_no_charge", cost_usd=0.0)
            self.ledger.settle(ledger_job, owner_pseudonym, model=self.model, cost=None, generation_id=generation_id,
                               usage=usage, cost_state="unknown_reserved")
            return self._unsure("Optional research could not publish a safely billed, source-verified result.",
                                billing_job_id=ledger_job, cost_state="unknown_reserved")
        except Exception:
            if receipt_row is not None:
                return self._unsure("The optional provider response did not contain a safely usable cited result.",
                                    billing_job_id=ledger_job, cost_state="charged", cost_usd=receipt_row["cost"])
            self.ledger.settle(ledger_job, owner_pseudonym, model=self.model, cost=None, generation_id=generation_id,
                               usage=usage, cost_state="unknown_reserved")
            return self._unsure("Optional research could not publish a safely billed, source-verified result.",
                                billing_job_id=ledger_job, cost_state="unknown_reserved")
        allowed = set(candidates)
        claims = []
        for item in parsed.get("claims", [])[:4] if isinstance(parsed.get("claims"), list) else []:
            if isinstance(item, dict) and type(item.get('sentence_id')) is int and 1 <= item['sentence_id'] <= len(candidates):
                item = dict(item, text=candidates[item['sentence_id'] - 1])
            if not isinstance(item, dict) or item.get("text") not in allowed:
                continue
            verdict = item.get("verdict")
            sources = await self._verified_sources(item.get("citations"))
            if verdict not in ("supported", "contradicted") or not sources:
                verdict, sources = "insufficient_evidence", []
            explanation = ("The cited public passages support this selected assertion. Read them below."
                           if verdict == "supported" else
                           "The cited public passages contradict this selected assertion. Read them below."
                           if verdict == "contradicted" else
                           "No source passage was verified for this selected assertion.")
            claims.append({"text": item["text"], "verdict": verdict, "evidence": sources,
                           "explanation": explanation})
        if not claims:
            return self._unsure("No cited, unchanged factual statement could be safely verified.",
                                billing_job_id=ledger_job, cost_state="charged", cost_usd=receipt_row["cost"])
        verdicts = [claim["verdict"] for claim in claims]
        rating = 4 if all(value == "supported" for value in verdicts) else 2 if all(value == "contradicted" for value in verdicts) else 3
        label = "Probably true" if rating == 4 else "Likely false" if rating == 2 else "Unsure"
        return {"rating": rating, "label": label, "claims": claims, "whole_post_validated": False,
                "assessment_basis": "web_sources", "source_verified": any(claim["evidence"] for claim in claims),
                "scope": "Only the selected unchanged factual statements and verified public source passages were reviewed. The full message, personal context, links, and media were not validated.",
                "experimental": True, "provider": "DeepSeek V4.1 Flash via OpenRouter",
                "billing_job_id": ledger_job, "cost_state": "charged", "cost_usd": receipt_row["cost"]}

    @staticmethod
    def _unsure(note, *, billing_job_id=None, cost_state=None, cost_usd=None):
        return {"rating": 3, "label": "Unsure", "claims": [], "whole_post_validated": False,
                "assessment_basis": "unresolved", "source_verified": False, "summary": "We could not check this message.",
                "scope": "No optional provider verdict was published.", "notes": [note], "experimental": True,
                "provider": "DeepSeek V4.1 Flash via OpenRouter", "billing_job_id": billing_job_id,
                "cost_state": cost_state, "cost_usd": cost_usd}


class BriefWebResearch(OpenRouterResearch):
    """One live lookup for short text, using the same consent and cost ledger."""
    completion_timeout = 12
    receipt_timeout = 4

    def request_body(self, text):
        from .quick_check import LIVE_CUES
        historical = any(int(year) < _utc_now().year for year in re.findall(r'\b(?:19|20)\d{2}\b', text))
        self._needs_recent_sources = bool(LIVE_CUES.search(text)) and not historical
        body = super().request_body(text)
        body.update(max_tokens=650, max_tool_calls=1)
        body['tools'] = [{"type": "openrouter:web_search",
                          "parameters": {"max_uses": 1, "max_results": 2, "max_total_results": 2}}]
        body['messages'] = [
            {'role': 'system', 'content':
             'Check the numbered factual statement with ONE targeted web search. Today is ' + _utc_now().date().isoformat() + '. '
             'For present-tense news, find reporting from the last 24 hours. A debunk of a past death rumor does NOT settle a claim made today. '
             'A lack of news does NOT prove someone is alive or dead. '
             'Return JSON only: {"claims":[{"sentence_id":1,"verdict":"supported|contradicted|insufficient_evidence",'
             '"citations":[{"url":"https://...","quote":"short literal passage from this page"}]}]}. '
             'Choose the original sentence by ID. Do not rewrite it. Use a source that directly addresses it; '
             'unrelated background is insufficient. One relevant citation is enough. Do not keep searching.'},
            {'role': 'user', 'content': json.dumps({'sentences': [{'id': i, 'text': sentence}
                                for i, sentence in enumerate(sentence_claims(text), 1)]}, ensure_ascii=False)}]
        return body
