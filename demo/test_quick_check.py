"""Fast-answer scope, consent routing and current-news safety; no paid calls."""
import asyncio
import json
import time
import unittest
import tempfile
from pathlib import Path
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

from demo.quick_check import QuickChecker, route
from demo.mobile_api import MobileAPI, MobileRequest
from demo.openrouter_research import BriefWebResearch, CostLedger


class QuickCheckTests(unittest.IsolatedAsyncioTestCase):
    async def test_simple_answer_is_model_based_and_has_no_fake_sources(self):
        calls = []
        async def model(body):
            calls.append(body)
            return {'text': json.dumps({'answer': 'false', 'why': 'The Earth is round.'}), 'truncated': False}
        value = await QuickChecker(requester=model)('The Earth is flat.')
        self.assertEqual(value['label'], 'Looks false')
        self.assertFalse(value['source_verified'])
        self.assertFalse(value['whole_post_validated'])
        self.assertEqual(value['claims'][0]['evidence'], [])
        self.assertEqual(value['cost_usd'], 0)
        self.assertEqual(len(calls), 1)

    async def test_news_links_dates_and_medical_decisions_never_answered_from_memory(self):
        async def forbidden(_): raise AssertionError('must not use model memory')
        checker = QuickChecker(requester=forbidden)
        for text in ['Trump has died.', 'Amit Shah has died.', 'Prices fell 20 percent today.',
                     'Read https://example.org/news', 'Read bbc.com/news', 'What dose should I take?',
                     'A new law was announced in 2026.']:
            self.assertIsNone(await checker(text), text)
        self.assertEqual(route('A photo', True), 'deep')
        self.assertEqual(route('x' * 421), 'deep')

    async def test_unsure_truncated_and_malformed_answers_escalate(self):
        for result in [{'text':'not json'}, {'text':'{}'}, {'text':json.dumps({'answer':'research','why':'Need current information.'})},
                       {'text':json.dumps({'answer':'true','why':'A clear answer.'}),'truncated':True}]:
            async def model(_, result=result): return result
            self.assertIsNone(await QuickChecker(requester=model)('A synthetic assertion.'))

    async def test_fast_path_bypasses_busy_research_slot_and_forced_check_bypasses_fast(self):
        fast_calls, web_calls = [], []
        async def quick(text, has_media, stage):
            fast_calls.append(text)
            return {'label':'Looks false','claims':[]}
        async def web(text, stage, owner):
            web_calls.append(text)
            return {'label':'Likely false','claims':[]}
        api = MobileAPI(None, asyncio.Semaphore(0), cloud_reviewer=web, quick_reviewer=quick)
        api.jobs['one'] = {'owner':'test', 'status':'pending'}
        await asyncio.wait_for(api.process('one',MobileRequest(text='A short assertion.',consent=True,cloud_consent=True)),.2)
        self.assertEqual(api.jobs['one']['status'],'complete')
        self.assertEqual(web_calls,[])
        api.capacity.release()
        api.jobs['two']={'owner':'test','status':'pending'}
        await api.process('two',MobileRequest(text='A short assertion.',consent=True,cloud_consent=True,force_research=True))
        self.assertEqual(len(fast_calls),1)
        self.assertEqual(len(web_calls),1)

    async def test_whitespace_is_not_a_source_failure_but_changed_negation_is(self):
        page={'url':'https://public.example/ref','title':'Reference','text':'Vaccines\n\n do not cause autism.'}
        checker=BriefWebResearch()
        with patch('demo.openrouter_research.fetch_page',return_value=page):
            yes=await checker._verified_sources([{'url':page['url'],'quote':'Vaccines do not cause autism.'}])
            no=await checker._verified_sources([{'url':page['url'],'quote':'Vaccines do cause autism.'}])
        self.assertEqual(len(yes),1);self.assertEqual(no,[])

    async def test_current_death_rumor_cannot_reuse_old_debunk(self):
        checker=BriefWebResearch();body=checker.request_body('A named public figure has died.')
        self.assertEqual(body['max_tool_calls'],1)
        quote='The death rumor is false according to a named official.'
        page={'url':'https://public.example/ref','title':'Reference','text':quote,
              'published_at':(datetime.now(timezone.utc)-timedelta(days=12)).isoformat()}
        with patch('demo.openrouter_research.fetch_page',return_value=page):
            self.assertEqual(await checker._verified_sources([{'url':page['url'],'quote':quote}]),[])
        page['published_at']=datetime.now(timezone.utc).isoformat()
        with patch('demo.openrouter_research.fetch_page',return_value=page):
            self.assertEqual(len(await checker._verified_sources([{'url':page['url'],'quote':quote}])),1)
        page.pop('published_at')
        with patch('demo.openrouter_research.fetch_page',return_value=page):
            self.assertEqual(await checker._verified_sources([{'url':page['url'],'quote':quote}]),[])

    async def test_slow_news_provider_stops_without_guessing_or_losing_cost_reservation(self):
        cancelled = []
        async def slow_provider(*args, **kwargs):
            try:
                await asyncio.sleep(1)
            finally:
                cancelled.append(True)
        with tempfile.TemporaryDirectory() as folder:
            key = Path(folder) / 'test.key'; key.write_text('test-key')
            ledger = Path(folder) / 'ledger.jsonl'
            checker = BriefWebResearch(enabled=True, cloud_data_opt_in=True, key_path=key,
                                       ledger=CostLedger(ledger), requester=slow_provider)
            checker.completion_timeout = .01
            result = await asyncio.wait_for(checker.review('A named public figure has died.'), .5)
            self.assertEqual(cancelled, [True])
            self.assertFalse(result['source_verified'])
            self.assertEqual(result['claims'], [])
            self.assertEqual(result['cost_state'], 'unknown_reserved')
            self.assertEqual(json.loads(ledger.read_text().splitlines()[-1])['reserved_cost'], .10)


if __name__=='__main__': unittest.main()
