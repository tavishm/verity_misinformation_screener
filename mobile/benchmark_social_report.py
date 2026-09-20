#!/usr/bin/env python3
"""Bounded development test of the final, non-search social research report.

Uses preserved publisher RSS excerpts plus explicitly synthetic boundary cases.
This measures a decision step, not retrieval quality or field accuracy.
"""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PROMPT = (ROOT / "mobile/android/assets/social_report_prompt.txt").read_text()
NOW = "2026-09-20T06:15:00Z"

def fixture(text):
    return {"title": "Synthetic test passage", "url": "https://example.org/fixture",
            "quote": text, "published": NOW}

def main():
    saved = json.loads((ROOT / "dist/social-iit-quick-check.json").read_text())["rows"][0]["sources"]
    mother = [s for s in saved if "Mother" in s["title"] or "Mother" in s["quote"] or "mother" in s["quote"]]
    cases = [
        ("real emotional headline", "Unfortunate death of a student at IIT Bombay", saved, "true"),
        ("real attributed allegation", "Mother of IIT Bombay student says her son was killed and tortured.", mother, "true"),
        ("accusation is not proof", "An IIT Bombay professor murdered the student.", saved, "uncertain"),
        ("same event correction", "The committee approved the proposal on Monday.", [fixture("The committee rejected the proposal on Monday after a three-hour meeting.")], "false"),
        ("missing payload", "The mission launched Tuesday carrying four astronauts.", [fixture("The mission launched successfully on Tuesday from the coastal space centre.")], "uncertain"),
        ("unrelated city", "20 people were arrested in Delhi September 19.", [fixture("20 people were arrested in Mumbai September 19, police said.")], "uncertain"),
        ("personal greeting", "Happy Diwali to everyone!", [], "skip"),
        ("complete visible sentence", "The mission launched on Tuesday. #Space Show more", [fixture("The mission launched successfully on Tuesday from the coastal space centre.")], "true"),
        ("unseen image provenance", "This photo shows the bridge collapsing this morning.", [fixture("The bridge collapsed this morning. Investigators are at the scene.")], "uncertain"),
        ("unknown cause is not disproof", "The landlord deliberately started the fire.", [fixture("Investigators have not determined the cause of the fire.")], "uncertain"),
        ("reported claim is supported", "A witness accused the mayor of bribery.", [fixture("A witness alleged that the mayor took a bribe during the contract talks.")], "true"),
        ("source adds details", "The spacecraft launched on Tuesday.", [fixture("The spacecraft launched from Florida on Tuesday with four astronauts aboard.")], "true"),
    ]
    key = (Path.home() / ".config/forward-check/openrouter.key").read_text().strip()

    def run(case):
        name, claim, sources, expected = case
        evidence = [{"index": i, "title": s["title"], "url": s["url"], "published": s["published"], "excerpt": s["quote"]} for i, s in enumerate(sources)]
        nested = {"claim": claim, "checked_at_utc": NOW, "sources": evidence, "visual_context_unchecked": "photo" in claim, "visible_text_only": "Show more" in claim}
        payload = {"post_text": json.dumps(nested), "links": [], "link_status": "unavailable", "image_status": "unavailable"}
        body = {"model": "qwen/qwen3.7-flash", "temperature": 0, "max_tokens": 320, "reasoning": {"enabled": False}, "response_format": {"type": "json_object"},
                "provider": {"sort": "price", "max_price": {"prompt": .15, "completion": .60, "request": 0}},
                "messages": [{"role": "system", "content": PROMPT}, {"role": "user", "content": [{"type": "text", "text": json.dumps(payload)}]}]}
        row = {"case": name, "expected": expected, "input": payload}
        start = time.monotonic()
        try:
            request = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", data=json.dumps(body).encode(), headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=12) as response:
                data = json.load(response)
            choice = data["choices"][0]
            answer = json.loads(choice["message"]["content"])
            valid = choice["finish_reason"] == "stop" and (answer.get("v") not in ("true", "false") or (answer.get("relation") == "same_event" and isinstance(answer.get("source"), int) and 0 <= answer["source"] < len(sources)))
            actual = answer.get("v")
            row.update(answer=answer, generation_id=data.get("id"), usage=data.get("usage"), finish_reason=choice["finish_reason"])
            if actual == "false" and valid:
                premise = sources[answer["source"]]["quote"]
                guard = {**body, "max_tokens": 80, "messages": [{"role": "system", "content": (ROOT / "mobile/android/assets/social_evidence_prompt.txt").read_text()}, {"role": "user", "content": json.dumps({"premise": premise, "hypothesis": claim})}]}
                request = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", data=json.dumps(guard).encode(), headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
                with urllib.request.urlopen(request, timeout=12) as response:
                    guard_data = json.load(response)
                guard_answer = json.loads(guard_data["choices"][0]["message"]["content"])
                row.update(false_guard=guard_answer, guard_usage=guard_data.get("usage"), guard_generation_id=guard_data.get("id"))
                if guard_data["choices"][0]["finish_reason"] != "stop" or guard_answer.get("label") != "contradicts":
                    actual = "uncertain"
            row.update(actual=actual, passed=valid and actual == expected)
        except Exception as error:
            row.update(error=type(error).__name__, passed=False, cost_unknown=True)
        row["seconds"] = round(time.monotonic() - start, 3)
        return row

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        rows = list(pool.map(run, cases))
    result = {"scope": __doc__, "prompt_sha256": hashlib.sha256(PROMPT.encode()).hexdigest(), "count": len(rows), "passed": sum(r["passed"] for r in rows), "provider_reported_cost_usd": sum((r.get("usage") or {}).get("cost", 0) + (r.get("guard_usage") or {}).get("cost", 0) for r in rows), "unknown_charge_count": sum(bool(r.get("cost_unknown")) for r in rows), "rows": rows}
    (ROOT / "dist/social-report-regression.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k not in ("rows", "scope")}))
    for row in rows:
        print(row["case"], row.get("answer", row.get("error")), row["seconds"])
    raise SystemExit(0 if result["passed"] == len(rows) else 1)

if __name__ == "__main__":
    main()
