"""Fit and evaluate a contextual phone gate. Public and synthetic data are reported separately.

Uses the actual ARM int8 encoder, including dense+tanh pooling; Android reproduces this.
No test data chooses weights, hyperparameters or thresholds. Translations share a split.
"""
from pathlib import Path
import hashlib,json,re,zipfile,os,time,unicodedata
os.environ.setdefault('OMP_NUM_THREADS','4')
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
from safetensors.numpy import load_file
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report,confusion_matrix
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
CACHE=ROOT/'.cache/context-gate';LABELS=['personal','claim','scam'];SEED=20260920

def normalized(t):return re.sub(r'\s+',' ',unicodedata.normalize('NFKC',t)).strip().casefold()
def dataset():
 rows=[];cb=CACHE/'claimbuster'
 if not cb.exists():cb=Path('/tmp/forward-check-classifier')
 official=json.loads((cb/'test.json').read_text());excluded={normalized(r['text']) for r in official}
 train=[r for r in json.loads((cb/'train.json').read_text()) if normalized(r['text']) not in excluded]
 indices=np.arange(len(train));a,b=train_test_split(indices,test_size=.2,stratify=[r['label'] for r in train],random_state=SEED);val=set(b)
 # ClaimBuster's negative class includes real, low-priority public facts.
 # It is NOT a personal-message label. Use its positive class only for training
 # this different task; retain the published test set for transparent reporting.
 for i,r in enumerate(train):
  if int(r['label']):rows.append(dict(text=r['text'],label='claim',split='validation' if i in val else 'train',source='claimbuster',language='en'))
 for r in official:rows.append(dict(text=r['text'],label='claim' if int(r['label']) else 'personal',split='test',source='claimbuster',language='en'))
 sms={}
 raw=zipfile.ZipFile(ROOT/'.cache/spam-training/uci-sms-spam.zip').read('SMSSpamCollection').decode()
 for line in raw.splitlines():
  if '\t' not in line:continue
  label,t=line.split('\t',1);sms[normalized(t)]=(t,label=='spam')
 texts=list(sms.values());indices=np.arange(len(texts));y=[int(r[1]) for r in texts]
 a,rest=train_test_split(indices,test_size=.3,stratify=y,random_state=SEED);b,c=train_test_split(rest,test_size=.5,stratify=np.array(y)[rest],random_state=SEED);train_set=set(a);val=set(b)
 for i,(t,spam) in enumerate(texts):rows.append(dict(text=t,label='scam' if spam else 'personal',split='train' if i in train_set else 'validation' if i in val else 'test',source='uci_sms',language='en'))
 synthetic=HERE/'revised-synthetic.jsonl'
 if not synthetic.exists():synthetic=HERE/'synthetic-training.jsonl'
 if synthetic.exists():
  for line in synthetic.read_text().splitlines():
   r=json.loads(line);v=int(hashlib.sha256(r['group'].encode()).hexdigest()[:8],16)%10
   rows.append(dict(text=r['text'],label=r['label'],split='test' if v==0 else 'validation' if v==1 else 'train',source='synthetic_deepseek' if synthetic.name.startswith('revised') else 'synthetic_local_teacher',language=r['language'],group=r['group'],topic=r['batch']))
 # Teach compositional routing: a friendly opening does not erase a claim.
 # Both parent examples must be training-only, including their translations.
 mixed=[]
 for lang in ['en','hi','hinglish']:
  eligible=[r for r in rows if r['split']=='train' and r['language']==lang and r['source']=='synthetic_deepseek']
  greetings=[r for r in eligible if r['label']=='personal' and any(x in r['topic'] for x in ['greetings','wishes','blessings','affection'])]
  facts=[r for r in eligible if r['label']=='claim']
  for i,fact in enumerate(facts):
   if not greetings:continue
   greeting=greetings[i%len(greetings)]
   for suffix in [bool(i%2)]:
    text=(fact['text']+' '+greeting['text']) if suffix else (greeting['text']+' '+fact['text'])
    mixed.append(dict(text=text,label='claim',split='train',source='synthetic_deepseek',language=lang,group='mixed:'+fact['group']+':'+greeting['group']+':'+str(suffix),augmentation='training_only_composition'))
 rows.extend(mixed)
 # Prevent exact text contamination even across public sources and augmentation.
 priority={'test':0,'validation':1,'train':2};seen={};clean=[]
 for r in sorted(rows,key=lambda r:priority[r['split']]):
  key=normalized(r['text'])
  if key not in seen:seen[key]=r;clean.append(r)
 return clean

