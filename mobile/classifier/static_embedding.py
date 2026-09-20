"""Reference scorer for the quantized multilingual static-embedding asset."""
from __future__ import annotations
import json, math, struct
from pathlib import Path
import numpy as np
from tokenizers import Tokenizer

class StaticEmbeddingGate:
 def __init__(self, head, binary, tokenizer):
  self.head=json.loads(Path(head).read_text()) if not isinstance(head,dict) else head
  raw=Path(binary).read_bytes()
  if raw[:4]!=b'FCEM': raise ValueError('bad embedding magic')
  version,vocab,dim=struct.unpack_from('<III',raw,4)
  if version!=1 or vocab!=self.head['vocab_size'] or dim!=self.head['dimensions']: raise ValueError('embedding metadata mismatch')
  offset=16; self.scales=np.frombuffer(raw,dtype='<f4',count=dim,offset=offset)
  self.table=np.frombuffer(raw,dtype=np.int8,count=vocab*dim,offset=offset+dim*4).reshape(vocab,dim)
  self.tokenizer=Tokenizer.from_file(str(tokenizer)); self.weights=np.asarray(self.head['weights'])
 def ids(self,text):
  ids=self.tokenizer.encode((text or '')[:self.head['max_input_codepoints']]).ids
  if len(ids)>self.head['max_tokens']: ids=ids[:self.head['max_tokens']-1]+[102]
  return ids
 def embedding(self,text):
  ids=self.ids(text); return (self.table[ids].astype(np.float32)*self.scales).mean(0)
 def score(self,text):
  z=self.head['intercept']+float(self.embedding(text)@self.weights)
  return 1/(1+math.exp(-max(-35,min(35,z))))
