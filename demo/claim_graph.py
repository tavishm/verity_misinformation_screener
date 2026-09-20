"""Persistent claim/evidence/provenance graph and honest exposure accounting.

Lexical similarity generates candidates only. A caller must separately verify
semantic equivalence and original evidence before reusing a paraphrase verdict.
"""
from __future__ import annotations
from collections import Counter
from datetime import datetime, timezone
from difflib import SequenceMatcher
import hashlib
import json
import re
import sqlite3
import threading
import time
import unicodedata

from .evidence import query_terms
from .qualifiers import dates, numbers, needs_author_context

TTL_SECONDS = 6 * 3600
RELATIVE = re.compile(r'\b(today|yesterday|tomorrow|currently|now|this (?:week|month|year)|last (?:week|month|year)|next (?:week|month|year))\b|आज|कल', re.I)
NEGATION = re.compile(r"\b(not|never|no|without|neither|nor|false|untrue|fake|denied|denies|didn['’]t|isn['’]t|wasn['’]t|hasn['’]t|won['’]t)\b", re.I)
ATTRIBUTION = re.compile(r'\b(said|says|claimed|claims|alleged|alleges|reportedly|according to|announced|announcement|report states|reports that)\b', re.I)
STATES = {
    'proposed': r'\b(propos\w*|introduc\w*|draft|plan\w*|would|could|may|might)\b',
    'passed': r'\b(pass(?:ed|es)?|approv(?:ed|es|al))\b',
    'signed': r'\b(signed|enacted|became law|become law)\b',
    'effective': r'\b(effective|in effect|took effect|takes effect|implemented)\b',
    'blocked': r'\b(blocked|enjoined|suspended|overturned|struck down)\b',
    'future': r'\b(will|shall|going to|expected to|set to)\b',
    'increase': r'\b(increas\w*|rose|risen|higher|raised|grew)\b',
    'decrease': r'\b(decreas\w*|fell|fallen|lower|reduced|declin\w*)\b',
}
IGNORED_CAPS = set('The A An This That These Those On In At For From According During It He She They We I As Of By To And But Also However Yes No'.split())


def normalized(text):
    return ' '.join(unicodedata.normalize('NFKC', text).replace('’', "'").split()).strip()


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def stamp():
    return datetime.now(timezone.utc).isoformat()


def signature(text):
    """Conservative veto features, not semantic truth or an equivalence proof."""
    value = normalized(text)
    capitals = set(re.findall(r'\b[A-Z][A-Za-z-]{2,}\b', value)) - IGNORED_CAPS
    # Expansion is deliberately small: never invent an entity alias.
    entities = {x.casefold() for x in capitals if not any(x.casefold() in names for names in (
        ('january','february','march','april','may','june','july','august','september','october','november','december'),))}
    return {'numbers': sorted(str(n) for n in numbers(value)), 'dates': sorted(d.isoformat() for d in dates(value)),
            'ordered_numbers': re.findall(r'(?<!\w)\d[\d,]*(?:\.\d+)?', value),
            'number_words': sorted(re.findall(r'\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|hundred|thousand|million|billion|trillion|percent|percentage|dollars|rupees|days|months|years)\b',value.casefold())),
            'negation': bool(NEGATION.search(value)), 'attribution': bool(ATTRIBUTION.search(value)),
            'states': sorted(k for k, pattern in STATES.items() if re.search(pattern, value, re.I)),
            'entities': sorted(entities),
            'quantifiers': sorted(set(re.findall(r'\b(all|every|none|only|always|some|many|most)\b', value.casefold()))),
            'relative_time': bool(RELATIVE.search(value))}


def compatibility(first, second):
    a, b = signature(first), signature(second)
    reasons = [field for field in ('numbers','ordered_numbers','number_words','dates','negation','attribution','states','quantifiers') if a[field] != b[field]]
    # Named entities present in either side must not silently disappear/change.
    if a['entities'] != b['entities']:
        reasons.append('entities')
    if a['relative_time'] or b['relative_time']:
        reasons.append('relative_time')
    return {'compatible': not reasons, 'reasons': reasons, 'left': a, 'right': b}


