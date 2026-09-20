#!/usr/bin/env python3
"""Export a quantized multilingual static-embedding check-worthiness gate."""
from __future__ import annotations
import json, struct, hashlib
from pathlib import Path
import numpy as np
from safetensors import safe_open
from tokenizers import Tokenizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score, precision_recall_fscore_support, roc_auc_score, average_precision_score

MODEL_ID='sentence-transformers/static-similarity-mrl-multilingual-v1'
REVISION='b68f4122911bcffcd6e1f695f2d99cd6788972d8'
MODEL=Path.home()/'.cache/huggingface/hub/models--sentence-transformers--static-similarity-mrl-multilingual-v1/snapshots'/REVISION/'0_StaticEmbedding'
DATA=Path('/tmp/forward-check-classifier')
MAX_TOKENS=256

def rows(name): return json.loads((DATA/name).read_text())
def token_ids(tok,text):
 ids=tok.encode(text[:5000]).ids
 return ids[:MAX_TOKENS-1]+[102] if len(ids)>MAX_TOKENS else ids

def embed(texts,tok,table,dim):
 out=np.empty((len(texts),dim),np.float32)
 for i,text in enumerate(texts):
  ids=token_ids(tok,text)
  out[i]=table[ids,:dim].mean(0)
 return out

def report(y,p,t):
 q=p>=t; pr,re,f,_=precision_recall_fscore_support(y,q,average='binary',zero_division=0)
 return {'threshold':round(float(t),3),'accuracy':round(float(accuracy_score(y,q)),6),'precision':round(float(pr),6),'recall':round(float(re),6),'f1':round(float(f),6),'roc_auc':round(float(roc_auc_score(y,p)),6),'average_precision':round(float(average_precision_score(y,p)),6)}

def main():
 tok=Tokenizer.from_file(str(MODEL/'tokenizer.json'))
 with safe_open(MODEL/'model.safetensors',framework='numpy') as f: table=f.get_tensor('embedding.weight')
 train,test=rows('train.json'),rows('test.json'); test_norm={x['text'].strip().lower() for x in test}
 train=[x for x in train if x['text'].strip().lower() not in test_norm]
 fit,val=train_test_split(train,test_size=.2,random_state=1729,stratify=[x['label'] for x in train])
 results={}; models={}
 for dim in (64,128):
  xf=embed([x['text'] for x in fit],tok,table,dim); xv=embed([x['text'] for x in val],tok,table,dim)
  yf=np.array([x['label'] for x in fit]); yv=np.array([x['label'] for x in val])
  m=LogisticRegression(C=1,class_weight='balanced',max_iter=500,random_state=1729).fit(xf,yf)
  pv=m.predict_proba(xv)[:,1]
  choices=[(report(yv,pv,t)['f1'],t) for t in np.arange(.3,.701,.025)]
  threshold=max(choices)[1]; results[str(dim)]=report(yv,pv,threshold); models[dim]=(m,threshold)
 print('validation',json.dumps(results,indent=2))
 # Choose higher validation F1; tie favors 64.
 dim=max((results[str(d)]['f1'],-d,d) for d in (64,128))[2]
 # Refit on full official training partition at chosen dimension; evaluate test once.
 x=embed([z['text'] for z in train],tok,table,dim); y=np.array([z['label'] for z in train])
 xt=embed([z['text'] for z in test],tok,table,dim); yt=np.array([z['label'] for z in test])
 m=LogisticRegression(C=1,class_weight='balanced',max_iter=500,random_state=1729).fit(x,y)
 threshold=models[dim][1]
 float_metrics=report(yt,m.predict_proba(xt)[:,1],threshold)
 # Symmetric per-dimension int8 quantization of the original pretrained token table.
 source=table[:,:dim]; scales=np.max(np.abs(source),axis=0)/127; quant=np.rint(source/scales).clip(-127,127).astype(np.int8)
 quantized=quant.astype(np.float32)*scales
 quant_metrics=report(yt,m.predict_proba(embed([z['text'] for z in test],tok,quantized,dim))[:,1],threshold)
 base=Path(__file__).parent; binary=base/'forward_embedding.bin'; vocab=base/'forward_embedding_vocab.txt'
 with binary.open('wb') as f:
  f.write(b'FCEM'); f.write(struct.pack('<III',1,quant.shape[0],dim)); f.write(scales.astype('<f4').tobytes()); f.write(quant.tobytes())
 projection=base/'forward_embedding_projection.bin'
 token_scores=(quant.astype(np.float32)*scales)@m.coef_[0].astype(np.float32)
 with projection.open('wb') as f:
  f.write(b'FCSP'); f.write(struct.pack('<II',1,quant.shape[0])); f.write(token_scores.astype('<f4').tobytes())
 model_vocab=tok.get_vocab(); ordered=['']*len(model_vocab)
 for token,idx in model_vocab.items(): ordered[idx]=token
 vocab.write_text('\n'.join(ordered)+'\n')
 artifact={'format':'fairc-forward-static-embedding-v1','primary_model':'multilingual_static_embedding','model_id':MODEL_ID,'revision':REVISION,'license':'Apache-2.0','dimensions':dim,'vocab_size':len(ordered),'max_input_codepoints':5000,'max_tokens':MAX_TOKENS,'pooling':'mean of WordPiece token embeddings including CLS and SEP','quantization':'symmetric int8 per embedding dimension','compiled_inference':'The learned linear head is algebraically fused into one float32 score per pretrained token; sigmoid(intercept + mean(token_scores)).','intercept':float(m.intercept_[0]),'weights':[float(v) for v in m.coef_[0]],'thresholds':{'factual_offer':threshold,'uncertain_offer':max(.2,threshold-.1)},'training':{'dataset':'ClaimBuster Spotter two_class official train/test split','classifier_license':'GPL-3.0','selection':'64 versus 128 and threshold selected on 20% stratified training validation only; official test evaluated once afterward','validation':results,'float_heldout_test':float_metrics,'heldout_test':quant_metrics,'limitations':['Check-worthiness gate, never truth','Classifier labels are English U.S. political debate; multilingual embeddings do not make this WhatsApp- or Hindi-validated']},'assets':{'embedding_reference_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'projection_sha256':hashlib.sha256(projection.read_bytes()).hexdigest(),'vocab_sha256':hashlib.sha256(vocab.read_bytes()).hexdigest()}}
 (base/'forward_classifier.json').write_text(json.dumps(artifact,ensure_ascii=False,separators=(',',':'))+'\n')
 print(json.dumps({'chosen_dim':dim,'quantized_test':quant_metrics,'reference_embedding_bytes':binary.stat().st_size,'projection_bytes':projection.stat().st_size,'vocab_bytes':vocab.stat().st_size},indent=2))
if __name__=='__main__': main()
