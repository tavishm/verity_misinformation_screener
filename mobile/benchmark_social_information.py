#!/usr/bin/env python3
"""Paid regression for the proposed one-call cached-news information screen."""
import concurrent.futures, json, pathlib, statistics, time, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
MODEL, URL, MAX_COST_USD = "qwen/qwen3.7-flash", "https://openrouter.ai/api/v1/chat/completions", .04
PRIOR_PROMPT_ITERATION_COST_USD = .001295314
DATE = "2026-09-20T06:00:00Z"

def source(excerpt, published=DATE):
    return {"index": 0, "title": "Development fixture", "published": published, "excerpt": excerpt}

CASES = [
    ("opinion", "This politician is the worst leader we have ever had.", [], "skip"),
    ("question", "Which advertisement annoys you most?", [], "skip"),
    ("mixed fact and opinion", "I hate this policy: 10,000 people were arrested yesterday.", [], "research"),
    ("no evidence", "The bridge collapsed this morning.", [], "research"),
    ("direct support", "Earth has one natural moon.", [source("Earth has one natural moon, while Mars has two small moons.")], "true"),
    ("direct contradiction", "The committee approved the proposal on Monday.", [source("The committee rejected the proposal on Monday after a three-hour meeting.")], "false"),
    ("unrelated location", "20 people were arrested in Delhi on September 19.", [source("Exactly 20 people were arrested in Mumbai on September 19, police said.")], "research"),
    ("explicit location correction", "20 people were arrested in Delhi on September 19.", [source("Police said the 20 arrests were in Mumbai, not Delhi, on September 19.")], "false"),
    ("unrelated date", "20 people were arrested in Delhi on September 19.", [source("Exactly 20 people were arrested in Delhi on September 18, police said.")], "research"),
    ("same event number", "20 people were arrested in Mumbai on September 19.", [source("Police said exactly 10 people were arrested in Mumbai on September 19.")], "false"),
    ("unknown number", "20 people were arrested in Mumbai.", [source("People were arrested in Mumbai, but police did not release a number.")], "research"),
    ("allegation is not fact", "The mayor took a bribe.", [source("A witness alleged that the mayor took a bribe during the contract talks.")], "research"),
    ("attribution supported", "A witness alleged that the mayor took a bribe.", [source("A witness alleged that the mayor took a bribe during the contract talks.")], "true"),
    ("appearance versus death", "The actor died today.", [source("The actor appeared in public yesterday at an awards ceremony.")], "research"),
    ("partial mixed claim", "The mission launched Tuesday carrying four astronauts.", [source("The mission launched successfully on Tuesday from the coastal space centre.")], "research"),
]

def request_body(prompt, payload):
    # Mirrors the current non-web quick/evidence request settings.
    return {"model": MODEL, "temperature": 0, "max_tokens": 80,
        "reasoning": {"enabled": False}, "response_format": {"type": "json_object"},
        "provider": {"sort": "price", "max_price": {"prompt": .15, "completion": .60, "request": 0}},
        "messages": [{"role": "system", "content": prompt},
                     {"role": "user", "content": json.dumps(payload, ensure_ascii=False)}]}

def provenance(answer, sources):
    if answer.get("v") not in ("true", "false"):
        return answer.get("source") == -1 and answer.get("quote", "") == ""
    index, quote = answer.get("source"), answer.get("quote", "")
    return answer.get("relation") == "same_event" and isinstance(index, int) and 0 <= index < len(sources) and 20 <= len(quote) <= 400 and quote in sources[index]["excerpt"]

def call(item):
    name, claim, sources, expected, prompt = item
    key = (pathlib.Path.home()/".config/forward-check/openrouter.key").read_text().strip()
    body = request_body(prompt, {"claim": claim, "sources": sources})
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers={"Authorization":"Bearer "+key,"Content-Type":"application/json"})
    row = {"case":name,"expected":expected,"reserved_usd":0,"request_shape":"proposed SocialRequests non-web body + SocialEvidence.screeningInput"}
    started=time.monotonic()
    try:
        with urllib.request.urlopen(req,timeout=15) as response: data=json.load(response)
        answer=json.loads(data["choices"][0]["message"]["content"]); valid=provenance(answer,sources)
        actual=answer.get("v","invalid") if valid else "invalid_provenance"
        exact=actual==expected
        safe=exact or (expected in ("true","false") and actual=="research")
        row.update(actual=actual,passed=safe,exact_match=exact,answer=answer,provenance_valid=valid,
                   usage=data.get("usage"),generation_id=data.get("id"),finish_reason=data.get("choices",[{}])[0].get("finish_reason"))
    except urllib.error.HTTPError as error: row.update(passed=False,error="HTTPError",http_status=error.code,charge="unknown")
    except Exception as error: row.update(passed=False,error=type(error).__name__,charge="unknown")
    row["seconds"]=round(time.monotonic()-started,3)
    return row

