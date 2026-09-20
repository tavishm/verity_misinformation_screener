"""Consent-only, device-scoped, ephemeral API for the Android demo."""
import asyncio
from collections import deque
import hashlib
import html
import json
import os
from pathlib import Path
import re
import secrets
import time

from fastapi import HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field, StrictBool


class PairRequest(BaseModel):
    code: str = Field(pattern=r'^\d{6}$')


class MobileRequest(BaseModel):
    text: str = Field(min_length=3, max_length=5000)
    consent: StrictBool = False
    has_media: StrictBool = False
    cloud_consent: StrictBool = False
    force_research: StrictBool = False


class MobileAPI:
    """No raw messages, queries, or results are written to disk by this API."""
    def __init__(self, reviewer, capacity, cloud_reviewer=None, quick_reviewer=None, device_store=None):
        self.reviewer, self.capacity = reviewer, capacity
        self.cloud_reviewer = cloud_reviewer
        self.quick_reviewer = quick_reviewer
        self.code, self.code_expires = '', 0
        self.device_store = Path(device_store) if device_store else None
        self.devices, self.jobs, self.tasks = self.load_devices(), {}, {}
        self.attempts = deque()
        self.reaper = None
        self.ttl = 15 * 60

    def load_devices(self):
        if self.device_store is None:
            return {}
        try:
            rows = json.loads(self.device_store.read_text())
            now = time.time()
            return {key: expiry for key, expiry in list(rows.items())[:32]
                    if re.fullmatch(r'[a-f0-9]{64}', key)
                    and isinstance(expiry, (int, float)) and now < expiry <= now + 86400}
        except (OSError, ValueError, TypeError, AttributeError):
            return {}

    def save_devices(self):
        # Persist only credential hashes and expiry. Private jobs/results remain
        # memory-only, and a server restart must not require phone interaction.
        if self.device_store is None:
            return
        self.device_store.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.device_store.with_suffix('.tmp')
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as handle:
            json.dump(self.devices, handle)
        temporary.chmod(0o600)
        temporary.replace(self.device_store)

    @staticmethod
    def fingerprint(token):
        return hashlib.sha256(token.encode()).hexdigest()

    def authorized(self, token):
        return self.devices.get(self.fingerprint(token), 0) > time.time()

    def owner(self, request):
        token = request.headers.get('X-Factcheck-Token', '')
        if not self.authorized(token):
            raise HTTPException(401, 'Pair this phone with the desktop first.')
        return self.fingerprint(token)

    def prune(self):
        now = time.time()
        active = {k: expiry for k, expiry in self.devices.items() if expiry > now}
        if active != self.devices:
            self.devices = active
            self.save_devices()
        for job_id in list(self.jobs):
            if self.jobs[job_id]['expires_at'] <= now:
                task = self.tasks.pop(job_id, None)
                if task and not task.done():
                    task.cancel()
                self.jobs.pop(job_id, None)

    async def cleanup_loop(self):
        while True:
            await asyncio.sleep(30)
            self.prune()

    async def process(self, job_id, payload):
        started = time.perf_counter()
        job = self.jobs.get(job_id)
        # A user can delete a just-created check before this task is scheduled.
        # In that case there is no private payload left to process.
        if job is None:
            return
        try:
            # Basic facts must not queue behind long web investigations.
            if self.quick_reviewer is not None and not payload.force_research:
                result = await self.quick_reviewer(payload.text, payload.has_media, lambda message: job.update(stage=message))
                if result is not None:
                    if self.jobs.get(job_id) is job:
                        job.update(status='complete', stage='Complete', result=result)
                    return
            # The published deadline covers queueing for the scarce research
            # slot as well as the review itself.  Otherwise eight queued jobs
            # could outlive the promised 180 seconds by many minutes.
            await asyncio.wait_for(self.capacity.acquire(), timeout=180)
            try:
                remaining = max(0.01, 180 - (time.perf_counter() - started))
                stage = lambda message: job.update(stage=message)
                if payload.cloud_consent:
                    if self.cloud_reviewer is None:
                        raise ValueError('Cloud research is not configured.')
                    review = self.cloud_reviewer(payload.text, stage, job['owner'][:16])
                else:
                    review = self.reviewer(payload.text, payload.has_media, stage)
                result = await asyncio.wait_for(review, timeout=remaining)
            finally:
                self.capacity.release()
            if self.jobs.get(job_id) is not job:
                return
            job.update(status='complete', stage='Complete', result=result)
        except asyncio.CancelledError:
            raise
        except Exception:
            # Do not log exception payloads: library exceptions may contain text.
            job.update(status='unresolved', stage='Could not finish this check',
                error='No verdict was published. Please try again or consult the original sources.')
        finally:
            if self.jobs.get(job_id) is job:
                job['elapsed_seconds'] = round(time.perf_counter() - started, 3)
            self.tasks.pop(job_id, None)

    def install(self, app):
        @app.on_event('startup')
        async def start_mobile():
            self.reaper = asyncio.create_task(self.cleanup_loop())

        @app.on_event('shutdown')
        async def stop_mobile():
            pending = list(self.tasks.values()) + ([self.reaper] if self.reaper else [])
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)
            self.jobs.clear()
            self.devices.clear()

        @app.get('/mobile')
        async def pairing_page():
            if self.code_expires <= time.time():
                self.code = f'{secrets.randbelow(1000000):06d}'
                self.code_expires = time.time() + 600
            page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>Forward Check · pair your phone</title><style>body{font:20px system-ui;background:#f5f4ef;color:#183d35;max-width:700px;margin:8vh auto;padding:24px}h1{font-size:42px}code{display:block;letter-spacing:.22em;font-size:64px;background:white;padding:24px;border-radius:18px}li{margin:18px 0}small{line-height:1.6}</style>
            <h1>Forward Check</h1><ol><li>Open Forward Check on the USB-connected Android phone.</li><li>Open Settings → Connect to computer (or Connect again) and enter:</li></ol><code>CODE</code><p>This code works once and expires after ten minutes. Reload for a new code after connecting.</p>
            <ol start="3"><li>Enable the forwarded-message helper in Android Accessibility settings.</li><li>Open a WhatsApp chat containing a forwarded test message. Review the prompt, then approve the selected text.</li></ol>
            <small>The phone first looks for claims and scams. Nothing is sent until you tap Yes, check. Simple facts get a quick AI answer from your server. News and harder questions use DeepSeek V4.1 Flash through OpenRouter to check online. Text and results are kept in server memory for up to 15 minutes. You can also use Check a message in the app.</small></html>'''
            return HTMLResponse(page.replace('CODE', html.escape(self.code)), headers={'Cache-Control': 'no-store',
                'X-Frame-Options': 'DENY', 'Referrer-Policy': 'no-referrer', 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'"})

        @app.post('/api/mobile/pair')
        async def pair(payload: PairRequest, request: Request):
            # Native client has no Origin. Browsers may pair only from this UI.
            origin = request.headers.get('origin')
            if origin and origin not in ('http://127.0.0.1:8870', 'http://localhost:8870'):
                raise HTTPException(403, 'Pair from the Android app.')
            now = time.time()
            while self.attempts and self.attempts[0] < now - 60:
                self.attempts.popleft()
            if len(self.attempts) >= 6:
                raise HTTPException(429, 'Wait a minute before trying another code.')
            self.attempts.append(now)
            if now >= self.code_expires or not secrets.compare_digest(payload.code, self.code):
                raise HTTPException(403, 'The code is incorrect, expired, or already used.')
            self.code, self.code_expires = '', 0
            token = secrets.token_urlsafe(32)
            self.prune()
            if len(self.devices) >= 32:
                raise HTTPException(429, 'The demo has reached its paired-phone limit.')
            self.devices[self.fingerprint(token)] = now + 24 * 3600
            self.save_devices()
            return JSONResponse({'token': token, 'expires_in_seconds': 86400}, headers={'Cache-Control': 'no-store'})

        @app.post('/api/mobile/check')
        async def check(payload: MobileRequest, request: Request):
            owner = self.owner(request)
            if payload.consent is not True:
                raise HTTPException(403, 'Explicit approval is required for each message.')
            if not payload.text.strip():
                raise HTTPException(422, 'A blank message cannot be checked.')
            self.prune()
            if sum(j['status'] == 'pending' for j in self.jobs.values()) >= 8:
                raise HTTPException(429, 'The demo is busy. Try again shortly.')
            if len(self.jobs) >= 64:
                old = next((k for k, j in self.jobs.items() if j['status'] != 'pending'), None)
                if old:
                    self.jobs.pop(old, None)
            job_id = secrets.token_urlsafe(24)
            self.jobs[job_id] = {'job_id': job_id, 'owner': owner, 'status': 'pending',
                'stage': 'Checking…', 'expires_at': time.time() + self.ttl}
            self.tasks[job_id] = asyncio.create_task(self.process(job_id, payload))
            return JSONResponse({'job_id': job_id, 'status': 'pending'}, headers={'Cache-Control': 'no-store'})

        @app.get('/api/mobile/jobs/{job_id}')
        async def get_job(job_id: str, request: Request):
            owner = self.owner(request)
            self.prune()
            job = self.jobs.get(job_id)
            if not job or job['owner'] != owner:
                raise HTTPException(404, 'This check has expired or belongs to another phone.')
            return JSONResponse({k: v for k, v in job.items() if k != 'owner'}, headers={'Cache-Control': 'no-store'})

        @app.delete('/api/mobile/jobs/{job_id}')
        async def forget_job(job_id: str, request: Request):
            owner = self.owner(request)
            job = self.jobs.get(job_id)
            if job and job['owner'] == owner:
                task = self.tasks.pop(job_id, None)
                if task:
                    task.cancel()
                self.jobs.pop(job_id, None)
            return JSONResponse({'forgotten': True}, headers={'Cache-Control': 'no-store'})
