"""Disclosed synthetic replay of original, native repost, formatting and wrapper."""
import json,time,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
token=(ROOT/'demo/data/local-token').read_text().strip()
def api(path,body=None):
 r=urllib.request.Request('http://127.0.0.1:8870'+path,data=json.dumps(body).encode() if body is not None else None,headers={'X-Factcheck-Token':token,'Content-Type':'application/json'})
 with urllib.request.urlopen(r,timeout=30) as response:return json.load(response)
def main():
 text='The State Department amended ITAR to remove certain uncrewed underwater vehicles from the U.S. Munitions List.'
 base={'platform':'demo','relation':'original','post_id':'hierarchy-original'}
 cases=[('original',text,base),('native_repost',text,{**base,'post_id':'hierarchy-repost','original_post_id':'hierarchy-original','relation':'native_repost'}),
        ('same_assertion_formatting',text.replace('State Department','State  Department'),{**base,'post_id':'hierarchy-formatting'}),
        ('opinion_wrapper','I think this policy is terrible.',{**base,'post_id':'hierarchy-quote','relation':'quote','quoted_text':text,'quoted_post_id':'hierarchy-original','has_commentary':True})]
 session='hierarchy-development-'+str(int(time.time()));rows=[]
 for name,claim,post in cases:
  body={'text':claim,'post':post,'scope':'development','session_id':session,'observation_id':name};start=time.perf_counter()
  submitted=api('/api/check',body);deadline=time.monotonic()+180
  while True:
   job=api('/api/jobs/'+submitted['job_id'])
   if job['status']!='pending' or time.monotonic()>deadline:break
   time.sleep(.2)
  elapsed=time.perf_counter()-start;r=job.get('result',{})
  rows.append({'case':name,'request':body,'wall_seconds':round(elapsed,4),'job':job})
  print(json.dumps({'case':name,'status':job['status'],'seconds':round(elapsed,4),'label':r.get('label'),'route':r.get('reuse',{}).get('kind'),'generation_calls':len(r.get('model_usage',[]))}),flush=True)
  if name=='native_repost':assert r.get('reuse',{}).get('kind')=='unchanged_repost' and not r.get('model_usage')
  if name=='same_assertion_formatting':assert r.get('reuse',{}).get('kind') in ('claim_reuse','exact_reuse') and not r.get('model_usage')
  if name=='opinion_wrapper':assert not r.get('whole_post_validated') and r.get('original_assessment')
 (ROOT/'experiments/politics/hierarchy-replay.json').write_text(json.dumps({'kind':'synthetic development replay, not live feed coverage','results':rows},indent=2)+'\n')
if __name__=='__main__':main()
