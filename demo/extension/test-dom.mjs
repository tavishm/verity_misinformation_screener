// Real Chrome fixture test. No user profile, extension secret, or social network request.
// Run: node demo/extension/test-dom.mjs [path-to-chrome]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {stopBrowser} from './tests/chrome.mjs';

const directory = path.dirname(fileURLToPath(import.meta.url));
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'evidence-extension-test-'));
const chrome = spawn(process.argv[2] || '/usr/bin/google-chrome', [
  '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-background-networking',
  '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0',
  `--user-data-dir=${profile}`, 'about:blank',
], {detached: true, stdio: 'ignore'});
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
let socket;
try {
  let port;
  for (let attempt = 0; attempt < 100; attempt++) {
    const filename = path.join(profile, 'DevToolsActivePort');
    if (fs.existsSync(filename)) { port = fs.readFileSync(filename, 'utf8').split('\n')[0]; break; }
    await delay(100);
  }
  assert(port, 'Disposable Chrome did not start');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => socket.addEventListener('open', resolve, {once: true}));
  let sequence = 0;
  const pending = new Map(), exceptions = [];
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, {resolve, reject});
    socket.send(JSON.stringify({id, method, params}));
  });
  const fixture = fs.readFileSync(path.join(directory, 'fixture.html')).toString('base64');
  socket.addEventListener('message', async event => {
    const data = JSON.parse(event.data);
    if (data.id) {
      const request = pending.get(data.id);
      if (request) {pending.delete(data.id); data.error ? request.reject(data.error) : request.resolve(data.result);}
    } else if (data.method === 'Fetch.requestPaused') {
      const request = data.params;
      if (request.request.url === 'https://x.com/home') {
        await send('Fetch.fulfillRequest', {requestId: request.requestId, responseCode: 200,
          responseHeaders: [{name: 'Content-Type', value: 'text/html; charset=utf-8'}], body: fixture});
      } else await send('Fetch.failRequest', {requestId: request.requestId, errorReason: 'BlockedByClient'});
    } else if (data.method === 'Runtime.exceptionThrown') exceptions.push(data.params.exceptionDetails.text);
  });
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Fetch.enable', {patterns: [{urlPattern: '*'}]});
  await send('Emulation.setDeviceMetricsOverride', {width: 1100, height: 1000, deviceScaleFactor: 1, mobile: false});
  await send('Page.navigate', {url: 'https://x.com/home'});
  await delay(200);
  const frame = (await send('Page.getFrameTree')).frameTree.frame.id;
  const {executionContextId: contextId} = await send('Page.createIsolatedWorld', {frameId: frame, worldName: 'extension-fixture-test'});
  const evaluate = async expression => {
    const result = await send('Runtime.evaluate', {expression, contextId, returnByValue: true, awaitPromise: true});
    assert(!result.exceptionDetails, JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  await evaluate(`
    globalThis.testPanels = [];
    globalThis.testMessages = [];
    globalThis.testJobMode = 'complete';
    globalThis.testClockOffset = 0;
    const originalNow = Date.now;
    Date.now = () => originalNow() + testClockOffset;
    const originalShadow = Element.prototype.attachShadow;
    Element.prototype.attachShadow = function(options) {
      const shadow = originalShadow.call(this, options);
      testPanels.push({host: this, shadow});
      return shadow;
    };
    globalThis.chrome = {
      runtime: {onMessage: {addListener: callback => globalThis.testSettingsCallback = callback}, sendMessage: async payload => {
        testMessages.push(payload);
        if (payload.type === 'GET_SETTINGS') return {ok: true, data: {autoCheckEnabled: false, configured: true, autoMode: 'all'}};
        if (payload.type === 'OBSERVE_POST') return {ok: true, data: {tracked: true, session_id: 'fixture-session', observation_id: 'fixture-observation'}};
        if (payload.type === 'CHECK_TEXT') return {ok: true, data: {job_id: 'fixture-job'}};
        if (payload.type === 'GET_JOB') {
          if (testJobMode === 'pending') {testClockOffset += 16000; return {ok: true, data: {status: 'pending', stage: 'Fixture pending'}};}
          return {ok: true, data: {status: 'complete', result: {
            label: 'Fixture result (not a verdict)', rating: null, scope: 'Text only', claims: [{
              text: 'Fixture claim', explanation: 'Fixture explanation', evidence: [{
                source_url: 'https://example.org/fixture', title: 'Fixture source', quote: 'Quoted test evidence.'
              }]
            }]
          }}};
        }
        throw new Error('Unexpected fixture message');
      }},
      storage: {onChanged: {addListener: callback => globalThis.testStorageCallback = callback}}
    };
    const media = document.createElement('div');
    media.dataset.testid = 'tweetPhoto';
    document.querySelector('article[data-testid="tweet"]').append(media);
    const quote = document.createElement('div');
    quote.dataset.testid = 'quoteTweet';
    const quotedText = document.createElement('div');
    quotedText.dataset.testid = 'tweetText';
    quotedText.textContent = 'Quoted fixture claim that is outside the primary text scope.';
    quote.append(quotedText);
    document.querySelector('article[data-testid="tweet"]').append(quote);
  `);
  await evaluate(fs.readFileSync(path.join(directory, 'guards.js'), 'utf8'));
  await evaluate(fs.readFileSync(path.join(directory, 'platforms.js'), 'utf8'));
  await evaluate(fs.readFileSync(path.join(directory, 'content.js'), 'utf8'));
  await delay(650);
  assert.equal(await evaluate('testPanels.filter(item => item.host.isConnected).length'), 3, 'Only three visible posts receive panels');
  assert.equal(await evaluate('testMessages.filter(item => item.type === "CHECK_TEXT").length'), 0, 'Automatic checking defaults off');
  assert.equal(await evaluate('testPanels[0].host.shadowRoot === null'), true, 'Panel uses closed shadow DOM');
  assert.match(await evaluate('testPanels[0].shadow.textContent'), /Evidence Check/);
  await evaluate('testPanels[0].shadow.querySelector("button").click()');
  await delay(50);
  assert.equal(await evaluate('testMessages.filter(item => item.type === "CHECK_TEXT").length'), 0, 'Synthetic page click cannot submit');
  const click = async index => {
    const rectangle = await evaluate(`(() => {const rect = testPanels[${index}].shadow.querySelector('button').getBoundingClientRect(); return {x: rect.x + rect.width/2, y: rect.y + rect.height/2};})()`);
    await send('Input.dispatchMouseEvent', {type: 'mousePressed', button: 'left', clickCount: 1, ...rectangle});
    await send('Input.dispatchMouseEvent', {type: 'mouseReleased', button: 'left', clickCount: 1, ...rectangle});
    await delay(300);
  };
  await click(0);
  assert.equal(await evaluate('testMessages.filter(item => item.type === "CHECK_TEXT").length'), 1);
  assert.equal(await evaluate('testMessages.find(item => item.type === "CHECK_TEXT").hasMedia'), true);
  assert.equal(await evaluate('testMessages.find(item => item.type === "CHECK_TEXT").text.includes("Quoted fixture claim")'), false, 'Quoted text is excluded');
  const submittedPost = await evaluate('testMessages.find(item => item.type === "CHECK_TEXT").post');
  assert.deepEqual({...submittedPost, media_fingerprint: submittedPost.media_fingerprint && 'rendered-media'}, {platform:'x',post_id:'123',post_url:'https://x.com/example/status/123',published_at:null,original_post_id:null,relation:'quote',quoted_text:'Quoted fixture claim that is outside the primary text scope.',quoted_post_id:null,quoted_post_url:null,quoted_published_at:null,has_commentary:true,media_fingerprint:'rendered-media'}, 'Only explicit rendered provenance is submitted');
  assert.match(await evaluate('testPanels[0].shadow.querySelector(".status").textContent'), /Fixture result/);
  assert.match(await evaluate('testPanels[0].shadow.querySelector(".scope").textContent'), /Text check.*media unchecked/);
  assert.equal(await evaluate('testPanels[0].shadow.querySelector("blockquote").textContent'), 'Quoted test evidence.');
  await evaluate('document.querySelector("[data-testid=tweetText]").textContent = "The edited sample event did not happen in 2024."');
  await delay(450);
  assert.equal(await evaluate('testPanels[0].shadow.querySelector(".status").textContent'), 'Post changed · check again');
  assert.equal(await evaluate('testPanels[0].shadow.querySelector("blockquote")'), null, 'Editing removes obsolete citations');
  await evaluate('testJobMode = "pending"');
  await click(0);
  assert.match(await evaluate('testPanels[0].shadow.querySelector(".status").textContent'), /Still checking/);
  await evaluate('testJobMode = "complete"; document.querySelector("[data-testid=tweetText]").textContent = "Edited again before opt-in."; testSettingsCallback({type: "SETTINGS_CHANGED", settings: {autoCheckEnabled: true, feedCaptureEnabled: true, configured: true, autoMode: "all"}})');
  await delay(550);
  assert.equal(await evaluate('testMessages.filter(item => item.type === "CHECK_TEXT" && item.auto).length'), 3, 'Opt-in submits only three visible posts');
  assert.equal(await evaluate('testMessages.some(item => item.type === "CHECK_TEXT" && item.text.includes("off-screen"))'), false);
  assert.equal(await evaluate('testMessages.filter(item => item.type === "OBSERVE_POST").length'), 3, 'Explicit feed capture records only visible post versions');
  await evaluate('window.scrollTo(0, document.body.scrollHeight)');
  await delay(550);
  assert.equal(await evaluate('testMessages.filter(item => item.type === "CHECK_TEXT" && item.text.includes("off-screen")).length'), 1, 'Newly visible post checks once');
  await evaluate('history.pushState({}, "", "/messages")');
  await delay(3250);
  assert.equal(await evaluate('document.querySelectorAll("[data-evidence-check]").length'), 0, 'Private route removes all panels');
  assert.deepEqual(exceptions, [], 'No browser script exceptions');
  console.log('PASS Chrome DOM fixture: visible-only insertion, opt-in, trusted clicks, media scope, evidence, edit invalidation, >15s pending, scrolling, private-route cleanup.');
} finally {
  await stopBrowser(chrome, socket, profile);
}