def context_key(text, has_media, post=None):
    post = post or {}
    relative = bool(RELATIVE.search(text))
    return {'has_media': bool(has_media),
            'speaker_context': [post.get('platform'),post.get('post_id')] if needs_author_context(text) else None,
            'media_fingerprint': post.get('media_fingerprint') if has_media else None,
            'time_anchor': post.get('published_at') if relative else None,
            'relative_unanchored': relative and not bool(post.get('published_at')),
            'quoted_text': normalized(post.get('quoted_text') or ''),
            'quoted_post_id': post.get('quoted_post_id'),
            'quoted_time': post.get('quoted_published_at'),
            'wrapper': bool(post.get('has_commentary')) or post.get('relation') in ('quote','crosspost')}


def assessment_key(text, has_media, post, revision):
    return digest([revision, text.strip(), context_key(text, has_media, post)])


class ClaimGraph:
    def __init__(self, path):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,topic TEXT,title TEXT,source_url TEXT);
            CREATE TABLE IF NOT EXISTS claims(id TEXT PRIMARY KEY,event_id TEXT,text TEXT,signature TEXT,status TEXT,evidence TEXT,revision TEXT,checked_at REAL);
            CREATE VIRTUAL TABLE IF NOT EXISTS claim_fts USING fts5(id UNINDEXED,text);
            CREATE TABLE IF NOT EXISTS post_versions(id TEXT PRIMARY KEY,platform TEXT,post_id TEXT,original_post_id TEXT,relation TEXT,text TEXT,context TEXT,created_at REAL);
            CREATE TABLE IF NOT EXISTS post_claims(post_version TEXT,claim_id TEXT,PRIMARY KEY(post_version,claim_id));
            CREATE TABLE IF NOT EXISTS reviews(cache_key TEXT PRIMARY KEY,result TEXT,revision TEXT,created_at REAL,expires_at REAL);
            CREATE TABLE IF NOT EXISTS exposures(id TEXT PRIMARY KEY,session_id TEXT,observation_id TEXT,scope TEXT,platform TEXT,post_id TEXT,text TEXT,has_media INTEGER,has_quote INTEGER,observed_at REAL,cache_key TEXT,assessed INTEGER DEFAULT 0,decisive INTEGER DEFAULT 0,text_decisive INTEGER DEFAULT 0,prelabelled INTEGER DEFAULT 0);
            CREATE TABLE IF NOT EXISTS review_log(id INTEGER PRIMARY KEY,cache_key TEXT,text TEXT,scope TEXT,route TEXT,created_at REAL,result TEXT);
            CREATE TABLE IF NOT EXISTS usage(id INTEGER PRIMARY KEY,kind TEXT,input_tokens INTEGER,output_tokens INTEGER,seconds REAL,created_at REAL);
            CREATE TABLE IF NOT EXISTS packets(id TEXT PRIMARY KEY,source_url TEXT,source_version TEXT,uses INTEGER DEFAULT 0,last_used REAL);
            CREATE INDEX IF NOT EXISTS exposures_scope ON exposures(scope);
            CREATE INDEX IF NOT EXISTS posts_external_id ON post_versions(platform,post_id);
        ''')
        self.db.commit()
        if 'reusable' not in {row[1] for row in self.db.execute('PRAGMA table_info(claims)')}:
            self.db.execute('ALTER TABLE claims ADD COLUMN reusable INTEGER NOT NULL DEFAULT 0')
            self.db.commit()

    def cached(self, key, revision):
        with self.lock:
            row = self.db.execute('SELECT * FROM reviews WHERE cache_key=? AND revision=? AND expires_at>?', (key,str(revision),time.time())).fetchone()
        return dict(row) if row else None

    def apply_policy_version(self, version):
        """Retire decisions after verifier/guard changes without erasing audits."""
        with self.lock,self.db:
            self.db.execute('CREATE TABLE IF NOT EXISTS policy_meta(name TEXT PRIMARY KEY,value TEXT)')
            previous=self.db.execute("SELECT value FROM policy_meta WHERE name='version'").fetchone()
            if previous is None or previous['value']!=version:
                self.db.execute('UPDATE reviews SET expires_at=0')
                self.db.execute("UPDATE claims SET reusable=0,revision='retired:' || revision")
                self.db.execute('UPDATE exposures SET assessed=0,decisive=0,text_decisive=0,prelabelled=0')
                self.db.execute("INSERT OR REPLACE INTO policy_meta VALUES('version',?)",(version,))

    def cached_text(self, text, has_media, post, revision):
        return self.cached(assessment_key(text,has_media,post,revision),revision)

    def candidates(self, text, revision, limit=6):
        terms = query_terms(text)
        if not terms:
            return []
        expression = ' OR '.join('"'+t.replace('"','""')+'"' for t in terms[:24])
        with self.lock:
            rows = self.db.execute('''SELECT c.* FROM claim_fts f JOIN claims c ON c.id=f.id
                WHERE claim_fts MATCH ? AND c.revision=? AND c.checked_at>? AND c.reusable=1 AND c.status IN ('supported','contradicted')
                ORDER BY bm25(claim_fts) LIMIT ?''', (expression,str(revision),time.time()-TTL_SECONDS,limit*3)).fetchall()
        values = [dict(r) for r in rows if not needs_author_context(r['text'])]
        for row in values:
            row['compatibility'] = compatibility(text,row['text'])
            row['similarity'] = SequenceMatcher(None, normalized(text).casefold(), normalized(row['text']).casefold()).ratio()
            row['evidence'] = json.loads(row['evidence'])
        values.sort(key=lambda r:(r['compatibility']['compatible'],r['similarity']),reverse=True)
        return values[:limit]

    def original_match(self, text, has_media, post):
        if not post or post.get('relation') != 'native_repost' or not post.get('original_post_id') or post.get('has_commentary') or post.get('quoted_text'):
            return False
        # Media content equality cannot be established when fingerprints are absent.
        if has_media and not post.get('media_fingerprint'):
            return False
        current = context_key(text,has_media,post)
        with self.lock:
            rows = self.db.execute('SELECT text,context FROM post_versions WHERE platform=? AND post_id=? ORDER BY created_at DESC LIMIT 10',
                                   (post.get('platform'),post['original_post_id'])).fetchall()
        return any(row['text'] == text.strip() and json.loads(row['context']) == current for row in rows)

    def observe(self, value):
        scope = value.get('scope','untracked')
        if scope not in ('live_feed','development'):
            return None
        key = digest([scope,value['session_id'],value['observation_id']])
        with self.lock, self.db:
            self.db.execute('''INSERT OR IGNORE INTO exposures
                (id,session_id,observation_id,scope,platform,post_id,text,has_media,has_quote,observed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)''', (key,value['session_id'],value['observation_id'],scope,value.get('platform'),value.get('post_id'),
                (value.get('text') or '')[:5000],bool(value.get('has_media')),bool(value.get('has_quoted_content')),time.time()))
        return key

    def attach(self, observation_id, session_id, scope, key, result):
        if not observation_id or not session_id or scope not in ('live_feed','development'):
            return
        exposure_id = digest([scope,session_id,observation_id])
        with self.lock, self.db:
            row = self.db.execute('SELECT * FROM exposures WHERE id=?',(exposure_id,)).fetchone()
            if not row:
                return
            decisive = bool(result.get('whole_post_validated')) and not row['has_media'] and not row['has_quote']
            existing = self.db.execute('SELECT created_at FROM reviews WHERE cache_key=?',(key,)).fetchone()
            prelabelled = decisive and bool(existing and existing['created_at'] <= row['observed_at'])
            self.db.execute('UPDATE exposures SET cache_key=?,assessed=1,decisive=?,text_decisive=?,prelabelled=? WHERE id=?',
                (key,decisive,bool(result.get('extracted_claims_resolved')),prelabelled,exposure_id))

    def usage(self, usage, kind='generation'):
        if not usage:
            return
        with self.lock,self.db:
            self.db.execute('INSERT INTO usage(kind,input_tokens,output_tokens,seconds,created_at) VALUES(?,?,?,?,?)',
                (kind,int(usage.get('input_tokens') or 0),int(usage.get('output_tokens') or 0),
                 float(usage.get('generation_seconds',usage.get('seconds',0)) or 0),time.time()))

    def record(self, key, text, has_media, post, revision, result, scope='untracked', cache_result=True):
        post = post or {}
        now = time.time()
        context = context_key(text,has_media,post)
        post_version = digest([post.get('platform'),post.get('post_id'),text.strip(),context])
        claim_ids, event_ids = [], []
        with self.lock,self.db:
            self.db.execute('INSERT OR IGNORE INTO post_versions VALUES(?,?,?,?,?,?,?,?)',
                (post_version,post.get('platform','demo'),post.get('post_id'),post.get('original_post_id'),post.get('relation','unknown'),text.strip(),json.dumps(context),now))
            # Use existing canonical IDs only when the pipeline explicitly approved reuse.
            for claim in result.get('claims',[]):
                citations = claim.get('evidence') or []
                first = citations[0] if citations else {}
                event_id = claim.get('event_id') or ('event_'+digest(first.get('source_url') or sorted(query_terms(claim['text']))[:6])[:20])
                topic = 'US public affairs' if any(s in (first.get('source_url') or '') for s in ('senate.gov','house.gov','whitehouse.gov','federalregister.gov','govinfo.gov','congress.gov','bls.gov','bea.gov','supremecourt.gov')) else 'Other public affairs'
                self.db.execute('INSERT OR IGNORE INTO events VALUES(?,?,?,?)',
                    (event_id,topic,first.get('title') or 'Unresolved evidence gap',first.get('source_url')))
                reusable = not has_media and not context['wrapper'] and not context['quoted_text'] and not RELATIVE.search(claim['text']) and not needs_author_context(claim['text'])
                claim_id = claim.get('canonical_id') or 'claim_'+digest([normalized(claim['text']),context if not reusable else None])[:24]
                claim['canonical_id'],claim['event_id'] = claim_id,event_id
                existing = self.db.execute('SELECT id FROM claims WHERE id=?',(claim_id,)).fetchone()
                if not existing or not claim.get('inherited'):
                    self.db.execute('INSERT OR REPLACE INTO claims VALUES(?,?,?,?,?,?,?,?,?)',
                        (claim_id,event_id,claim['text'],json.dumps(signature(claim['text'])),claim['verdict'],json.dumps(citations,ensure_ascii=False),str(revision),now,int(bool(reusable))))
                    self.db.execute('DELETE FROM claim_fts WHERE id=?',(claim_id,))
                    self.db.execute('INSERT INTO claim_fts(id,text) VALUES(?,?)',(claim_id,claim['text']))
                self.db.execute('INSERT OR IGNORE INTO post_claims VALUES(?,?)',(post_version,claim_id))
                claim_ids.append(claim_id);event_ids.append(event_id)
                for source in citations:
                    packet = digest([source.get('source_url'),source.get('document_version')])
                    self.db.execute('''INSERT INTO packets VALUES(?,?,?,1,?) ON CONFLICT(id) DO UPDATE SET uses=uses+1,last_used=excluded.last_used''',
                        (packet,source.get('source_url'),str(source.get('document_version')),now))
            result.setdefault('reuse',{})['canonical_id'] = claim_ids[0] if claim_ids else None
            result['reuse']['event_id'] = event_ids[0] if event_ids else None
            result['post_version_id'] = post_version
            # A reused review keeps its original age; reading does not refresh truth.
            if cache_result:
                # A new review must not revive exposure flags computed from an
                # older, possibly withdrawn decision under the same cache key.
                self.db.execute('UPDATE exposures SET assessed=0,decisive=0,text_decisive=0,prelabelled=0 WHERE cache_key=?',(key,))
                self.db.execute('INSERT OR REPLACE INTO reviews VALUES(?,?,?,?,?)',
                    (key,json.dumps(result,ensure_ascii=False),str(revision),now,now+TTL_SECONDS))
            self.db.execute('INSERT INTO review_log(cache_key,text,scope,route,created_at,result) VALUES(?,?,?,?,?,?)',
                (key,text[:5000],scope,result.get('reuse',{}).get('kind','fresh_review'),now,json.dumps(result,ensure_ascii=False)))
        return result

    def packet_known(self, source):
        with self.lock:
            return self.db.execute('SELECT 1 FROM packets WHERE id=?', (digest([source.get('source_url'),source.get('document_version')]),)).fetchone() is not None

    def snapshot(self, revision):
        with self.lock:
            exposures = self.db.execute('SELECT * FROM exposures').fetchall()
            logs = self.db.execute('SELECT * FROM review_log ORDER BY id DESC LIMIT 1000').fetchall()
            claims = self.db.execute('''SELECT c.*,COUNT(pc.post_version) AS post_count FROM claims c LEFT JOIN post_claims pc ON pc.claim_id=c.id
                GROUP BY c.id ORDER BY c.checked_at DESC LIMIT 150''').fetchall()
            events = self.db.execute('''SELECT e.*,COUNT(DISTINCT c.id) AS claim_count,COUNT(DISTINCT pc.post_version) AS post_count
                FROM events e LEFT JOIN claims c ON c.event_id=e.id LEFT JOIN post_claims pc ON pc.claim_id=c.id GROUP BY e.id ORDER BY post_count DESC LIMIT 100''').fetchall()
            usage = self.db.execute('SELECT kind,COUNT(*) AS calls,SUM(seconds) AS seconds FROM usage GROUP BY kind').fetchall()
            packets = self.db.execute('SELECT COUNT(*) FROM packets').fetchone()[0]
            valid_keys = {r[0] for r in self.db.execute('SELECT cache_key FROM reviews WHERE revision=? AND expires_at>?',(str(revision),time.time())).fetchall()}
            current_results={r['cache_key']:json.loads(r['result']) for r in self.db.execute('SELECT cache_key,result FROM reviews WHERE revision=? AND expires_at>?',(str(revision),time.time())).fetchall()}
            versions=self.db.execute('''SELECT pc.claim_id,p.* FROM post_claims pc JOIN post_versions p ON p.id=pc.post_version ORDER BY p.created_at DESC LIMIT 1000''').fetchall()
        stats = {}
        for scope in ('live_feed','development'):
            rows = [r for r in exposures if r['scope']==scope]
            current = [r for r in rows if r['cache_key'] in valid_keys]
            total = len(rows)
            stats[scope] = {'observed':total,'assessed':sum(r['assessed'] for r in current),
                'decisive':sum(r['decisive'] for r in current),'text_decisive':sum(r['text_decisive'] for r in current),
                'prelabelled':sum(r['prelabelled'] for r in current),
                'coverage':sum(r['decisive'] for r in current)/total if total else 0,
                'prelabelled_coverage':sum(r['prelabelled'] for r in current)/total if total else 0}
        stats.update(routes=dict(Counter(r['route'] for r in logs)),investigations=sum(r['route'] in ('fresh_review','evidence_reuse','abstained') for r in logs),
                     sources=packets,paid_api_spend=0,local_model_calls=sum(r['calls'] for r in usage),
                     gpu_seconds=sum(r['seconds'] or 0 for r in usage))
        result_claims = []
        for row in claims:
            value=dict(row);value['source_count']=len(json.loads(value.pop('evidence')))
            value['stale']=value['revision']!=str(revision) or value['checked_at'] < time.time()-TTL_SECONDS
            value.pop('signature'); value['checked_at']=datetime.fromtimestamp(value['checked_at'],timezone.utc).isoformat()
            value['post_versions']=[{k:v[k] for k in ('id','platform','post_id','original_post_id','relation','text','created_at')} for v in versions if v['claim_id']==value['id']][:12]
            result_claims.append(value)
        recent=[]
        for row in logs[:30]:
            value=json.loads(row['result']);value.update(id=str(row['id']),text=row['text'],capture_scope=row['scope'])
            current=current_results.get(row['cache_key'],{})
            value['stale']=not current or value.get('checked_at')!=current.get('checked_at')
            if value['stale']:
                value.update(previous_label=value.get('label'),label='Expired / superseded',rating=None)
            recent.append(value)
        return {'stats':stats,'events':[dict(r) for r in events],'claims':result_claims,'recent':recent}

    def graph(self, revision):
        snapshot=self.snapshot(revision)
        nodes,edges=[],[]
        topics=set()
        for event in snapshot['events']:
            topic='topic_'+digest(event['topic'])[:12]
            if topic not in topics:
                nodes.append({'id':topic,'type':'topic','label':event['topic']});topics.add(topic)
            nodes.append({'id':event['id'],'type':'event','label':event['title'],'post_count':event['post_count']})
            edges.append({'source':topic,'target':event['id'],'type':'contains'})
        for claim in snapshot['claims']:
            nodes.append({'id':claim['id'],'type':'claim','label':claim['text'],'status':claim['status'],'post_count':claim['post_count']})
            edges.append({'source':claim['event_id'],'target':claim['id'],'type':'assertion'})
        posts=set()
        for claim in snapshot['claims']:
            for post in claim['post_versions']:
                if post['id'] not in posts:
                    nodes.append({'id':post['id'],'type':'post_version','label':post['text'],'platform':post['platform'],
                                  'post_id':post['post_id'],'original_post_id':post['original_post_id'],'relation':post['relation']})
                    posts.add(post['id'])
                edges.append({'source':claim['id'],'target':post['id'],'type':'asserted_in'})
        return {'nodes':nodes,'edges':edges}
