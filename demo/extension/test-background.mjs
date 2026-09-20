import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import {fileURLToPath} from "node:url";
const directory = path.dirname(fileURLToPath(import.meta.url));
const testToken = "test-only-token-never-a-real-secret", extensionId = "a".repeat(32);
const platform = (url = "https://x.com/home", tab = 1) => ({id: extensionId, url, frameId: 0, tab: {id: tab, url}});
const popup = {id: extensionId, url: `chrome-extension://${extensionId}/popup.html`};
const post = {platform: "x", post_id: "123", post_url: "https://x.com/example/status/123", published_at: null, original_post_id: null, relation: "original", quoted_text: null, quoted_post_id: null, quoted_post_url: null, quoted_published_at: null, has_commentary: null, media_fingerprint: null};
function harness({configured = true, localData = {}, sessionData = {}, pending = false, responseStatus = 200} = {}) {
  if (configured && !localData.pairing) localData.pairing = {token: testToken};
  const requests = [], listeners = [], accesses = [], remoteJobs = new Map();
  let number = 0, cached = null;
  const store = data => ({
    async get(keys) {return Object.fromEntries((Array.isArray(keys) ? keys : [keys]).map(key => [key, structuredClone(data[key])]));},
    async set(value) {Object.assign(data, structuredClone(value));},
    async remove(key) {delete data[key];}, async setAccessLevel(value) {accesses.push(value.accessLevel);}
  });
  const context = vm.createContext({URL, console, setTimeout, clearTimeout, AbortController,
    chrome: {runtime: {id: extensionId, getURL: value => `chrome-extension://${extensionId}/${value}`,
      onMessage: {addListener: listener => listeners.push(listener)}, onInstalled: {addListener() {}}, async openOptionsPage() {}},
      storage: {local: store(localData), session: store(sessionData)}, tabs: {async query() {return [{id: 1}, {id: 2}];}, async sendMessage() {}, async create() {}}},
    async fetch(url, options) {
      requests.push({url, options}); let result;
      if (url.endsWith("/extension/connect")) result = {token: testToken, base_url: "http://127.0.0.1:8870"};
      else if (url.endsWith("/api/lookup")) result = cached ? {found: true, job_id: cached, cached: true} : {found: false};
      else if (url.endsWith("/api/check")) {
        result = {job_id: `job_${++number}`, cached: false};
        remoteJobs.set(result.job_id, {status: pending ? "pending" : "complete", result: {label: "Probably true", rating: 4,
          claims: [{text: "Example claim", verdict: "supported", explanation: "Must not be rendered", evidence: [
            {source_url: "javascript:alert(1)", quote: "unsafe"},
            {source_url: "https://example.org/source", quote: "Original words", title: "Source"}]}]}});
      } else if (url.includes("/api/jobs/")) result = remoteJobs.get(url.split("/").at(-1)) || {status: "complete", result: {claims: []}};
      else result = {model_ready: true, index: {documents: 30}, token: testToken};
      return {ok: responseStatus === 200, status: responseStatus, async json() {return result;}};
    }
  });
  context.importScripts = (...names) => names.forEach(name => vm.runInContext(fs.readFileSync(path.join(directory, name), "utf8"), context));
  vm.runInContext(fs.readFileSync(path.join(directory, "background.js"), "utf8"), context);
  const send = (message, sender = platform()) => new Promise(resolve => listeners[0](message.type === "CHECK_TEXT" ? {...message, post: message.post || post} : message, sender, value => resolve(structuredClone(value))));
  return {send, requests, localData, sessionData, accesses, context, cache: id => {cached = id;}};
}
let passed = 0;
async function test(name, fn) {await fn(); passed++; console.log(`PASS ${name}`);}
await test("opt-in, trusted storage, no leaked credentials", async () => {
  const h = harness();
  const settings = (await h.send({type: "GET_SETTINGS"})).data;
  assert.equal(settings.autoCheckEnabled, false); assert.equal(settings.autoRemaining, 50);
  assert.deepEqual(h.accesses, ["TRUSTED_CONTEXTS", "TRUSTED_CONTEXTS"]);
  assert.equal((await h.send({type: "CHECK_TEXT", text: "Example", auto: true})).code, "AUTO_OFF");
  assert.equal(h.requests.length, 0);
  const status = await h.send({type: "GET_STATUS"}, popup);
  assert.equal(status.data.documents, 30); assert(!JSON.stringify(status).includes(testToken));
  assert.equal(h.requests[0].options.headers["X-Factcheck-Token"], testToken);
  assert.equal(h.requests[0].options.redirect, "error");
});
await test("pairing and settings are restricted to extension UI", async () => {
  const h = harness({configured: false});
  assert.equal((await h.send({type: "CHECK_TEXT", text: "Example"})).code, "NOT_CONNECTED");
  for (const type of ["CONNECT", "DISCONNECT", "SET_SETTINGS", "OPEN_DEMO", "GET_STATUS"])
    assert.equal((await h.send({type})).code, "FORBIDDEN");
  assert.equal(h.requests.length, 0);
  assert.equal((await h.send({type: "CONNECT"}, popup)).ok, true);
  assert.equal(h.requests[0].options.headers["X-Factcheck-Token"], undefined);
  assert.equal((await h.send({type: "GET_SETTINGS"})).data.configured, true);
  assert.equal((await h.send({type: "SET_SETTINGS", dailyLimit: -1}, popup)).code, "SETTINGS");
  assert.equal((await h.send({type: "DISCONNECT"}, popup)).ok, true);
  assert.equal((await h.send({type: "GET_SETTINGS"})).data.configured, false);
});
await test("private paths, subframes and foreign senders denied", async () => {
  const h = harness();
  for (const sender of [platform("https://x.com/messages/123"), platform("https://x.com/i/chat"),
    platform("https://www.reddit.com/message/inbox"), platform("https://www.reddit.com/user/a/saved"),
    platform("https://x.com.evil.example/home"), platform("https://evil.example"),
    {...platform(), frameId: 1}, {...platform(), id: "other"}, {...platform(), tab: {id: 1, url: "https://x.com/messages"}}])
    assert.equal((await h.send({type: "CHECK_TEXT", text: "Example"}, sender)).code, "FORBIDDEN");
  assert.equal(h.requests.length, 0);
});
await test("daily budget is atomic across tabs, survives worker restart, permits cached and manual checks", async () => {
  const h = harness();
  await h.send({type: "SET_SETTINGS", autoCheckEnabled: true, dailyLimit: 20}, popup);
  const values = await Promise.all(Array.from({length: 23}, (_, i) => h.send({type: "CHECK_TEXT", text: `Example ${i}`, auto: true}, platform("https://www.reddit.com/r/news/", i % 2 + 1))));
  assert.equal(values.filter(x => x.ok).length, 20); assert.equal(values.filter(x => x.code === "AUTO_LIMIT").length, 3);
  const restarted = harness({localData: h.localData, sessionData: h.sessionData});
  assert.equal((await restarted.send({type: "GET_SETTINGS"})).data.autoRemaining, 0);
  restarted.cache("job_1");
  assert.equal((await restarted.send({type: "CHECK_TEXT", text: "Cached claim", auto: true})).data.cached, true);
  assert.equal((await restarted.send({type: "CHECK_TEXT", text: "Manual check"})).ok, true);
  assert.equal((await restarted.send({type: "GET_SETTINGS"})).data.autoUsed, 20);
  h.localData.usage.day = "2000-1-1";
  assert.equal((await h.send({type: "GET_SETTINGS"})).data.autoRemaining, 20);
});
await test("at most two automatic jobs pending and popularity filtering", async () => {
  const h = harness({pending: true});
  await h.send({type: "SET_SETTINGS", autoCheckEnabled: true, autoMode: "popular"}, popup);
  assert.equal((await h.send({type: "CHECK_TEXT", text: "Low views", auto: true, popularity: 999999})).code, "AUTO_INELIGIBLE");
  const responses = await Promise.all([1,2,3,4].map(i => h.send({type: "CHECK_TEXT", text: `Claim ${i}`, auto: true, popularity: 1000000})));
  assert.equal(responses.filter(x => x.ok).length, 2);
  assert.equal(responses.filter(x => x.code === "AUTO_BUSY").length, 2);
  assert.equal((await h.send({type: "GET_SETTINGS"})).data.autoUsed, 2);
});
await test("job ownership, text limits, and safe original-source rendering", async () => {
  const h = harness();
  assert.equal((await h.send({type: "CHECK_TEXT", text: "x".repeat(5001)})).code, "INVALID_TEXT");
  const response = await h.send({type: "CHECK_TEXT", text: "Example", hasMedia: true});
  assert.deepEqual(JSON.parse(h.requests[0].options.body), {text: "Example", has_media: true, post, scope: "untracked"});
  const jobId = response.data.job_id;
  assert.equal((await h.send({type: "GET_JOB", jobId}, platform("https://x.com/home", 2))).code, "FORBIDDEN");
  assert.equal((await h.send({type: "GET_JOB", jobId: "../../api/stats"})).code, "FORBIDDEN");
  const result = (await h.send({type: "GET_JOB", jobId})).data.result;
  assert.equal(result.claims[0].evidence.length, 1); assert.equal(result.claims[0].evidence[0].quote, "Original words");
  assert.equal(result.claims[0].explanation, undefined);
});
await test("queue failure doesn't consume automatic budget", async () => {
  const h = harness({responseStatus: 429});
  await h.send({type: "SET_SETTINGS", autoCheckEnabled: true}, popup);
  assert.equal((await h.send({type: "CHECK_TEXT", text: "Example", auto: true})).code, "429");
  assert.equal((await h.send({type: "GET_SETTINGS"})).data.autoUsed, 0);
});
await test("public post metadata and feed observations are opt-in, bounded, and deduplicated", async () => {
  const h = harness();
  const hidden = await h.send({type: "OBSERVE_POST", post, version: "one", text: "Visible claim", hasMedia: false, hasQuotedContent: false, popularity: 4});
  assert.equal(hidden.data.tracked, false); assert.equal(h.requests.length, 0);
  await h.send({type: "SET_SETTINGS", autoCheckEnabled: true, feedCaptureEnabled: true}, popup);
  const observed = await h.send({type: "OBSERVE_POST", post, version: "one", text: "Visible claim", hasMedia: true, hasQuotedContent: true, popularity: 4});
  assert.equal(observed.data.tracked, true);
  const observation = JSON.parse(h.requests.at(-1).options.body);
  assert.deepEqual({...observation, session_id: "redacted", observation_id: "redacted"}, {session_id: "redacted", observation_id: "redacted", post_id: "123", platform: "x", text: "Visible claim", has_media: true, has_quoted_content: true, relation: "original", popularity: 4, scope: "live_feed"});
  await h.send({type: "OBSERVE_POST", post, version: "one", text: "Visible claim", hasMedia: true, hasQuotedContent: true, popularity: 4});
  assert.equal(h.requests.filter(request => request.url.endsWith("/api/observations")).length, 1);
  const tracked = await h.send({type: "CHECK_TEXT", text: "Example", auto: true, tracking: observed.data});
  assert(tracked.ok); assert.equal(JSON.parse(h.requests.at(-1).options.body).scope, "live_feed");
});
await test("supported URL routes and source URL validation", async () => {
  const guard = harness().context.FACTCHECK_GUARDS;
  for (const url of ["https://x.com/home", "https://twitter.com/person/status/123", "https://old.reddit.com/r/india/", "https://www.reddit.com/r/news/comments/abc/title/"]) assert(guard.allowedPlatformUrl(url));
  for (const url of ["http://x.com/home", "https://x.com:8443/home", "https://x.com/%6dessages", "https://reddit.com/settings/", "https://reddit.com/r/news/about/moderators/", "https://x.com/i/bookmarks"]) assert(!guard.allowedPlatformUrl(url));
  assert.equal(guard.safeSourceUrl("https://secret@example.com"), null);
});
console.log(`${passed} extension bridge checks passed.`);
