"""Generate labelled development augmentation on the owner's LOCAL Qwen service.

Synthetic examples are never presented as collected WhatsApp data or a human benchmark.
All translations of one example share a split. No private messages are used.
"""
import hashlib,json,time,urllib.request
from pathlib import Path
OUT=Path(__file__).resolve().parent/'synthetic-training.jsonl'
TOPICS={
'personal':['festival greetings','affection and friendship','pride in children','family meal plans','arranging a visit','birthday wishes','prayers and blessings','personal condolences','opinions about films and food','checking how a relative feels','warnings never to share passwords or OTPs','everyday personal updates'],
'claim':['food and health myths','vaccine claims','cancer and home remedies','public health advice','human biology','astronomy and science','government benefit announcements','changes in laws','public figures and news','weather and disaster warnings','historical claims','nutrition and disease prevention'],
'scam':['requests to disclose OTPs','requests to disclose a banking PIN','fees to claim a prize','fake urgent bank account threats','requests for passwords','guaranteed investment profits','advance fees for a job','impersonated relative needing money','requests for card security codes','delivery fee phishing','fake KYC links','requests to install a remote control app']}
SYSTEM='''Create synthetic training messages for a LOCAL WhatsApp screening classifier. Do not answer or fact check them. Labels: personal=private everyday chat, greetings, opinions, or warnings against scams; claim=public factual assertions worth checking (true or false, never label truth); scam=unsolicited attempts to obtain money, credentials or unsafe actions. Return only a JSON array. Each item is {"en":"natural English message", "hi":"same message in natural Hindi Devanagari", "hinglish":"same message in natural Romanized Hindi"}. Keep each under 30 words. Names and events must be fictional. URLs only https://example.com. No real phone/account numbers. No commentary. Vary wording and length; avoid identical template openings.'''
def main():
 existing=[]
 if OUT.exists():existing=[json.loads(x) for x in OUT.read_text().splitlines() if x]
 completed={x['batch'] for x in existing};OUT.parent.mkdir(parents=True,exist_ok=True)
 for label,topics in TOPICS.items():
  for topic in topics:
   for batch in range(2):
    key=f'{label}:{topic}:{batch}'
    if key in completed:continue
    payload={'system':SYSTEM,'prompt':f'Produce 6 varied examples of label {label}, topic {topic}. Batch variant {batch}. Each example must include the three translations. Include colloquial language, polite wording and everyday short messages.','max_new_tokens':2048,'thinking':False}
    try:
     request=urllib.request.Request('http://127.0.0.1:8871/generate',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
     answer=json.load(urllib.request.urlopen(request,timeout=120));raw=answer['text'].strip();start=raw.find('[');end=raw.rfind(']');rows=json.loads(raw[start:end+1]);clean=[]
     for i,row in enumerate(rows):
      if not isinstance(row,dict) or any(not isinstance(row.get(lang),str) or not 3<len(row[lang])<1000 for lang in ['en','hi','hinglish']):continue
      if not any('\u0900'<=ch<='\u097f' for ch in row['hi']):continue
      group=hashlib.sha256((key+':'+str(i)).encode()).hexdigest()[:20]
      for lang in ['en','hi','hinglish']:clean.append(dict(group=group,batch=key,language=lang,label=label,text=row[lang],source='synthetic-local-Qwen3-4B',human_verified=False))
     if not clean:raise ValueError('No complete trilingual examples')
     with OUT.open('a') as output:
      for row in clean:output.write(json.dumps(row,ensure_ascii=False)+'\n')
     print(key,'rows',len(clean),'seconds',round(answer['generation_seconds'],1),flush=True)
    except Exception as e:print('FAILED',key,type(e).__name__,flush=True)
if __name__=='__main__':main()
