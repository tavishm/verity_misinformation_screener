"""Exercise actual local verification; synthetic development cases, never live coverage."""
import argparse
import json
import time
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--limit',type=int,default=12)
    args=parser.parse_args()
    token=(ROOT/'demo/data/local-token').read_text().strip()
    def api(path,payload=None):
        req=urllib.request.Request('http://127.0.0.1:8870'+path,
            data=json.dumps(payload).encode() if payload is not None else None,
            headers={'X-Factcheck-Token':token,'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=30) as response: return json.load(response)
    records=json.loads((ROOT/'experiments/politics/development_inputs.json').read_text())['examples'][:max(1,min(12,args.limit))]
    results=[];session='politics-development-'+str(int(time.time()))
    for row in records:
        payload={'text':row['input'],'post':{'platform':'demo','post_id':row['id'],'relation':'original'},
                 'scope':'development','session_id':session,'observation_id':row['id']}
        started=time.perf_counter();submitted=api('/api/check',payload)
        deadline=time.monotonic()+240
        while True:
            result=api('/api/jobs/'+submitted['job_id'])
            if result['status']!='pending' or time.monotonic()>deadline: break
            time.sleep(.4)
        result.update(input=row,request=payload,wall_seconds=round(time.perf_counter()-started,3),cached=submitted['cached'])
        results.append(result)
        compact={'id':row['id'],'status':result['status'],'seconds':result['wall_seconds'],'cached':submitted['cached']}
        if result.get('result'):
            compact.update(label=result['result']['label'],route=result['result']['reuse']['kind'])
        print(json.dumps(compact),flush=True)
        report={'dataset_kind':'synthetic source-derived development inputs, not social posts',
                'coverage_claim':False,'paid_api_spend':0,'session_id':session,'results':results}
        (ROOT/'experiments/politics/development-run.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')

if __name__=='__main__':main()
