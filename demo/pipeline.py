"""Every post is checked; only verified assertions or source research are shared.

No cluster majority labels. Similarity is candidate retrieval, qualifier guards
only veto, and MiniCheck support is never interpreted as contradiction.
"""
from __future__ import annotations
import asyncio
import json
import os
import re
import time
from copy import deepcopy

import httpx

from .claim_graph import RELATIVE, assessment_key, normalized, stamp
from .qualifiers import sentence_claims, support_gap, needs_author_context
from .evidence_policy import official_scope_gap, official_claim_type, contradiction_scope_gap


class VerificationEngine:
    def __init__(self, index, graph, ask, validate, extraction_prompt, verification_prompt, researcher=None, scorer=None):
        self.index, self.graph, self.ask, self.validate = index, graph, ask, validate
        self.extraction_prompt, self.verification_prompt = extraction_prompt, verification_prompt
        self.researcher, self.scorer = researcher, scorer or self.score
        self.research_lock = asyncio.Lock()
        self.scorer_base = os.environ.get('FACTCHECK_SCORER_BASE','http://127.0.0.1:8872')

    async def score(self, pairs):
        try:
            async with httpx.AsyncClient(timeout=20) as client:
                response = await client.post(self.scorer_base+'/score',json={'pairs':pairs})
                response.raise_for_status()
                value=response.json()
            self.graph.usage(value,'entailment')
            return value['scores']
        except (httpx.HTTPError,KeyError,ValueError):
            return None  # Ordinary source verification remains available.

    async def generate(self, prompt, payload, tokens):
        value,usage=await self.ask(prompt,payload,tokens)
        self.graph.usage(usage)
        return value,usage

    def cached(self, text, has_media, post, revision, scope='untracked'):
        key=assessment_key(text,has_media,post,revision)
        hit=self.graph.cached(key,revision)
        if not hit:
            return None
        result=json.loads(hit['result'])
        if needs_author_context(text) and result.get('extracted_claims_resolved'):
            return None
        for claim in result.get('claims',[]):
            claim['inherited']=True
        native=self.graph.original_match(text,has_media,post)
        result['reuse']={'kind':'unchanged_repost' if native else 'exact_reuse',
            'reason':'Original identity, unchanged text and available context match.' if native else 'Identical checked text and context; evidence version and expiry still match.',
            'evidence_reused':True}
        result['model_usage']=[]
        return self.graph.record(key,text,has_media,post,revision,result,scope,cache_result=False)

    async def reuse_claim(self, text, revision, post):
        # A wrapper, relative date, attached media reference or attribution must
        # not silently borrow a canonical claim with a different context.
        if needs_author_context(text) or RELATIVE.search(text) or post.get('quoted_text') or post.get('relation') in ('quote','crosspost'):
            return None
        for candidate in self.graph.candidates(text,revision,limit=4):
            exact = normalized(candidate['text']) == normalized(text)
            if not exact and (not candidate['compatibility']['compatible'] or candidate['similarity'] < .72):
                continue
            evidence=candidate['evidence']
            if not evidence:
                continue
            if not exact:
                # Re-check every member against the canonical assertion AND the
                # original source. Never expand a cluster by transitive links.
                if candidate['status']!='supported' or re.search(r'[^\x00-\x7f]',text.replace('’',"'").replace('“','"').replace('”','"')):
                    continue
                # A set or even ordered list of numbers cannot prove who owns
                # which value. Multi-number paraphrases take a fresh review.
                if len(re.findall(r'(?<!\w)\d[\d,]*(?:\.\d+)?',text))>1:
                    continue
                if support_gap(text,[s.get('quote',s.get('text','')) for s in evidence]):
                    continue
                passage='\n'.join(s.get('quote',s.get('text','')) for s in evidence)
                if len(passage)>10000:
                    continue
                scores=await self.scorer([
                    {'document':candidate['text'],'claim':text},
                    {'document':text,'claim':candidate['text']},
                    {'document':passage,'claim':text}])
                if not scores or min(scores)<.985:
                    continue
            return {'text':text,'verdict':candidate['status'],'evidence':deepcopy(evidence),
                'explanation':'Matches a current assertion checked against the original evidence.',
                'canonical_id':candidate['id'],'event_id':candidate['event_id'],'inherited':True,
                'reuse_method':'exact_assertion' if exact else 'bidirectional_entailment_and_source',
                'canonical_checked_at':candidate['checked_at']}
        return None

    def retrieve(self, claims, queries):
        lists=[]
        for claim in claims:
            hits=[]
            for query in [claim['text'],*queries[:2]]:
                hits.extend(self.index.search(query,limit=5))
            lists.append(hits)
        sources,seen={},set()
        for position in range(max((len(c) for c in lists),default=0)):
            for candidates in lists:
                if position>=len(candidates):
                    continue
                hit=self.index.context(candidates[position],max_chars=1800)
                if not hit:
                    continue
                if any(s['document_id']==hit['document_id'] and s['document_version']==hit['document_version'] and s['start_offset']<=hit['start_offset'] and s['end_offset']>=hit['end_offset'] for s in sources.values()):
                    continue
                key=(hit.get('document_id'),hit.get('document_version'),hit.get('start_offset'))
                if key in seen:
                    continue
                seen.add(key);sid='S'+str(len(sources)+1)
                sources[sid]={**hit,'source_id':sid}
                if len(sources)>=8:
                    return sources
        return sources

    async def verify(self, text, claims, sources, post):
        if not sources:
            return [{'text':c['text'],'verdict':'insufficient_evidence','evidence':[],
                     'explanation':'No adequate source passage is available.'} for c in claims],None
        payload={'post':text,'context':{k:post.get(k) for k in ('published_at','relation','quoted_text','quoted_published_at')},
            'claims':[{'id':i,'text':c['text']} for i,c in enumerate(claims)],
            'sources':[{k:s.get(k) for k in ('source_id','title','text','published_at','published_at_raw','date_warning','publisher','source_kind','language','scope_note')} for s in sources.values()]}
        verified,usage=await self.generate(self.verification_prompt,payload,1250)
        ids={s['source_id'] for s in payload['sources']}
        rows=self.validate(claims,verified,{k:v for k,v in sources.items() if k in ids})
        # Agreement is a conservative publishing gate, not calibrated truth
        # confidence. A low support score causes abstention, never "false".
        english=[];pairs=[]
        for row in rows:
            letters=[c for c in row['text'] if c.isalpha()]
            is_english=bool(letters) and sum(ord(c)<128 for c in letters)/len(letters)>.97
            passage='\n'.join(s.get('quote',s.get('text','')) for s in row.get('evidence',[]))
            if row['verdict']=='supported' and is_english and 0<len(passage)<=12000:
                english.append(row);pairs.append({'document':passage,'claim':row['text']})
        if pairs:
            scores=await self.scorer(pairs)
            if scores and len(scores)==len(english):
                for row,score in zip(english,scores):
                    row['source_support_score']=round(score,5)
                    if score<.90:
                        row.update(verdict='insufficient_evidence',explanation='The independent local support check did not confirm this assertion against its cited passages.')
        # This prototype has no trusted relative-time resolver. Fail closed even
        # if the small model guesses the meaning of "today".
        for row in rows:
            scope_gap=official_scope_gap(row['text'],row.get('evidence',[]))
            if not scope_gap and row['verdict']=='contradicted':
                scope_gap=contradiction_scope_gap(row['text'],row.get('evidence',[]))
            if scope_gap:
                row.update(verdict='insufficient_evidence',explanation=scope_gap)
            if needs_author_context(row['text']):
                row.update(verdict='insufficient_evidence',explanation='This assertion needs the original speaker or author context, which this prototype has not established.')
            if RELATIVE.search(row['text']) and not post.get('published_at'):
                row.update(verdict='insufficient_evidence',explanation='The original publication date is needed to resolve this relative date.')
            # A government document can establish its contents, not whether a
            # social post exists or a quotation is currently being circulated.
            circulation=re.search(r'\b(?:being|been|is|was|are|were)\s+(?:widely\s+)?(?:shared|circulated)\b|\b(?:going|went|gone) viral\b|\b(?:a|the|this) (?:tweet|post) (?:quotes|says|claims|shows)\b',row['text'],re.I)
            citations=row.get('evidence',[])
            if circulation and citations and all(str(s.get('source_kind','')).startswith('official_') for s in citations):
                row.update(verdict='insufficient_evidence',explanation='These documents establish the source wording, not the separate claim about its circulation in social posts.')
        return rows,usage

    async def review(self,text,has_media=False,post=None,scope='untracked',stage=None,include_original=True):
        post=post or {};stage=stage or (lambda message:None)
        revision=self.index.revision
        cached=self.cached(text,has_media,post,revision,scope)
        if cached:
            return cached
        quotation=re.fullmatch(r'The document titled "([^"\n]{1,600})" states: "(.{12,4000})"\.',text.strip(),re.S)
        if quotation:
            # Exact quotation is a string/provenance operation, not an LLM
            # truth judgment. It confirms wording, never the quoted allegation.
            hit=self.index.lookup_exact_quote(*quotation.groups())
            row={'text':text,'verdict':'supported' if hit else 'insufficient_evidence',
                 'evidence':[{**hit,'source_id':'Q1','quote':hit['text']}] if hit else [],
                 'explanation':'The exact quotation is present in this current source version.' if hit else 'This exact title and quotation were not found together in a current source document.'}
            route=('evidence_reuse' if self.graph.packet_known(hit) else 'fresh_review') if hit else 'abstained'
            result=self.finish(text,has_media,post,revision,[row],[],scope,route,'Exact quotation lookup against versioned source text; no model generation.')
            return await self.with_original(result,post,scope,stage,include_original)
        sentences=sentence_claims(text)
        if not 1<=len(sentences)<=4:
            return self.finish(text,has_media,post,revision,[],[],scope,'abstained',
                'This text needs a longer, more detailed review.', label='Needs detailed review')
        stage('Looking for matching assertions')
        rows=[]
        for sentence in sentences:
            rows.append(None if has_media else await self.reuse_claim(sentence,revision,post))
        usage=[];sources={};research_note=None
        if not all(rows):
            stage('Identifying factual assertions')
            extracted,used=await self.generate(self.extraction_prompt,{'post':text,'quoted_context':post.get('quoted_text')},220)
            usage.append(used)
            if extracted.get('kind')=='no_factual_claim':
                result=self.finish(text,has_media,post,revision,[],usage,scope,'abstained','No checkable assertion identified.',label='No factual claim')
                return await self.with_original(result,post,scope,stage,include_original)
            if extracted.get('kind') not in ('factual','unclear'):
                return self.finish(text,has_media,post,revision,[],usage,scope,'abstained','Claim identification was inconclusive.',label='Needs detailed review')
            queries=extracted.get('queries',[])
            queries=[q[:300] for q in queries[:2] if isinstance(q,str)] if isinstance(queries,list) else []
            missing=[{'text':s} for s,r in zip(sentences,rows) if r is None]
            stage('Retrieving original source passages')
            sources=self.retrieve(missing,queries)
            stage('Checking each assertion against evidence')
            checked,used=await self.verify(text,missing,sources,post)
            if used: usage.append(used)
            # Spend free source requests only for an evidence gap, with query
            # deduplication and hard acquisition caps in the researcher.
            if self.researcher and any(r['verdict']=='insufficient_evidence' and not r.get('evidence') for r in checked) and queries:
                stage('Looking up additional public source documents')
                async with self.research_lock:
                    try:
                        research_note=await self.researcher(queries[0])
                    except Exception:
                        research_note={'status':'unavailable','note':'The free primary-source lookup did not complete.'}
                if self.index.revision!=revision:
                    revision=self.index.revision
                    # Refresh all claims after any evidence change; stale labels
                    # must never survive by attaching to a new revision number.
                    missing=[{'text':s} for s in sentences];rows=[None]*len(sentences)
                    sources=self.retrieve(missing,queries)
                    stage('Checking newly retrieved evidence')
                    checked,used=await self.verify(text,missing,sources,post)
                    if used: usage.append(used)
            iterator=iter(checked)
            rows=[row if row is not None else next(iterator) for row in rows]
        if revision!=self.index.revision:
            raise ValueError('Sources changed during this check. Retry against the refreshed library.')
        shared=bool(sources) and any(self.graph.packet_known(s) for s in sources.values())
        complete=bool(rows) and all(r['verdict'] in ('supported','contradicted') for r in rows)
        all_inherited=bool(rows) and all(r.get('inherited') for r in rows)
        route='claim_reuse' if all_inherited else 'evidence_reuse' if shared else 'fresh_review'
        if not complete: route='abstained'
        reason={'claim_reuse':'Every assertion independently matched a current canonical assertion.',
                'evidence_reuse':'Existing source research was reused; this text received a new verdict.',
                'fresh_review':'New assertions were checked against original source passages.',
                'abstained':'Available evidence does not resolve every assertion.'}[route]
        result=self.finish(text,has_media,post,revision,rows,usage,scope,route,reason)
        if research_note: result['research']=research_note
        return await self.with_original(result,post,scope,stage,include_original)

    async def with_original(self,result,post,scope,stage,include_original):
        quoted=post.get('quoted_text') or ''
        if include_original and len(quoted.strip())>=3 and post.get('relation') in ('quote','crosspost'):
            stage('Checking the quoted original separately')
            original_post={'platform':post.get('platform'),'post_id':post.get('quoted_post_id'),
                'published_at':post.get('quoted_published_at'),'relation':'original'}
            key=result.get('assessment_key')
            try:
                original=await self.review(quoted,False,original_post,scope,stage,include_original=False)
                if result.get('source_revision')!=str(self.index.revision):
                    raise ValueError('Sources changed while checking the quoted original. Retry with the updated evidence.')
            except Exception:
                with self.graph.lock,self.graph.db:
                    self.graph.db.execute('DELETE FROM reviews WHERE cache_key=?',(key,))
                raise
            result['original_assessment']={**original,'scope':'Quoted text only; does not verify the wrapper or any attached media'}
            result['whole_post_validated']=False
            # Store combined presentation without extending the main review age.
            with self.graph.lock,self.graph.db:
                original_row=self.graph.cached(original.get('assessment_key'),self.index.revision)
                if original_row:
                    self.graph.db.execute('UPDATE reviews SET result=?,expires_at=MIN(expires_at,?) WHERE cache_key=?',
                        (json.dumps(result,ensure_ascii=False),original_row['expires_at'],key))
                else:
                    self.graph.db.execute('DELETE FROM reviews WHERE cache_key=?',(key,))
        return result

    def finish(self,text,has_media,post,revision,rows,usage,scope,route,reason,label=None):
        verdicts=[r['verdict'] for r in rows]
        complete=bool(verdicts) and all(v in ('supported','contradicted') for v in verdicts)
        rating=4 if verdicts and all(v=='supported' for v in verdicts) else 2 if verdicts and all(v=='contradicted' for v in verdicts) else 3
        label=label or ('Probably true' if rating==4 else 'Likely false' if rating==2 else 'Mixed claims' if complete else 'Unsure')
        if not rows: rating=None if label=='No factual claim' else 3
        for row in rows:
            row['evidence_scope']=official_claim_type(row['text'],row.get('evidence',[]))
        attribution_only=any(r['evidence_scope']=='attribution_only' for r in rows)
        whole=complete and not attribution_only and not has_media and not post.get('quoted_text') and post.get('relation') not in ('quote','crosspost')
        if complete and attribution_only and all(v=='supported' for v in verdicts):
            label='Source wording confirmed';rating=3
        key=assessment_key(text,has_media,post,revision)
        result={'rating':rating,'label':label,'claims':rows,'extracted_claims_resolved':complete,
            'whole_post_validated':whole,'scope':'Entire supplied text; attached media unchecked' if has_media else 'Wrapper text only; quoted original assessed separately' if post.get('quoted_text') else 'Entire supplied text; experimental source assessment',
            'model_usage':usage,'source_count':len({s.get('source_url') for r in rows for s in r.get('evidence',[])}),
            'checked_at':stamp(),'experimental':True,'assessment_key':key,'source_revision':str(revision),
            'reuse':{'kind':route,'reason':reason,'evidence_reused':route in ('claim_reuse','evidence_reuse')}}
        result=self.graph.record(key,text,has_media,post,revision,result,scope)
        if attribution_only:
            result['scope']='Source attribution only; underlying assertions are not independently verified' + ('; media unchecked' if has_media else '')
            with self.graph.lock,self.graph.db:
                self.graph.db.execute('UPDATE reviews SET result=? WHERE cache_key=?',(json.dumps(result,ensure_ascii=False),key))
        # A semantic transfer cannot keep refreshing an old canonical decision.
        inherited_dates=[r['canonical_checked_at'] for r in rows if r.get('inherited') and r.get('canonical_checked_at')]
        if inherited_dates:
            from .claim_graph import TTL_SECONDS
            with self.graph.lock,self.graph.db:
                self.graph.db.execute('UPDATE reviews SET expires_at=MIN(expires_at,?) WHERE cache_key=?', (min(inherited_dates)+TTL_SECONDS,key))
        return result