def main():
    prompt=(ROOT/"mobile/android/assets/social_information_prompt.txt").read_text()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        rows=list(pool.map(call,[(*case,prompt) for case in CASES]))
    cost=sum(float((r.get("usage") or {}).get("cost") or 0) for r in rows)
    unknown=sum(r.get("charge")=="unknown" for r in rows); lat=[r["seconds"] for r in rows]
    decisive=[r for r in rows if r["expected"] in ("true","false")]
    result={"scope":"Small hand-authored development regression of a proposed one-call opinion + cached-evidence route. No retrieval, source-page, phone, or field-accuracy measurement.",
        "model":MODEL,"count":len(rows),"safe_outcomes":sum(r.get("passed",False) for r in rows),
        "exact_expected_matches":sum(r.get("exact_match",False) for r in rows),
        "decisive_coverage":{"matched":sum(r.get("exact_match",False) for r in decisive),"eligible":len(decisive)},"reported_cost_usd":cost,
        "prior_prompt_iterations_reported_cost_usd":PRIOR_PROMPT_ITERATION_COST_USD,
        "total_development_reported_cost_usd":PRIOR_PROMPT_ITERATION_COST_USD+cost,
        "maximum_authorized_cost_usd":MAX_COST_USD,"unknown_charge_count":unknown,"reserved_cost_usd":0,
        "latency_seconds":{"median":statistics.median(lat),"max":max(lat),"note":"Concurrent development API latency; excludes retrieval, phone networking and UI."},"rows":rows}
    (ROOT/"dist/social-information-benchmark.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    lines=["# Social information benchmark","",result["scope"],"",f"- Safe outcome: **{result['safe_outcomes']}/{result['count']}**",f"- Exact expected outcome: **{result['exact_expected_matches']}/{result['count']}**",f"- Decisive source coverage: **{result['decisive_coverage']['matched']}/{result['decisive_coverage']['eligible']}** clear true/false cases; the remainder abstained",f"- Final run provider-reported cost: **${cost:.8f}**; all prompt iterations: **${PRIOR_PROMPT_ITERATION_COST_USD+cost:.8f}**; unknown-charge failures: **{unknown}**; local reservations: **$0**",f"- Concurrent API latency: median **{statistics.median(lat):.3f}s**, maximum **{max(lat):.3f}s** (not end-to-end)","","| Case | Expected | Actual | Exact | Safe | Provenance valid |","|---|---|---|---|---|---|"]
    for r in rows: lines.append(f"| {r['case']} | {r['expected']} | {r.get('actual',r.get('error','unknown'))} | {'yes' if r.get('exact_match') else 'no'} | {'yes' if r.get('passed') else 'no'} | {r.get('provenance_valid','n/a')} |")
    lines += ["","Labels were fixed before execution. The cases cover opinions, factual claims without evidence, location/date/number distinctions, allegations, attribution, death timing and partial support. A safe-outcome rate on this small synthetic development set is not production accuracy. Coverage is separate because an abstention should open Research deeper, never appear as verified. The latency is for concurrent laptop API calls against preloaded text; fresh retrieval remains outside the 1–2 second path.",""]
    (ROOT/"dist/social-information-benchmark.md").write_text("\n".join(lines))
    print(json.dumps({k:result[k] for k in ("count","safe_outcomes","exact_expected_matches","decisive_coverage","reported_cost_usd","unknown_charge_count","latency_seconds")}))
    if cost>MAX_COST_USD: raise SystemExit("reported cost exceeded authorized ceiling")
    raise SystemExit(0 if result["safe_outcomes"]==result["count"] else 1)

if __name__=="__main__": main()
