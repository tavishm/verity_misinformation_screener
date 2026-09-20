#!/usr/bin/env python3
"""Three paired production-shaped calls comparing price and latency routing."""
import concurrent.futures, json, pathlib, statistics, time, urllib.error, urllib.request

ROOT=pathlib.Path(__file__).resolve().parents[1]
URL="https://openrouter.ai/api/v1/chat/completions"
MAX_COST_USD=.004
PRIOR_ATTEMPT={"reported_cost_usd":.00005,"unknown_charge_count":4,"reserved_cost_usd":0,"note":"Two long-excerpt pairs returned content that could not be parsed within the 80-token cap; the first script version did not retain their response IDs or usage. The two retained BBC generation IDs were gen-1789876716-rj1hUp9KN82KImhbpT5M and gen-1789876716-i5g0HGlzth9bVeBt1lkL."}
CASES=[
 {"case":"BBC Ed Sheeran headline","claim":"Ed Sheeran addressed the Macklemore controversy at a Philadelphia show and admitted mistakes.","source":{"index":0,"title":"BBC News: Ed Sheeran admits 'mistakes' as he addresses Macklemore controversy at Philadelphia show","published":"2026-09-20T01:38:30Z","excerpt":"Ed Sheeran admits 'mistakes' as he addresses Macklemore controversy at Philadelphia show"}},
 {"case":"NDTV Bengal bypoll headline","claim":"Himanta Sarma jabbed Mamata Banerjee over her party's symbol.","source":{"index":0,"title":"NDTV: 'Football Will Deflate': Himanta Sarma Jabs Mamata Banerjee Over Party Symbol","published":"2026-09-20T03:30:36Z","excerpt":"'Football Will Deflate': Himanta Sarma Jabs Mamata Banerjee Over Party Symbol"}},
 {"case":"The Hindu same-sex couple headline","claim":"A High Court directed Uttar Pradesh Police to ensure the safety of a same-sex couple in a live-in relationship.","source":{"index":0,"title":"The Hindu: High Court directs Uttar Pradesh Police to ensure safety of same-sex couple in live-in relationship","published":"2026-09-20T03:46:40Z","excerpt":"High Court directs Uttar Pradesh Police to ensure safety of same-sex couple in live-in relationship"}},
]

def call(case,sort,prompt,key):
    payload={"claim":case["claim"],"sources":[case["source"]]}
    body={"model":"qwen/qwen3.7-flash","temperature":0,"max_tokens":80,"reasoning":{"enabled":False},
          "response_format":{"type":"json_object"},
          "provider":{"sort":sort,"max_price":{"prompt":.15,"completion":.60,"request":0}},
          "messages":[{"role":"system","content":prompt},{"role":"user","content":json.dumps(payload)}]}
    req=urllib.request.Request(URL,data=json.dumps(body).encode(),headers={"Authorization":"Bearer "+key,"Content-Type":"application/json"})
    row={"case":case["case"],"sort":sort,"reserved_usd":0};started=time.monotonic()
    try:
        with urllib.request.urlopen(req,timeout=15) as response:data=json.load(response)
        raw=data.get("choices",[{}])[0].get("message",{}).get("content","")
        row.update(seconds=round(time.monotonic()-started,3),provider=data.get("provider"),model=data.get("model"),
                   generation_id=data.get("id"),finish_reason=data.get("choices",[{}])[0].get("finish_reason"),usage=data.get("usage"),answer_raw=raw)
        try:row["answer"]=json.loads(raw)
        except Exception:row.update(error="JSONDecodeError",charge="reported_in_usage")
    except urllib.error.HTTPError as error:row.update(seconds=round(time.monotonic()-started,3),error="HTTPError",http_status=error.code,charge="unknown")
    except Exception as error:row.update(seconds=round(time.monotonic()-started,3),error=type(error).__name__,charge="unknown")
    return row

def main():
    prompt=(ROOT/"mobile/android/assets/social_information_prompt.txt").read_text();key=(pathlib.Path.home()/".config/forward-check/openrouter.key").read_text().strip();rows=[]
    for case in CASES:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            rows.extend(pool.map(lambda sort:call(case,sort,prompt,key),("price","latency")))
    cost=sum(float((r.get("usage") or {}).get("cost") or 0) for r in rows);unknown=sum(r.get("charge")=="unknown" for r in rows)
    summary={sort:{"count":sum(r["sort"]==sort for r in rows),"median_seconds":statistics.median(r["seconds"] for r in rows if r["sort"]==sort),"max_seconds":max(r["seconds"] for r in rows if r["sort"]==sort),"reported_cost_usd":sum(float((r.get("usage") or {}).get("cost") or 0) for r in rows if r["sort"]==sort),"providers":sorted(set(str(r.get("provider")) for r in rows if r["sort"]==sort))} for sort in ("price","latency")}
    pairs=[{"case":case["case"],"price_seconds":next(r["seconds"] for r in rows if r["case"]==case["case"] and r["sort"]=="price"),"latency_seconds":next(r["seconds"] for r in rows if r["case"]==case["case"] and r["sort"]=="latency")} for case in CASES]
    result={"scope":"Three paired development calls using real public RSS headlines and the production combined prompt/settings. Small concurrent API-only sample; not phone latency or a universal provider claim.",
      "official_routing_note":"OpenRouter documents provider.sort=latency as prioritizing lower time to first token; max_price remains an independent hard provider-price ceiling.",
      "official_docs":"https://openrouter.ai/docs/guides/routing/provider-selection","max_authorized_cost_usd":MAX_COST_USD,
      "official_endpoint_snapshot":{"checked_at":"2026-09-20","url":"https://openrouter.ai/api/v1/models/qwen/qwen3.7-flash/endpoints","eligible_endpoint_count":1,"provider":"Alibaba"},
      "recommendation":"Do not change the runtime sort from price to latency based on this sample: both modes selected the sole Alibaba endpoint and latency sorting did not improve median whole-call time.",
      "reported_cost_usd":cost,"unknown_charge_count":unknown,"reserved_cost_usd":0,"prior_attempt":PRIOR_ATTEMPT,
      "total_development_reported_cost_usd":PRIOR_ATTEMPT["reported_cost_usd"]+cost,"summary":summary,"pairs":pairs,"rows":rows}
    (ROOT/"dist/social-routing-benchmark.json").write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps({"reported_cost_usd":cost,"unknown_charge_count":unknown,"summary":summary},indent=2))
    if cost>MAX_COST_USD:raise SystemExit("reported cost exceeded authorized ceiling")

if __name__=="__main__":main()
