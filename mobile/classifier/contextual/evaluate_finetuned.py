"""Development diagnostics only; these examples do not select training checkpoints."""
import json
import numpy as np
import torch
from finetune import RoutingModel, prepare, probabilities
from train import HERE,CACHE,LABELS

torch.set_num_threads(4)
checkpoint=torch.load(CACHE/'finetuned.pt',map_location='cpu',weights_only=True)
model=RoutingModel().cuda().eval();model.load_state_dict(checkpoint['state_dict'],strict=False)
rows=json.loads((HERE/'challenge.json').read_text());p=probabilities(model,prepare(rows),list(range(len(rows))),'cuda')
selected=checkpoint['selection'];report=[]
for row,score in zip(rows,p):
 label='scam' if score[2]>=selected['spam_threshold'] else 'personal' if score[0]>=selected['personal_threshold'] else 'claim'
 report.append(dict(**row,predicted=label,probabilities=score.tolist()))
 if label!=row['label']:print(row['language'],row['label'],'->',label,repr(row['text']),np.round(score,3),flush=True)
for lang in ['en','hi','hinglish']:
 items=[r for r in report if r['language']==lang];print(lang,sum(r['label']==r['predicted'] for r in items),'/',len(items),flush=True)
(HERE/'fp32-development.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