def features(rows):
 options=ort.SessionOptions();options.intra_op_num_threads=4;options.inter_op_num_threads=1
 session=ort.InferenceSession(str(CACHE/'encoder-int8.onnx'),options,providers=['CPUExecutionProvider'])
 tokenizer=Tokenizer.from_file(str(CACHE/'tokenizer.json'));tokenizer.enable_truncation(max_length=128);tokenizer.enable_padding(pad_id=0,pad_token='[PAD]')
 dense=load_file(str(CACHE/'2_Dense/model.safetensors'))
 path=CACHE/'feature-cache.npz';stored=dict(np.load(path)) if path.exists() else {};keys=[hashlib.sha256(r['text'].encode()).hexdigest() for r in rows]
 missing=[i for i,k in enumerate(keys) if k not in stored];missing.sort(key=lambda i:len(rows[i]['text']))
 start=time.time()
 for offset in range(0,len(missing),32):
  selected=missing[offset:offset+32];enc=tokenizer.encode_batch([rows[i]['text'] for i in selected]);mask=np.array([e.attention_mask for e in enc],dtype=np.int64)
  inputs={'input_ids':np.array([e.ids for e in enc],dtype=np.int64),'attention_mask':mask}
  hidden=session.run(None,inputs)[0];pooled=(hidden*mask[:,:,None]).sum(1)/mask.sum(1)[:,None]
  z=np.tanh(pooled@dense['linear.weight'].T+dense['linear.bias']).astype(np.float32);z/=np.maximum(np.linalg.norm(z,axis=1,keepdims=True),1e-12)
  for i,v in zip(selected,z):stored[keys[i]]=v
  if offset%512==0:print('embedded',offset,'/',len(missing),'seconds',round(time.time()-start),flush=True)
 np.savez_compressed(path,**stored)
 return np.stack([stored[k] for k in keys])

def main():
 rows=dataset();print('dataset',len(rows),flush=True);X=features(rows);y=np.array([LABELS.index(r['label']) for r in rows]);split=np.array([r['split'] for r in rows]);tr=split=='train';va=split=='validation';te=split=='test'
 model=LogisticRegression(C=5,class_weight='balanced',max_iter=1500,random_state=SEED).fit(X[tr],y[tr]);prob=model.predict_proba(X)
 # High-confidence personal messages may be skipped; uncertainty is offered for review.
 choices=[]
 for threshold in np.arange(.5,.991,.02):
  skip=prob[:,0]>=threshold;claim=va&(y==1);personal=va&(y==0)
  missed=float(skip[claim].mean()) if claim.any() else 1
  if missed<=.03:choices.append((float(skip[personal].mean()),float(threshold)))
 personal_threshold=max(choices)[1] if choices else .99
 spam_threshold=.995
 for threshold in np.arange(.6,.996,.01):
  pred=prob[va,2]>=threshold;actual=y[va]==2
  precision=(pred&actual).sum()/max(1,pred.sum())
  if precision>=.98 and pred.sum()>=15:spam_threshold=float(threshold);break
 def metrics(mask):
  p=prob[mask];actual=y[mask];pred=np.where(p[:,2]>=spam_threshold,2,np.where(p[:,0]>=personal_threshold,0,1))
  return {'rows':int(mask.sum()),'confusion_matrix':confusion_matrix(actual,pred,labels=[0,1,2]).tolist(),'report':classification_report(actual,pred,labels=[0,1,2],target_names=LABELS,output_dict=True,zero_division=0)}
 report={'model':'sentence-transformers/distiluse-base-multilingual-cased-v2','revision':'bfe45d0732ca50787611c0fe107ba278c7f3f889','seed':SEED,'train_rows':int(tr.sum()),'validation_rows':int(va.sum()),'test_rows':int(te.sum()),'personal_threshold':personal_threshold,'spam_threshold':spam_threshold,'validation':metrics(va),'test_groups':{},'limitations':['Synthetic Hindi and Romanized Hindi data is NOT a real WhatsApp benchmark.','Task labels from English debate and old SMS data do not establish senior-user deployment accuracy.','Personal messages may contain private facts that are not public fact-check targets.','Model probability is a routing score, never truth or scam certainty.']}
 for source in sorted({r['source'] for r in rows}):
  for lang in sorted({r['language'] for r in rows if r['source']==source}):
   mask=te&np.array([r['source']==source and r['language']==lang for r in rows]);
   if mask.any():report['test_groups'][source+':'+lang]=metrics(mask)
 head={'version':1,'labels':LABELS,'dimensions':512,'max_tokens':128,'weights':model.coef_.tolist(),'bias':model.intercept_.tolist(),'personal_threshold':personal_threshold,'spam_threshold':spam_threshold,'encoder_sha256':hashlib.sha256((CACHE/'encoder-int8.onnx').read_bytes()).hexdigest()}
 (HERE/'head.json').write_text(json.dumps(head,indent=2)+'\n');(HERE/'evaluation.json').write_text(json.dumps(report,indent=2)+'\n')
 (HERE/'split-manifest.jsonl').write_text(''.join(json.dumps({**{k:v for k,v in r.items() if k!='text'},'text_sha256':hashlib.sha256(r['text'].encode()).hexdigest()})+'\n' for r in rows))
 print(json.dumps(report,indent=2),flush=True)
if __name__=='__main__':main()
