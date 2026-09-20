"""Select domain/language weighting on validation only; keep test results separate."""
import json,numpy as np
from sklearn.linear_model import LogisticRegression
from train import dataset,features,HERE,LABELS,SEED
rows=dataset();X=features(rows);y=np.array([LABELS.index(r['label']) for r in rows]);tr=np.array([r['split']=='train' for r in rows]);va=np.array([r['split']=='validation' for r in rows]);
groups={key:va&np.array([r['source']+':'+r['language']==key for r in rows]) for key in sorted({r['source']+':'+r['language'] for r in rows})}
best=None;reports=[]
for strength in [4,12,30]:
 weights=np.array([strength if r['source']=='synthetic_local_teacher' else 1 for r in rows])
 for C in [1,5,20]:
  model=LogisticRegression(C=C,class_weight='balanced',max_iter=2000,random_state=SEED).fit(X[tr],y[tr],sample_weight=weights[tr]);p=model.predict_proba(X)
  personal=.99
  for t in np.arange(.4,.991,.02):
   valid=True
   for mask in groups.values():
    facts=mask&(y==1)
    if facts.sum()>=8 and (p[facts,0]>=t).mean()>.06:valid=False
   if valid:personal=float(t);break
  spam=.995
  for t in np.arange(.45,.996,.01):
   pred=p[:,2]>=t;valid=True
   for mask in groups.values():
    if ((y==2)&mask).sum()<8:continue
    precision=(pred[mask]&(y[mask]==2)).sum()/max(1,pred[mask].sum())
    if precision<.95:valid=False
   if valid:spam=float(t);break
  pred=np.where(p[:,2]>=spam,2,np.where(p[:,0]>=personal,0,1));scores=[]
  for mask in groups.values():
   recalls=[(pred[mask&(y==k)]==k).mean() for k in range(3) if (mask&(y==k)).any()]
   if recalls:scores.append(float(np.mean(recalls)))
  value=float(np.mean(scores));row=dict(synthetic_weight=strength,C=C,personal_threshold=personal,spam_threshold=spam,validation_group_balanced_accuracy=value);reports.append(row);print(row,flush=True)
  if best is None or value>best[0]:best=(value,model,row)
_,model,choice=best;head=json.loads((HERE/'head.json').read_text());head.update(weights=model.coef_.tolist(),bias=model.intercept_.tolist(),personal_threshold=choice['personal_threshold'],spam_threshold=choice['spam_threshold'])
(HERE/'head.json').write_text(json.dumps(head,indent=2)+'\n');(HERE/'head-selection.json').write_text(json.dumps(dict(selection=choice,candidates=reports),indent=2)+'\n')
p=model.predict_proba(X);pred=np.where(p[:,2]>=head['spam_threshold'],2,np.where(p[:,0]>=head['personal_threshold'],0,1))
from sklearn.metrics import confusion_matrix
out={}
for key in groups:
 mask=np.array([r['split']=='test' and r['source']+':'+r['language']==key for r in rows]);out[key]=dict(rows=int(mask.sum()),confusion_matrix=confusion_matrix(y[mask],pred[mask],labels=[0,1,2]).tolist())
(HERE/'weighted-test-results.json').write_text(json.dumps(out,indent=2)+'\n');print('TEST',out,flush=True)
