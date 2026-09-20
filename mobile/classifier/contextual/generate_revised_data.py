"""Generate synthetic bilingual routing data with an explicit $0.60 budget.

Only invented examples are sent, never phone messages. Each paid request is
reserved in a persistent ledger before it starts; unknown costs keep that hold.
Quality filters reject copied English labelled Hinglish and wrong-script Hindi.
This remains synthetic data, not a human-labelled WhatsApp evaluation.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import re
import threading
import time
import urllib.request
import uuid

from generate_training import TOPICS

HERE=Path(__file__).resolve().parent
OUTPUT=HERE/'revised-synthetic.jsonl'
LEDGER=HERE/'generation-costs.jsonl'
MODEL='deepseek/deepseek-v4.1-flash'
RESERVE=.015
BUDGET=.60
LOCK=threading.Lock()
SYSTEM='''Create invented WhatsApp message examples for an on-device SCREENING model. This is not truth labelling. Return JSON {"examples":[{"en":"...","hi":"...","hinglish":"..."}]}.
Labels: personal = a greeting, affection, private family update/plan, personal opinion, or advice NOT to give codes/passwords to strangers. These do not require public fact checking. claim = public factual assertion that could be checked, whether true or false; a claim still counts if it starts with a greeting or opinion. scam = an unsolicited attempt to extract credentials or payment, a phishing threat, or an implausible guaranteed-money pitch. Ordinary personal requests and warnings AGAINST scams are not scams. Never include a false public factual assertion in a personal example.
Give exactly 12 diverse examples with each in three languages. Hindi must be fluent, idiomatic Devanagari, written like an ordinary Indian family member. Hinglish must be natural ROMANIZED HINDI (Hindi words using Latin letters, such as 'aaj mujhe', 'kripya', 'mat dena', 'tumhara', 'hai'), not a copy of English. All three versions must express the same meaning and have the requested label. Vary structure, length and politeness; no mechanically repeated opening. Some short messages, some two-clause messages. Each version at most 35 words. Use only fictional names, no real credentials or phone numbers, and only example.org URLs. Do not answer the messages or add analysis.'''

def append(path,row):
    with path.open('a') as output:
        output.write(json.dumps(row,ensure_ascii=False)+'\n');output.flush()

def reserve(topic):
    with LOCK:
        latest={}
        if LEDGER.exists():
            for line in LEDGER.read_text().splitlines():
                row=json.loads(line);latest[row['request_id']]=row
        total=sum(row.get('cost_usd',RESERVE) for row in latest.values())
        if total+RESERVE>BUDGET:raise RuntimeError('Synthetic data budget reached')
        row=dict(request_id=str(uuid.uuid4()),time_ms=int(time.time()*1000),model=MODEL,state='reserved',reserved_usd=RESERVE,topic=topic)
        append(LEDGER,row)
        return row

def generate(task):
    label,topic=task;key=label+':'+topic
    receipt=reserve(key)
    token=(Path.home()/'.config/forward-check/openrouter.key').read_text().strip()
    body=dict(model=MODEL,reasoning={'enabled':False},temperature=.7,max_tokens=4200,
              response_format={'type':'json_object'},provider={'sort':'latency','max_price':{'prompt':.5,'completion':2.0}},
              messages=[{'role':'system','content':SYSTEM},{'role':'user','content':f'Label: {label}. Topic: {topic}. Produce 12 parallel examples.'}])
    request=urllib.request.Request('https://openrouter.ai/api/v1/chat/completions',data=json.dumps(body).encode(),
        headers={'Content-Type':'application/json','Authorization':'Bearer '+token,'X-Title':'Forward Check synthetic routing data'})
    try:
        with urllib.request.urlopen(request,timeout=90) as response:answer=json.load(response)
        usage=answer.get('usage',{});receipt.update(generation_id=answer.get('id'),usage=usage,state='charged' if usage.get('cost') is not None else 'cost_unknown')
        if usage.get('cost') is not None:receipt['cost_usd']=usage['cost']
        with LOCK:append(LEDGER,receipt)
        choice=answer['choices'][0]
        if choice.get('finish_reason')=='length':raise ValueError('Truncated generation')
        examples=json.loads(choice['message']['content'])['examples'];valid=[]
        for index,example in enumerate(examples):
            if any(not isinstance(example.get(lang),str) or not 3<len(example[lang])<800 for lang in ['en','hi','hinglish']):continue
            if sum('\u0900'<=c<='\u097f' for c in example['hi'])<len(example['hi'])*.25:continue
            if re.sub(r'\W','',example['hinglish'].casefold())==re.sub(r'\W','',example['en'].casefold()):continue
            if not re.search(r'\b(hai|hain|ho|ka|ki|ke|ko|se|mein|main|aap|tum|mujhe|mera|meri|apna|apni|apne|karo|karna|mat|nahi|nahin|aur|liye|tha|thi|par|aaj|kal|kya|hum|yeh|ye|woh|wo)\b',example['hinglish'],re.I):continue
            group=hashlib.sha256(('revised:'+key+':'+str(index)).encode()).hexdigest()[:20]
            for language in ['en','hi','hinglish']:
                valid.append(dict(text=example[language],label=label,language=language,group=group,batch=key,source='synthetic-deepseek-v4.1-flash',human_verified=False))
        with LOCK:
            for row in valid:append(OUTPUT,row)
        print(key,'accepted_rows',len(valid),'cost',receipt.get('cost_usd','unknown'),flush=True)
    except Exception as error:
        # Do not retry automatically: the provider may already have charged.
        print(key,'failed',type(error).__name__,flush=True)

if __name__=='__main__':
    existing={json.loads(line)['batch'] for line in OUTPUT.read_text().splitlines()} if OUTPUT.exists() else set()
    tasks=[(label,topic) for label,topics in TOPICS.items() for topic in topics if label+':'+topic not in existing]
    with ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(generate,tasks))
