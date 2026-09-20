#!/usr/bin/env python3
"""Paid, bounded smoke check of the opinion gate; synthetic text only."""
import concurrent.futures, json, pathlib, time, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
CASES = [
    ("I think every AI lab wants to scare us into regulation.", "skip"),
    ("For every upvote I will read 100 pages and run 10 miles.", "skip"),
    ("Which ad cliché drives you crazy?", "skip"),
    ("I died waiting for this update lol.", "skip"),
    ("I love Reddit; X is terrible.", "skip"),
    ("This politician is the worst.", "skip"),
    ("MFW my code works on the first try.", "skip"),
    ("Happy Diwali! I'm proud of my children.", "skip"),
    ("मुझे यह नेता बिल्कुल पसंद नहीं है।", "skip"),
    ("I think Trump died this morning.", "claim"),
    ("I hate this policy: 10,000 people were arrested yesterday.", "claim"),
    ("In my opinion vaccines contain microchips.", "claim"),
    ("Drinking hot water changes sexual orientation.", "claim"),
    ("The Earth goes around the Sun.", "claim"),
    ("The Earth does not go around the Sun.", "claim"),
    ("गर्म पानी पीने से इंसान की यौन अभिरुचि बदल जाती है।", "claim"),
]

def run(case):
    text, expected = case
    key = (pathlib.Path.home()/'.config/forward-check/openrouter.key').read_text().strip()
    prompt = (ROOT/'mobile/android/assets/social_quick_prompt.txt').read_text()
    body = {'model':'qwen/qwen3.7-flash','temperature':0,'max_tokens':80,
            'reasoning':{'enabled':False},'response_format':{'type':'json_object'},
            'provider':{'sort':'price','max_price':{'prompt':.15,'completion':.60,'request':0}},
            'messages':[{'role':'system','content':prompt},{'role':'user','content':text}]}
    req = urllib.request.Request('https://openrouter.ai/api/v1/chat/completions',
        data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+key,'Content-Type':'application/json'})
    row={'text':text,'expected':expected}; start=time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=15) as resp: data=json.load(resp)
        answer=json.loads(data['choices'][0]['message']['content'])
        actual='skip' if answer.get('v')=='skip' else 'claim' if answer.get('v') in ['true','false','research'] else 'invalid'
        row.update(actual=actual,passed=actual==expected,answer=answer,usage=data.get('usage'),generation_id=data.get('id'))
    except Exception as e: row.update(error=type(e).__name__,passed=False)
    row['seconds']=round(time.monotonic()-start,3)
    print(json.dumps(row),flush=True)
    return row

if __name__=='__main__':
    with concurrent.futures.ThreadPoolExecutor(2) as pool: rows=list(pool.map(run,CASES))
    total=sum((r.get('usage') or {}).get('cost',0) or 0 for r in rows)
    result={'warning':'Small synthetic gate regression check, not an accuracy or truth-verification benchmark.',
            'passed':sum(r['passed'] for r in rows),'count':len(rows),'reported_cost_usd':total,'rows':rows}
    (ROOT/'dist/social-gate-smoke.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='rows'}))
