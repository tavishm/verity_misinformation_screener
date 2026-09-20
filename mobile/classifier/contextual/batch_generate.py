"""Batched local teacher augmentation. Run on an available owner-approved GPU."""
import os,json,hashlib,time
from pathlib import Path
import torch
from transformers import AutoTokenizer,AutoModelForCausalLM
from generate_training import SYSTEM,TOPICS
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'synthetic-training.jsonl'
MODEL=os.environ.get('FORWARD_TEACHER_MODEL')
if not MODEL:
 raise RuntimeError('Set FORWARD_TEACHER_MODEL to a local teacher-model directory before generating data.')
def main():
 done={json.loads(x)['batch'] for x in OUT.read_text().splitlines() if x} if OUT.exists() else set()
 tasks=[(label,topic,batch) for label,topics in TOPICS.items() for topic in topics for batch in range(2) if f'{label}:{topic}:{batch}' not in done]
 tokenizer=AutoTokenizer.from_pretrained(MODEL,local_files_only=True,padding_side='left')
 model=AutoModelForCausalLM.from_pretrained(MODEL,local_files_only=True,dtype=torch.bfloat16,attn_implementation='sdpa',use_safetensors=True).to('cuda:0').eval()
 for start in range(0,len(tasks),8):
  chunk=tasks[start:start+8];prompts=[]
  for label,topic,batch in chunk:
   messages=[{'role':'system','content':SYSTEM},{'role':'user','content':f'Produce 6 varied examples of label {label}, topic {topic}. Batch variant {batch}. Each example must include the three translations. Use colloquial language and polite wording; each message under 30 words.'}]
   prompts.append(tokenizer.apply_chat_template(messages,tokenize=False,add_generation_prompt=True,enable_thinking=False))
  inputs=tokenizer(prompts,padding=True,return_tensors='pt').to(model.device);torch.manual_seed(20260920+start);t=time.time()
  with torch.inference_mode():outputs=model.generate(**inputs,max_new_tokens=2048,do_sample=True,temperature=.7,top_p=.8,top_k=20,pad_token_id=tokenizer.pad_token_id,eos_token_id=tokenizer.eos_token_id)
  for task,output in zip(chunk,outputs):
   label,topic,batch=task;key=f'{label}:{topic}:{batch}';raw=tokenizer.decode(output[inputs.input_ids.shape[1]:],skip_special_tokens=True).split('</think>')[-1].strip()
   try:
    rows=json.loads(raw[raw.find('['):raw.rfind(']')+1]);clean=[]
    for i,row in enumerate(rows):
     if not isinstance(row,dict) or any(not isinstance(row.get(lang),str) or not 3<len(row[lang])<1000 for lang in ['en','hi','hinglish']):continue
     if not any('\u0900'<=c<='\u097f' for c in row['hi']):continue
     group=hashlib.sha256((key+':'+str(i)).encode()).hexdigest()[:20]
     for lang in ['en','hi','hinglish']:clean.append(dict(group=group,batch=key,language=lang,label=label,text=row[lang],source='synthetic-local-Qwen3-4B',human_verified=False))
    with OUT.open('a') as f:
     for row in clean:f.write(json.dumps(row,ensure_ascii=False)+'\n')
    print(key,len(clean),flush=True)
   except Exception as e:print('FAILED',key,type(e).__name__,flush=True)
  print('batch seconds',round(time.time()-t,1),'completed',min(start+8,len(tasks)),'/',len(tasks),flush=True)
if __name__=='__main__':main()
