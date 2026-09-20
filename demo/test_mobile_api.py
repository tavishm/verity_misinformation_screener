"""Privacy boundary tests; no live messages, model calls, or public fetches."""
import asyncio
import re
import time
import unittest
import json
import tempfile
from pathlib import Path
from unittest.mock import patch
from urllib.parse import urlsplit

from fastapi import FastAPI
from fastapi.testclient import TestClient
from demo.mobile_api import MobileAPI
from demo.mobile_links import public_target, links_in, link_count, fetch_page


class MobileAPITests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        async def review(text, has_media, stage):
            self.calls.append(text)
            return {'label': 'Unsure', 'rating': 3, 'claims': []}
        self.api = MobileAPI(review, asyncio.Semaphore(2))
        app = FastAPI()
        self.api.install(app)
        self.client = TestClient(app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def pair(self):
        page = self.client.get('/mobile')
        code = re.search(r'<code>(\d{6})</code>', page.text)[1]
        response = self.client.post('/api/mobile/pair', json={'code': code})
        self.assertEqual(response.status_code, 200)
        return code, {'X-Factcheck-Token': response.json()['token']}

    def test_pair_one_use_and_no_browser_cross_origin(self):
        page = self.client.get('/mobile')
        code = re.search(r'<code>(\d{6})</code>', page.text)[1]
        self.assertEqual(page.headers['cache-control'], 'no-store')
        self.assertEqual(self.client.post('/api/mobile/pair', json={'code': code},
            headers={'Origin': 'https://untrusted.example'}).status_code, 403)
        code, headers = self.pair()
        self.assertTrue(self.api.authorized(headers['X-Factcheck-Token']))
        self.assertEqual(self.client.post('/api/mobile/pair', json={'code': code}).status_code, 403)

    def test_explicit_boolean_consent_before_any_work(self):
        _, headers = self.pair()
        for body in ({'text': 'A private test message'}, {'text': 'A private test message', 'consent': False}):
            self.assertEqual(self.client.post('/api/mobile/check', json=body, headers=headers).status_code, 403)
        self.assertEqual(self.client.post('/api/mobile/check', json={'text': 'A private test message', 'consent': 'true'}, headers=headers).status_code, 422)
        self.assertEqual(self.calls, [])
        self.assertEqual(self.api.jobs, {})

    def test_blank_text_is_rejected_before_any_job_or_review(self):
        _, headers = self.pair()
        response = self.client.post('/api/mobile/check', json={'text': '   ', 'consent': True}, headers=headers)
        self.assertEqual(response.status_code, 422)
        self.assertEqual(self.calls, [])
        self.assertEqual(self.api.jobs, {})

    def test_job_owner_and_expiration(self):
        _, first = self.pair()
        _, second = self.pair()
        response = self.client.post('/api/mobile/check', json={'text': 'Synthetic factual sentence', 'consent': True}, headers=first)
        job_id = response.json()['job_id']
        self.assertEqual(self.client.get('/api/mobile/jobs/' + job_id, headers=second).status_code, 404)
        self.assertEqual(self.client.get('/api/mobile/jobs/' + job_id, headers=first).status_code, 200)
        self.api.jobs[job_id]['expires_at'] = time.time() - 1
        self.assertEqual(self.client.get('/api/mobile/jobs/' + job_id, headers=first).status_code, 404)
        self.assertNotIn(job_id, self.api.jobs)

    def test_no_pairing_no_check(self):
        self.assertEqual(self.client.post('/api/mobile/check', json={'text': 'Synthetic claim', 'consent': True}).status_code, 401)
        self.assertEqual(self.calls, [])

    def test_connection_survives_restart_without_saving_tokens_or_messages(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'paired.json'
            self.api.device_store = path
            _, headers = self.pair()
            token = headers['X-Factcheck-Token']
            restarted = MobileAPI(None, asyncio.Semaphore(2), device_store=path)
            self.assertTrue(restarted.authorized(token))
            self.assertNotIn(token, path.read_text())
            self.assertEqual(restarted.jobs, {})
            self.assertEqual(path.stat().st_mode & 0o077, 0)
            path.write_text(json.dumps({self.api.fingerprint(token): time.time() - 1}))
            expired = MobileAPI(None, asyncio.Semaphore(2), device_store=path)
            self.assertFalse(expired.authorized(token))
            self.api.device_store = None

    def test_cloud_provider_requires_its_own_explicit_opt_in(self):
        cloud_calls = []
        async def cloud(text, stage, owner):
            cloud_calls.append(text)
            return {'label': 'Unsure', 'rating': 3, 'claims': []}
        self.api.cloud_reviewer = cloud
        _, headers = self.pair()
        self.client.post('/api/mobile/check', json={'text': 'Local-only synthetic message', 'consent': True}, headers=headers)
        self.assertEqual(cloud_calls, [])
        result = self.client.post('/api/mobile/check', json={'text': 'Approved cloud synthetic claim',
            'consent': True, 'cloud_consent': True}, headers=headers)
        for _ in range(10):
            state = self.client.get('/api/mobile/jobs/' + result.json()['job_id'], headers=headers)
            if state.json()['status'] != 'pending':
                break
            time.sleep(.01)
        self.assertEqual(cloud_calls, ['Approved cloud synthetic claim'])
        self.assertNotIn('Approved cloud synthetic claim', self.calls)

    def test_delete_check_forgets_result(self):
        _, headers = self.pair()
        job_id = self.client.post('/api/mobile/check', json={'text': 'Synthetic claim', 'consent': True}, headers=headers).json()['job_id']
        self.assertEqual(self.client.delete('/api/mobile/jobs/' + job_id, headers=headers).status_code, 200)
        self.assertNotIn(job_id, self.api.jobs)


class PublicLinkTests(unittest.TestCase):
    def resolver(self, address):
        return lambda *args, **kwargs: [(2, 1, 6, '', (address, 443))]

    def test_private_targets_and_credentials_blocked(self):
        for address in ('127.0.0.1', '10.2.3.4', '169.254.169.254', '::1', '192.168.2.1', 'fc00::1'):
            with self.assertRaises(ValueError):
                public_target('https://public-looking.example/page', self.resolver(address))
        for url in ('file:///etc/passwd', 'https://user:pass@example.org', 'http://example.org:8870', 'http://example.org/\nrequest'):
            with self.assertRaises(ValueError):
                public_target(url, self.resolver('1.1.1.1'))

    def test_pins_validated_public_ip_and_limits_links(self):
        parts, host, port, address = public_target('https://example.org/test#fragment', self.resolver('1.1.1.1'))
        self.assertEqual((host, port, address), ('example.org', 443, '1.1.1.1'))
        self.assertEqual(len(links_in('https://a.example https://b.example https://c.example')), 2)
        self.assertEqual(link_count('https://a.example https://b.example https://c.example'), 3)

    def test_redirect_is_revalidated_before_a_second_connection(self):
        class Response:
            status = 302
            def getheader(self, name, default=None): return 'https://127.0.0.1/private' if name == 'Location' else default
        class Connection:
            def request(self, *_args, **_kwargs): pass
            def getresponse(self): return Response()
            def close(self): pass
        first = (urlsplit('https://public.example/start'), 'public.example', 443, '1.1.1.1')
        with patch('demo.mobile_links.public_target', side_effect=[first, ValueError('private redirect')]) as target, \
             patch('demo.mobile_links.PinnedConnection', return_value=Connection()):
            with self.assertRaisesRegex(ValueError, 'private redirect'):
                fetch_page('https://public.example/start')
        self.assertEqual(target.call_count, 2)


class ActualAppAuthorizationTests(unittest.TestCase):
    def test_phone_token_cannot_access_desktop_admin_routes(self):
        # Exercise the installed app middleware, not the standalone MobileAPI.
        from demo.app import app, LOCAL_TOKEN
        with TestClient(app) as client:
            code = re.search(r'<code>(\d{6})</code>', client.get('/mobile').text)[1]
            paired = client.post('/api/mobile/pair', json={'code': code})
            self.assertEqual(paired.status_code, 200)
            phone = {'X-Factcheck-Token': paired.json()['token']}
            self.assertEqual(client.get('/api/stats', headers=phone).status_code, 401)
            self.assertEqual(client.post('/api/mobile/check', json={'text': 'Synthetic private message', 'consent': True},
                                         headers={'X-Factcheck-Token': LOCAL_TOKEN}).status_code, 401)


if __name__ == '__main__':
    unittest.main()
