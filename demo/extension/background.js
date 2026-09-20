"use strict";
importScripts("guards.js");
const BASE = "http://127.0.0.1:8870";
const DEFAULTS = Object.freeze({autoCheckEnabled: false, feedCaptureEnabled: false, autoMode: "all", dailyLimit: 50});
const {allowedPlatformUrl, safeSourceUrl} = FACTCHECK_GUARDS;
const uiUrls = new Set([chrome.runtime.getURL("popup.html"), chrome.runtime.getURL("options.html")]);
let admissionQueue = Promise.resolve();
// Pairing credentials are inaccessible to content scripts and page JavaScript.
const storageReady = Promise.all([
  chrome.storage.local.setAccessLevel({accessLevel: "TRUSTED_CONTEXTS"}),
  chrome.storage.session.setAccessLevel({accessLevel: "TRUSTED_CONTEXTS"})
]);
function dayKey() { const d = new Date(); return `${d.getFullYear()}-${d.getMonth()+1}-${d.getDate()}`; }
function serialized(operation) { const result = admissionQueue.then(operation, operation); admissionQueue = result.catch(() => {}); return result; }
function senderKind(sender) {
  if (sender.id !== chrome.runtime.id) return null;
  if (uiUrls.has(sender.url) && (!sender.tab || sender.tab.url === sender.url)) return "ui";
  if (sender.tab && Number.isInteger(sender.tab.id) && sender.frameId === 0 && allowedPlatformUrl(sender.url) &&
      allowedPlatformUrl(sender.tab.url) && new URL(sender.url).origin === new URL(sender.tab.url).origin) return "platform";
  return null;
}
async function prefs() {
  await storageReady;
  const {preferences = {}, usage = {}, pairing} = await chrome.storage.local.get(["preferences", "usage", "pairing"]);
  const value = {...DEFAULTS, ...preferences};
  const used = usage.day === dayKey() ? Number(usage.count) || 0 : 0;
  return {...value, configured: typeof pairing?.token === "string", autoUsed: used, autoRemaining: Math.max(0, value.dailyLimit - used)};
}
function opaqueId(prefix) { return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 12)}`; }
async function session() { return (await chrome.storage.session.get("reviewSession")).reviewSession || {jobs: {}, observations: {}, id: opaqueId("session")}; }
async function saveSession(state) {
  const keys = Object.keys(state.jobs);
  for (const key of keys.slice(0, Math.max(0, keys.length - 500))) delete state.jobs[key];
  const observations = Object.keys(state.observations || {});
  for (const key of observations.slice(0, Math.max(0, observations.length - 1000))) delete state.observations[key];
  await chrome.storage.session.set({reviewSession: state});
}
function cleanPost(raw) {
  if (!raw || !["x", "reddit"].includes(raw.platform)) return null;
  const string = (value, max) => typeof value === "string" ? value.slice(0, max) : null;
  const nullableUrl = value => { const url = safeSourceUrl(value); return url && allowedPlatformUrl(url) ? url : null; };
  const id = string(raw.post_id, 160);
  if (id && !(raw.platform === "x" ? /^\d+$/.test(id) : /^t3_[a-z0-9]+$/i.test(id))) return null;
  const relation = ["original", "native_repost", "quote", "crosspost", "unknown"].includes(raw.relation) ? raw.relation : "unknown";
  const bool = value => typeof value === "boolean" ? value : null;
  return {platform: raw.platform, post_id: id, post_url: nullableUrl(raw.post_url), published_at: string(raw.published_at, 80),
    original_post_id: string(raw.original_post_id, 160), relation, quoted_text: string(raw.quoted_text, 5000),
    quoted_post_id: string(raw.quoted_post_id, 160), quoted_post_url: nullableUrl(raw.quoted_post_url),
    quoted_published_at: string(raw.quoted_published_at, 80), has_commentary: bool(raw.has_commentary), media_fingerprint: string(raw.media_fingerprint, 160)};
}
function observationKey(tabId, message) { return `${tabId}:${message.version || ""}`; }
async function observe(message, sender) {
  const settings = await prefs();
  if (!settings.autoCheckEnabled || !settings.feedCaptureEnabled) return {tracked: false};
  const post = cleanPost(message.post);
  if (!post?.post_id) return {tracked: false};
  const key = observationKey(sender.tab.id, message);
  return serialized(async () => {
    const state = await session();
    if (state.observations?.[key]) return state.observations[key];
    const observation = {session_id: state.id, observation_id: opaqueId("observation"), post_id: post.post_id, platform: post.platform,
      ...(typeof message.text === "string" && message.text.length >= 3 && message.text.length <= 5000 ? {text: message.text} : {}),
      has_media: message.hasMedia === true, has_quoted_content: Boolean(message.hasQuotedContent), relation: post.relation,
      popularity: Number.isFinite(message.popularity) && message.popularity >= 0 ? Math.floor(message.popularity) : null, scope: "live_feed"};
    await request("/api/observations", {method: "POST", body: JSON.stringify(observation)});
    state.observations ||= {}; state.observations[key] = {tracked: true, session_id: observation.session_id, observation_id: observation.observation_id};
    await saveSession(state); return state.observations[key];
  });
}
async function broadcast() {
  const settings = await prefs();
  const tabs = await chrome.tabs.query({});
  await Promise.allSettled(tabs.filter(tab => Number.isInteger(tab.id)).map(tab =>
    chrome.tabs.sendMessage(tab.id, {type: "SETTINGS_CHANGED", settings})));
}
async function request(path, options = {}, pairingRequest = false) {
  await storageReady;
  const {pairing} = await chrome.storage.local.get("pairing");
  if (!pairingRequest && !pairing?.token) throw {code: "NOT_CONNECTED", message: "Open Evidence Check and choose Connect."};
  const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(BASE + path, {...options, signal: controller.signal, credentials: "omit", cache: "no-store", redirect: "error",
      headers: {"Content-Type": "application/json", ...(!pairingRequest ? {"X-Factcheck-Token": pairing.token} : {})}});
    if (!response.ok) {
      const messages = {401: "Reconnect using the extension popup.", 403: "Connection rejected. Open the extension and reconnect.",
        404: "This check expired. Check the post again.", 422: "This text exceeds the checker's limits.",
        429: "The checker is busy. Please try again shortly."};
      throw {code: String(response.status), message: messages[response.status] || "The checker could not complete this request."};
    }
    return await response.json();
  } catch (error) {
    if (error?.code && error?.message) throw error;
    throw {code: "OFFLINE", message: "Checker offline. Start the local service, then reconnect."};
  } finally { clearTimeout(timeout); }
}
function cleanJob(raw) {
  const string = (value, max = 1000) => typeof value === "string" ? value.slice(0, max) : "";
  const status = ["pending", "complete", "unresolved", "stale"].includes(raw.status) ? raw.status : "unresolved";
  const cleanAssessment = result => ({
    label: string(result.label, 100) || "Unsure",
    rating: Number.isInteger(result.rating) && result.rating >= 1 && result.rating <= 5 ? result.rating : null,
    scope: string(result.scope, 250) || "Text only", checked_at: string(result.checked_at, 80),
    claims: Array.isArray(result.claims) ? result.claims.slice(0, 12).map(claim => ({
      text: string(claim.text, 5000), verdict: ["supported", "contradicted", "insufficient_evidence"].includes(claim.verdict) ? claim.verdict : "insufficient_evidence",
      evidence: (Array.isArray(claim.evidence) ? claim.evidence : []).slice(0, 10).map(source => ({
        source_url: safeSourceUrl(source.source_url || source.sourceURL), title: string(source.title, 300), quote: string(source.quote, 2400), published_at: string(source.published_at || source.published_at_raw, 100)
      })).filter(source => source.source_url)
    })) : []
  });
  const result = raw.result || {}, reuse = result.reuse || {};
  return {status, stage: string(raw.stage, 160), elapsed_seconds: Number(raw.elapsed_seconds) || 0,
    error: status === "unresolved" ? "This check needs more evidence or a fuller review." : "",
    result: status === "complete" ? {...cleanAssessment(result), reuse: ["exact_reuse", "unchanged_repost", "claim_reuse", "evidence_reuse", "fresh_review", "abstained"].includes(reuse.kind) ? {
      kind: reuse.kind, reason: string(reuse.reason, 300), canonical_id: string(reuse.canonical_id, 160), event_id: string(reuse.event_id, 160), evidence_reused: reuse.evidence_reused === true, reviewed_original: reuse.reviewed_original === true
    } : null, original_assessment: result.original_assessment && typeof result.original_assessment === "object" ? cleanAssessment(result.original_assessment) : null} : null};
}
async function remember(raw, tabId, automatic, state = null) {
  if (typeof raw.job_id !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(raw.job_id)) throw {code: "INVALID_JOB", message: "The checker returned an invalid review. Try again."};
  state ||= await session();
  const entry = state.jobs[raw.job_id] || {owners: [], started: Date.now(), automatic, pending: !raw.cached};
  if (!entry.owners.includes(tabId)) entry.owners.push(tabId);
  state.jobs[raw.job_id] = entry;
  await saveSession(state);
  return {job_id: raw.job_id, cached: raw.cached === true, coalesced: raw.coalesced === true};
}
async function submit(message, sender) {
  if (typeof message.text !== "string" || message.text.trim().length < 3 || message.text.length > 5000) {
    throw {code: "INVALID_TEXT", message: "Checks need 3–5,000 characters. Long posts are never silently shortened."};
  }
  const post = cleanPost(message.post);
  if (!post) throw {code: "INVALID_POST", message: "This post's public identity could not be read safely."};
  const base = {text: message.text, has_media: message.hasMedia === true, post};
  const bodyFor = tracking => JSON.stringify({...base, ...(tracking?.tracked ? {scope: "live_feed", session_id: tracking.session_id, observation_id: tracking.observation_id} : {scope: "untracked"})});
  if (message.auto !== true) {
    const raw = await request("/api/check", {method: "POST", body: bodyFor(null)});
    return serialized(() => remember(raw, sender.tab.id, false));
  }
  return serialized(async () => {
    const settings = await prefs();
    if (!settings.autoCheckEnabled) throw {code: "AUTO_OFF", message: "Automatic checking is off."};
    if (settings.autoMode === "popular" && !(Number(message.popularity) >= (new URL(sender.url).hostname.includes("reddit") ? 1000 : 1000000))) {
      throw {code: "AUTO_INELIGIBLE", message: "This post is outside the automatic popularity setting."};
    }
    // Reuse current reviews even after today's fresh-check budget is exhausted.
    const tracking = message.tracking?.tracked === true ? message.tracking : null;
    const body = bodyFor(tracking);
    const found = await request("/api/lookup", {method: "POST", body});
    if (found.found) return remember(found, sender.tab.id, true);
    if (settings.autoRemaining === 0) throw {code: "AUTO_LIMIT", message: "Today's automatic limit is reached. Manual checks are still available."};
    const state = await session();
    for (const [id, job] of Object.entries(state.jobs)) {
      if (job.automatic && job.pending) {
        try { const value = await request(`/api/jobs/${encodeURIComponent(id)}`); job.pending = value.status === "pending"; }
        catch (error) { if (error.code === "404") job.pending = false; else throw error; }
      }
    }
    await saveSession(state);
    if (Object.values(state.jobs).filter(job => job.automatic && job.pending).length >= 2) {
      throw {code: "AUTO_BUSY", message: "Waiting for another automatic check…"};
    }
    const raw = await request("/api/check", {method: "POST", body});
    if (!raw.cached && !raw.coalesced) await chrome.storage.local.set({usage: {day: dayKey(), count: settings.autoUsed + 1}});
    return remember(raw, sender.tab.id, true, state);
  });
}
async function handle(message, sender) {
  await storageReady;
  const kind = senderKind(sender);
  if (!kind || !message || typeof message.type !== "string") throw {code: "FORBIDDEN", message: "This page cannot use the checker."};
  if (message.type === "GET_SETTINGS") return prefs();
  if (message.type === "CONNECT" && kind === "ui") {
    const value = await request("/extension/connect", {method: "POST", body: JSON.stringify({connect: true})}, true);
    if (value.base_url !== BASE || typeof value.token !== "string" || value.token.length < 16) throw {code: "PAIRING", message: "The local service returned an invalid connection."};
    await chrome.storage.local.set({pairing: {token: value.token}});
    await broadcast();
    return {connected: true}; // Never return the token to a content page or UI.
  }
  if (message.type === "DISCONNECT" && kind === "ui") {
    await chrome.storage.local.remove("pairing");
    const settings = await prefs();
    await chrome.storage.local.set({preferences: {autoCheckEnabled: false, feedCaptureEnabled: false, autoMode: settings.autoMode, dailyLimit: settings.dailyLimit}});
    await chrome.storage.session.remove("reviewSession");
    await broadcast(); return {connected: false};
  }
  if (message.type === "SET_SETTINGS" && kind === "ui") {
    const current = await prefs();
    const autoMode = message.autoMode ?? current.autoMode;
    const dailyLimit = message.dailyLimit ?? current.dailyLimit;
    if (!["all", "popular"].includes(autoMode) || ![20, 50, 100, 200].includes(dailyLimit)) throw {code: "SETTINGS", message: "Choose a valid automatic-check setting."};
    const value = {autoCheckEnabled: typeof message.autoCheckEnabled === "boolean" ? message.autoCheckEnabled : current.autoCheckEnabled,
      feedCaptureEnabled: typeof message.feedCaptureEnabled === "boolean" ? message.feedCaptureEnabled : current.feedCaptureEnabled, autoMode, dailyLimit};
    if (value.autoCheckEnabled && !current.configured) throw {code: "NOT_CONNECTED", message: "Connect the local checker first."};
    await chrome.storage.local.set({preferences: value}); await broadcast(); return prefs();
  }
  if (message.type === "GET_STATUS" && kind === "ui") {
    const settings = await prefs();
    if (!settings.configured) return {...settings, modelReady: false, connected: false};
    const stats = await request("/api/stats");
    return {...settings, connected: true, modelReady: stats.model_ready === true, documents: Number(stats.index?.documents) || 0};
  }
  if (message.type === "OPEN_DEMO" && kind === "ui") { await chrome.tabs.create({url: BASE + "/"}); return {opened: true}; }
  if (message.type === "OPEN_DASHBOARD" && kind === "ui") { await chrome.tabs.create({url: BASE + "/dashboard"}); return {opened: true}; }
  if (message.type === "OPEN_OPTIONS" && kind === "ui") { await chrome.runtime.openOptionsPage(); return {opened: true}; }
  if (message.type === "CHECK_TEXT" && kind === "platform") return submit(message, sender);
  if (message.type === "OBSERVE_POST" && kind === "platform") return observe(message, sender);
  if (message.type === "GET_JOB" && kind === "platform") {
    const id = message.jobId;
    if (typeof id !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(id) || !(await session()).jobs[id]?.owners.includes(sender.tab.id)) {
      throw {code: "FORBIDDEN", message: "This check does not belong to this tab."};
    }
    const raw = await request(`/api/jobs/${encodeURIComponent(id)}`);
    if (raw.status !== "pending") await serialized(async () => { const state = await session(); if (state.jobs[id]) state.jobs[id].pending = false; await saveSession(state); });
    return cleanJob(raw);
  }
  throw {code: "FORBIDDEN", message: "This action is unavailable from this page."};
}
chrome.runtime.onMessage.addListener((message, sender, reply) => {
  handle(message, sender).then(data => reply({ok: true, data}), error => reply({ok: false, code: error?.code || "ERROR", message: error?.message || "The check is unavailable."}));
  return true;
});
chrome.runtime.onInstalled.addListener(details => {
  if (details.reason === "install") chrome.runtime.openOptionsPage();
});
