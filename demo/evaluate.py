#!/usr/bin/env python3
"""Serial, paired-local-API development evaluation; never a feed-coverage test.

python3 demo/evaluate.py --validate-only
python3 demo/evaluate.py --wait-model --output experiments/evidence/eval_results.json

Only case text and has_media are sent to the API; expected answers stay in this
process. The pairing token is read from a local file, never printed or saved.
Results are checkpointed after each case. Cached responses are identified and
their original model tokens are excluded from fresh-job token totals.
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import itertools
import json
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, build_opener, ProxyHandler
import uuid


ROOT = Path(__file__).resolve().parents[1]
VERDICTS = {"supported", "contradicted", "insufficient_evidence"}
MAX_RESPONSE_BYTES = 4 * 1024 * 1024


def utcnow():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def normalized(text):
    return " ".join(text.split())


def load_suite(cases_path, corpus_path):
    suite = json.loads(cases_path.read_text())
    corpus_bytes = corpus_path.read_bytes()
    digest = hashlib.sha256(corpus_bytes).hexdigest()
    if digest != suite["corpus_sha256"]:
        raise ValueError("The corpus differs from this suite's pinned evidence snapshot.")
    records = [json.loads(line) for line in corpus_bytes.decode().splitlines() if line.strip()]
    by_id = {row["evidence_id"]: row for row in records}
    by_url = {row["source_url"]: row for row in records}
    seen = set()
    for case in suite["cases"]:
        if case["id"] in seen:
            raise ValueError("Duplicate case ID in the evaluation suite.")
        seen.add(case["id"])
        if not isinstance(case["has_media"], bool) or not 3 <= len(case["text"]) <= 5000:
            raise ValueError("An evaluation input violates the API's request bounds.")
        expected = case["expected"]
        if (expected["kind"] == "no_factual_claim") != (not expected["claims"]):
            raise ValueError("Inconsistent expected claim classification.")
        for claim in expected["claims"]:
            if claim["verdict"] not in VERDICTS:
                raise ValueError("Unknown expected verdict.")
            ids, anchors = claim["evidence_ids"], claim["reference_quotes"]
            if len(ids) != len(anchors):
                raise ValueError("Reference quotes and evidence IDs must align.")
            if claim["verdict"] != "insufficient_evidence" and not ids:
                raise ValueError("Decisive expected claims need explicit evidence references.")
            for evidence_id, anchor in zip(ids, anchors):
                record = by_id[evidence_id]
                reference = suite["references"][evidence_id]
                if reference["text_sha256"] != hashlib.sha256(record["text"].encode()).hexdigest():
                    raise ValueError("An expected reference's text hash changed.")
                if not anchor or anchor not in record["text"]:
                    raise ValueError("An expected reference quote is absent from the source.")
    return suite, by_url


class API:
    def __init__(self, base, token_file):
        parsed = urlsplit(base)
        if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
                or parsed.username or parsed.password or parsed.query or parsed.fragment
                or parsed.path not in {"", "/"}):
            raise ValueError("The paired evaluation API must be an HTTP loopback origin.")
        self.base = base.rstrip("/")
        self.token_file = token_file
        # Avoid forwarding a local pairing token through environment HTTP proxies.
        self.opener = build_opener(ProxyHandler({}))

    def request(self, path, payload=None):
        token = self.token_file.read_text().strip()
        if not token or "\n" in token or "\r" in token:
            raise ValueError("The local pairing token file is missing or malformed.")
        request = Request(self.base + path,
                          data=None if payload is None else json.dumps(payload, ensure_ascii=False).encode(),
                          headers={"X-Factcheck-Token": token, "Content-Type": "application/json"},
                          method="GET" if payload is None else "POST")
        try:
            with self.opener.open(request, timeout=10) as response:
                data = response.read(MAX_RESPONSE_BYTES + 1)
            if len(data) > MAX_RESPONSE_BYTES:
                raise ValueError("Local API response exceeded the evaluation size allowance.")
            return json.loads(data)
        except HTTPError as error:
            # Do not print request headers, auth values, or arbitrary response bodies.
            raise RuntimeError(f"Local API returned HTTP {error.code} for {path.split('?')[0]}.") from None
        except (URLError, TimeoutError, OSError):
            raise RuntimeError("The local API could not be reached within its request timeout.") from None


def wait_ready(api, wait, timeout):
    deadline = time.monotonic() + timeout
    while True:
        try:
            stats = api.request("/api/stats")
            if stats.get("model_ready"):
                return stats
        except (RuntimeError, FileNotFoundError):
            pass
        if not wait or time.monotonic() >= deadline:
            raise RuntimeError("The paired local API/model is not ready. Start it or use --wait-model.")
        time.sleep(min(2, max(0, deadline - time.monotonic())))


def assess(case, job, suite, source_by_url):
    expected = case["expected"]
    result = job.get("result", {})
    rows = result.get("claims", [])
    criteria = {"completed": job.get("status") == "complete"}
    valid_rows = isinstance(rows, list) and all(isinstance(r, dict) for r in rows)
    criteria["claims_well_formed"] = valid_rows
    if not valid_rows:
        rows = []
    criteria["expected_claim_count"] = len(rows) == len(expected["claims"])
    criteria["verdict_multiset"] = (Counter(r.get("verdict") for r in rows)
                                    == Counter(g["verdict"] for g in expected["claims"]))
    criteria["whole_post_remains_unvalidated"] = result.get("whole_post_validated") is False
    criteria["resolved_scope_matches"] = (result.get("extracted_claims_resolved")
                                          is expected["extracted_claims_resolved"])
    criteria["classification"] = (bool(rows) if expected["kind"] == "factual" else
                                  not rows and result.get("rating") is None
                                  and result.get("label") == "No factual claim")
    if case["has_media"]:
        scope = str(result.get("scope", "")).lower()
        criteria["media_explicitly_unchecked"] = "media" in scope and (
            "unchecked" in scope or "not verified" in scope or "not been verified" in scope)
    bad_citations = []
    for index, row in enumerate(rows):
        citations = row.get("evidence", [])
        if not isinstance(citations, list):
            bad_citations.append({"claim_index": index, "reason": "evidence is not a list"})
            continue
        if row.get("verdict") in {"supported", "contradicted"} and not citations:
            bad_citations.append({"claim_index": index, "reason": "decisive verdict without evidence"})
        for citation in citations:
            if not isinstance(citation, dict):
                bad_citations.append({"claim_index": index, "reason": "malformed citation"})
                continue
            source = source_by_url.get(citation.get("source_url"))
            passage = citation.get("quote")
            if (not source or not isinstance(passage, str) or len(passage.strip()) < 12
                    or normalized(passage) not in normalized(source["text"])):
                bad_citations.append({"claim_index": index, "reason": "quote absent from pinned source"})
    criteria["citation_provenance"] = not bad_citations

    def compatible(gold, row):
        if gold["verdict"] != row.get("verdict"):
            return False
        expected_urls = {suite["references"][key]["source_url"] for key in gold["evidence_ids"]}
        actual_urls = {e.get("source_url") for e in row.get("evidence", []) if isinstance(e, dict)}
        return not expected_urls or bool(expected_urls & actual_urls)

    # Small (<=3 claims) bipartite matching catches verdicts assigned to wrong source topics.
    criteria["expected_verdict_source_pairs"] = len(rows) == len(expected["claims"]) and any(
        all(compatible(gold, row) for gold, row in zip(expected["claims"], permutation))
        for permutation in itertools.permutations(rows))
    return {"passed": all(criteria.values()), "criteria": criteria,
            "failed_criteria": [key for key, value in criteria.items() if not value],
            "citation_errors": bad_citations,
            "semantic_correctness_independently_judged": False}


def run_case(api, case, suite, source_by_url, timeout, poll_interval):
    started = time.monotonic()
    record = {"case_id": case["id"], "language": case["language"], "tags": case["tags"],
              "input": {"text": case["text"], "has_media": case["has_media"]},
              "expected": case["expected"], "started_at": utcnow()}
    try:
        submitted = api.request("/api/check", record["input"])
        record["submission"] = submitted
        record["submission_seconds"] = round(time.monotonic() - started, 3)
        job_id = submitted["job_id"]
        if not isinstance(job_id, str) or not 1 <= len(job_id) <= 128:
            raise ValueError("Local API returned an invalid job ID.")
        deadline = started + timeout
        polls = 0
        while True:
            job = api.request("/api/jobs/" + quote(job_id, safe=""))
            polls += 1
            if job.get("status") != "pending":
                record["job"] = job
                break
            if time.monotonic() >= deadline:
                record["job"] = {"status": "client_timeout", "job_id": job_id,
                                 "last_stage": job.get("stage"),
                                 "note": "Polling stopped; the backend job may still be running."}
                break
            time.sleep(min(poll_interval, max(0, deadline - time.monotonic())))
        record["poll_count"] = polls
    except (RuntimeError, ValueError, KeyError, OSError) as error:
        record["job"] = {"status": "client_error", "error": str(error)[:350]}
    record["wall_seconds"] = round(time.monotonic() - started, 3)
    record["completed_at"] = utcnow()
    record["assessment"] = assess(case, record["job"], suite, source_by_url)
    return record


def summarize(records):
    verdicts = Counter()
    statuses = Counter()
    fresh_input = fresh_output = reported_input = reported_output = 0
    fresh_seen = set()
    for record in records:
        job = record["job"]
        statuses[job.get("status", "unknown")] += 1
        result = job.get("result", {})
        verdicts.update(row.get("verdict", "unknown") for row in result.get("claims", []))
        usage = result.get("model_usage", [])
        tokens_in = sum(row.get("input_tokens") or 0 for row in usage)
        tokens_out = sum(row.get("output_tokens") or 0 for row in usage)
        reported_input += tokens_in
        reported_output += tokens_out
        submission = record.get("submission", {})
        key = submission.get("job_id")
        if key and key not in fresh_seen and not submission.get("cached") and not submission.get("coalesced"):
            fresh_seen.add(key)
            fresh_input += tokens_in
            fresh_output += tokens_out
    walls = sorted(r["wall_seconds"] for r in records)
    return {"cases_run": len(records), "passed_structural_expectations": sum(
                r["assessment"]["passed"] for r in records),
            "failed_case_ids": [r["case_id"] for r in records if not r["assessment"]["passed"]],
            "job_statuses": dict(statuses), "extracted_claim_verdict_counts": dict(verdicts),
            "cached_cases": sum(bool(r.get("submission", {}).get("cached")) for r in records),
            "coalesced_cases": sum(bool(r.get("submission", {}).get("coalesced")) for r in records),
            "cases_all_extracted_claims_resolved": sum(r["job"].get("result", {}).get(
                "extracted_claims_resolved") is True for r in records),
            "cases_whole_post_claimed_validated": sum(r["job"].get("result", {}).get(
                "whole_post_validated") is True for r in records),
            "result_reported_input_tokens_including_cached": reported_input,
            "result_reported_output_tokens_including_cached": reported_output,
            "fresh_completed_job_reported_input_tokens": fresh_input,
            "fresh_completed_job_reported_output_tokens": fresh_output,
            "token_accounting_limit": "Unresolved jobs may omit consumed tokens; reused job usage is excluded from fresh totals. These are result-reported counters, not complete GPU accounting.",
            "median_case_wall_seconds": None if not walls else round(
                (walls[(len(walls)-1)//2] + walls[len(walls)//2]) / 2, 3),
            "total_case_wall_seconds": round(sum(walls), 3),
            "real_feed_coverage_measured": False,
            "semantic_accuracy_independently_measured": False}


def checkpoint(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".tmp")
    temp.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    temp.chmod(0o600)
    temp.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://127.0.0.1:8870")
    parser.add_argument("--token-file", type=Path, default=ROOT / "demo/data/local-token")
    parser.add_argument("--cases", type=Path, default=ROOT / "experiments/evidence/eval_cases.json")
    parser.add_argument("--corpus", type=Path, default=ROOT / "experiments/evidence/corpus.jsonl")
    parser.add_argument("--output", type=Path, default=ROOT / "experiments/evidence/eval_runs" /
                        (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".json"))
    parser.add_argument("--case", action="append", dest="case_ids", help="Select an exact case ID; repeatable.")
    parser.add_argument("--wait-model", action="store_true")
    parser.add_argument("--ready-timeout", type=float, default=600)
    parser.add_argument("--case-timeout", type=float, default=420)
    parser.add_argument("--poll-interval", type=float, default=1.5)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    if min(args.ready_timeout, args.case_timeout, args.poll_interval) <= 0:
        parser.error("Timeouts and poll interval must be positive.")
    try:
        suite, source_by_url = load_suite(args.cases, args.corpus)
        cases = suite["cases"]
        if args.case_ids:
            selected = set(args.case_ids)
            if selected - {c["id"] for c in cases}:
                raise ValueError("One or more selected case IDs do not exist.")
            cases = [case for case in cases if case["id"] in selected]
        if args.validate_only:
            print(f"Validated {len(cases)} cases and all pinned source anchors. No API requests made.")
            return 0
        api = API(args.base_url, args.token_file)
        report = {"schema_version": 1, "run_id": str(uuid.uuid4()), "started_at": utcnow(),
                  "state": "waiting_for_model", "scope": suite["scope"],
                  "limitations": suite["selection_notes"], "base_url": api.base,
                  "suite_sha256": hashlib.sha256(args.cases.read_bytes()).hexdigest(),
                  "corpus_sha256": suite["corpus_sha256"], "selected_case_ids": [c["id"] for c in cases],
                  "references": suite["references"], "cases": []}
        checkpoint(args.output, report)
        try:
            report["stats_before"] = wait_ready(api, args.wait_model, args.ready_timeout)
            report["state"] = "running"
            checkpoint(args.output, report)
            for index, case in enumerate(cases, 1):
                print(f"[{index}/{len(cases)}] {case['id']}: submitted", flush=True)
                record = run_case(api, case, suite, source_by_url, args.case_timeout, args.poll_interval)
                report["cases"].append(record)
                report["summary"] = summarize(report["cases"])
                checkpoint(args.output, report)
                passed = record["assessment"]["passed"]
                print(f"  {'PASS' if passed else 'MISMATCH'} {record['wall_seconds']:.1f}s; "
                      f"{record['assessment']['failed_criteria']}", flush=True)
            report["state"] = "complete"
            try:
                report["stats_after"] = api.request("/api/stats")
            except RuntimeError:
                report["stats_after"] = {"available": False}
        except KeyboardInterrupt:
            report["state"] = "interrupted"
            report["note"] = "The harness stopped; any submitted backend job may still be running."
        except (RuntimeError, ValueError, OSError) as error:
            report["state"] = "error"
            report["error"] = str(error)[:350]
        report["completed_at"] = utcnow()
        report["summary"] = summarize(report["cases"])
        checkpoint(args.output, report)
        print(f"Saved {len(report['cases'])} results to {args.output}", flush=True)
        if report["state"] != "complete":
            return 130 if report["state"] == "interrupted" else 2
        return 1 if report["summary"]["failed_case_ids"] else 0
    except (ValueError, KeyError, OSError) as error:
        print(f"Evaluation setup failed: {str(error)[:350]}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
