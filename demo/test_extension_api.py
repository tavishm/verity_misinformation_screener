"""Loopback pairing and inference-free cache tests. No model calls or real feed data."""
import tempfile
from pathlib import Path
import unittest
from fastapi.testclient import TestClient
from demo import app as module
from demo.claim_graph import ClaimGraph, assessment_key
from demo.pipeline import VerificationEngine

class ExtensionAPITests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(module.app)
        self.headers = {'X-Factcheck-Token': module.LOCAL_TOKEN}
        self.origin = 'chrome-extension://' + 'a' * 32
        self.saved_jobs, self.saved_cache = module.jobs.copy(), module.cache.copy()
        self.saved_graph,self.saved_engine=module.GRAPH,module.ENGINE
        self.temp=tempfile.TemporaryDirectory()
        module.GRAPH=ClaimGraph(Path(self.temp.name)/'graph.sqlite3')
        module.ENGINE=VerificationEngine(module.INDEX,module.GRAPH,module.ask,module.validate_verdicts,module.EXTRACTION,module.VERIFICATION)
        module.jobs.clear()
        module.cache.clear()

    def tearDown(self):
        module.jobs.clear(); module.jobs.update(self.saved_jobs)
        module.cache.clear(); module.cache.update(self.saved_cache)
        module.GRAPH.db.close()
        module.GRAPH,module.ENGINE=self.saved_graph,self.saved_engine
        self.temp.cleanup()
        self.client.close()

    def test_pairing_only_extension_json_origin(self):
        for origin in ['', 'https://x.com', 'https://evil.example', 'chrome-extension://abc']:
            result = self.client.post('/extension/connect', json={'connect': True}, headers={'Origin': origin})
            self.assertEqual(result.status_code, 403)
        result = self.client.post('/extension/connect', json={'connect': True}, headers={'Origin': self.origin})
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()['token'], module.LOCAL_TOKEN)
        self.assertEqual(result.headers['cache-control'], 'no-store')
        self.assertEqual(result.headers['access-control-allow-origin'], self.origin)

    def test_malformed_pairing_cannot_expose_token(self):
        headers = {'Origin': self.origin, 'Content-Type': 'application/json'}
        self.assertEqual(self.client.post('/extension/connect', content='{', headers=headers).status_code, 400)
        self.assertEqual(self.client.post('/extension/connect', json={'connect': False}, headers=headers).status_code, 400)
        self.assertEqual(self.client.post('/extension/connect', content='connect=true', headers={'Origin': self.origin}).status_code, 415)

    def test_lookup_requires_auth_and_never_creates_job(self):
        self.assertEqual(self.client.post('/api/lookup', json={'text': 'Example claim'}).status_code, 401)
        for _ in range(3):
            self.assertEqual(self.client.post('/api/lookup', json={'text': 'Example claim'}, headers=self.headers).json(), {'found': False})
        self.assertEqual(module.jobs, {})
        self.assertEqual(self.client.post('/api/lookup', json={'text': 'x'*5001}, headers=self.headers).status_code, 422)

    def test_lookup_distinguishes_media_and_source_revision(self):
        claim = 'A test claim'
        key = assessment_key(claim,False,{},module.INDEX.revision)
        module.jobs['example'] = {'job_id': 'example', 'cache_key': key, 'status': 'pending', 'source_revision': module.INDEX.revision}
        result = self.client.post('/api/lookup', json={'text': claim}, headers=self.headers).json()
        self.assertTrue(result['coalesced'])
        module.jobs['example']['status'] = 'complete'; module.cache[key] = 'example'
        module.GRAPH.record(key,claim,False,{},module.INDEX.revision,
            {'rating':3,'label':'Unsure','claims':[],'reuse':{'kind':'abstained'}})
        result = self.client.post('/api/lookup', json={'text': claim}, headers=self.headers).json()
        self.assertTrue(result['cached'])
        self.assertFalse(self.client.post('/api/lookup', json={'text': claim, 'has_media': True}, headers=self.headers).json()['found'])
        module.jobs['example']['source_revision'] = 'old-source-version'
        self.assertEqual(self.client.get('/api/jobs/example', headers=self.headers).json()['status'], 'stale')

    def test_observations_validate_scope_and_context(self):
        body={'session_id':'test','observation_id':'post-1','scope':'live_feed','platform':'x',
              'text':'A quoted example','has_quoted_content':True}
        self.assertEqual(self.client.post('/api/observations',json=body,headers=self.headers).status_code,200)
        self.client.post('/api/observations',json=body,headers=self.headers)
        self.assertEqual(module.GRAPH.snapshot(module.INDEX.revision)['stats']['live_feed']['observed'],1)
        self.assertEqual(self.client.post('/api/observations',json={**body,'scope':'untracked'},headers=self.headers).status_code,422)

    def test_dashboard_serves_assets_without_leaking_token_to_unpaired_api(self):
        self.assertEqual(self.client.get('/dashboard').status_code,200)
        self.assertEqual(self.client.get('/assets/dashboard.js').status_code,200)
        self.assertEqual(self.client.get('/assets/local-token').status_code,404)
        self.assertEqual(self.client.get('/api/dashboard').status_code,401)

if __name__ == '__main__':
    unittest.main()
