import json,numpy as np
from pathlib import Path
from train import features,LABELS,HERE
rows=json.loads((HERE/'challenge.json').read_text());X=features(rows)
head=json.loads((HERE/'head.json').read_text());logits=X@np.array(head['weights']).T+np.array(head['bias']);p=np.exp(logits-logits.max(axis=1,keepdims=True));p/=p.sum(axis=1,keepdims=True)
report=[]
for row,score in zip(rows,p):
 label='scam' if score[2]>=head['spam_threshold'] else 'personal' if score[0]>=head['personal_threshold'] else 'claim'
 report.append(dict(**row,predicted=label,probabilities=score.tolist()))
 if label!=row['label']:print(row['language'],row['label'],'->',label,repr(row['text']),np.round(score,3),flush=True)
for lang in ['en','hi','hinglish']:
 selected=[r for r in report if r['language']==lang];print(lang,'correct',sum(r['predicted']==r['label'] for r in selected),'/',len(selected))
(HERE/'challenge-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
