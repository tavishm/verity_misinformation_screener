(() => {
  "use strict";
  if (window.top !== window) return;
  const {safeSourceUrl} = FACTCHECK_GUARDS;
  const {selector, snapshot, privatePage, privatePost, eligible} = FACTCHECK_PLATFORMS;
  const states = new Map();
  let settings = {autoCheckEnabled: false, autoMode: "all", configured: false};
  let pageUrl = location.href, pendingScan = false;
  const style = `
    :host{display:block!important;margin:10px 0 4px!important;font:12px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif!important;text-align:left!important;color-scheme:light dark}
    *{box-sizing:border-box}.box{--bg:#f8fafc;--ink:#243247;--muted:#637086;--line:#dbe3ec;--link:#1d4ed8;background:var(--bg);color:var(--ink);border:1px solid var(--line);border-radius:12px;padding:10px 12px;cursor:default}
    :host([data-theme=dark]) .box{--bg:#141e2b;--ink:#e4eaf2;--muted:#a1b0c3;--line:#334155;--link:#93bbff}
    .head{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.brand{font-size:11px;font-weight:600;letter-spacing:.1px;color:var(--muted);display:flex;align-items:center;gap:5px}.brand svg{width:15px;height:15px}
    .status{font-weight:650}.status:empty{display:none}.status[data-tone=true]{color:#15803d}.status[data-tone=false]{color:#c24130}:host([data-theme=dark]) .status[data-tone=true]{color:#86dfaa}:host([data-theme=dark]) .status[data-tone=false]{color:#ffa294}
    button{font:inherit;font-weight:600;color:var(--link);background:transparent;border:1px solid var(--line);border-radius:7px;padding:4px 9px;cursor:pointer;margin-left:auto;white-space:nowrap}button:hover{background:color-mix(in srgb,var(--link) 7%,transparent)}button:focus-visible,summary:focus-visible,a:focus-visible{outline:2px solid var(--link);outline-offset:3px}button:disabled{opacity:.5;cursor:default}
    .scope{color:var(--muted);font-size:11px;margin:5px 0 0}.meter{display:inline-flex;gap:3px;align-items:center}.meter i{width:7px;height:7px;border:1px solid var(--muted);border-radius:50%}.meter i.active{background:var(--ink);border-color:var(--ink)}
    details{margin-top:8px;border-top:1px solid var(--line);padding-top:7px}summary{cursor:pointer;color:var(--link);font-weight:600}p{margin:5px 0}article{margin:12px 0 0}article+article{border-top:1px solid var(--line);padding-top:9px}.claim{font-weight:600}.verdict,.date{font-size:11px;color:var(--muted)}
    a{color:var(--link);text-decoration:underline;overflow-wrap:anywhere}blockquote{margin:6px 0 9px;padding-left:10px;border-left:2px solid var(--line);font-size:12px;white-space:pre-wrap;max-height:240px;overflow:auto}.notice{font-size:11px;color:var(--muted)}
  `;
  function element(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
  }
  async function message(payload) {
    try {
      const response = await chrome.runtime.sendMessage(payload);
      if (!response?.ok) throw {code: response?.code, message: response?.message || "Reload this page after reloading the extension."};
      return response.data;
    } catch (error) { throw {code: error?.code, message: error?.message || "Reload this page after reloading the extension."}; }
  }
  function scopeText(current) {
    return ["Text check · experimental", current.media ? "media unchecked" : "visible text only",
      current.quoted ? "quoted post excluded" : "", current.post.relation === "native_repost" ? "native repost" : "",
      current.post.relation === "crosspost" ? "crosspost context unverified" : "", current.truncated ? "expand post for full text" : ""].filter(Boolean).join(" · ");
  }
  function theme(post, host) {
    for (let node = post; node; node = node.parentElement) {
      const background = getComputedStyle(node).backgroundColor;
      const parts = background.match(/[\d.]+/g)?.map(Number);
      if (parts && parts.length >= 3 && (parts.length < 4 || parts[3] > .5)) {
        host.dataset.theme = (parts[0] * .299 + parts[1] * .587 + parts[2] * .114) < 128 ? "dark" : "light";
        return;
      }
    }
    host.dataset.theme = matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }
  function ensureBadge(post, state) {
    if (state.host?.isConnected) { theme(post, state.host); return; }
    const host = element("div"); host.dataset.evidenceCheck = "extension";
    const shadow = host.attachShadow({mode: "closed"});
    const box = element("section", undefined, "box"); box.setAttribute("aria-label", "Evidence Check text assessment");
    const head = element("div", undefined, "head"), brand = element("span", "Evidence Check", "brand");
    state.status = element("span", "", "status"); state.status.setAttribute("role", "status");
    state.meter = element("span", undefined, "meter");
    state.button = element("button", "Check text"); state.button.type = "button";
    state.button.addEventListener("click", event => {
      event.preventDefault(); event.stopPropagation();
      if (event.isTrusted) start(post, state, false);
    });
    head.append(brand, state.status, state.meter, state.button);
    state.scope = element("p", "Text check · experimental", "scope");
    state.details = element("div");
    box.append(head, state.scope, state.details);
    // Keep post-card navigation out of extension controls, including their keyboard events.
    for (const eventName of ["click", "pointerdown", "keydown"]) host.addEventListener(eventName, event => event.stopPropagation());
    shadow.append(element("style", style), box);
    // Reddit custom elements may expose only named slots. An unassigned light-
    // DOM child would be invisible; place the badge directly after that post.
    if (post.matches("shreddit-post")) post.after(host); else post.append(host);
    state.host = host;
    theme(post, host);
    // A platform may remove just our host while retaining its post container.
    state.fingerprint = null;
  }
  function clearResult(state) {
    state.details.replaceChildren(); state.meter.replaceChildren();
    state.meter.removeAttribute("aria-label"); state.status.removeAttribute("data-tone");
  }
  function invalidate(post, state, current) {
    if (state.fingerprint === current.fingerprint) return false;
    const hadCheck = Boolean(state.jobId || state.busy || state.done);
    state.epoch++; clearTimeout(state.timer); state.timer = null;
    Object.assign(state, {current, fingerprint: current.fingerprint, busy: false, done: false, jobId: null, autoTried: false, retryAt: 0});
    clearResult(state); state.scope.textContent = scopeText(current);
    state.status.textContent = hadCheck ? "Post changed · check again" : "";
    state.button.textContent = "Check text";
    state.button.disabled = current.text.length < 3 || current.text.length > 5000 || current.truncated;
    if (current.text.length < 3) state.status.textContent = "No readable claim";
    if (current.text.length > 5000) state.status.textContent = "Long post · detailed review needed";
    if (current.truncated) state.status.textContent = "Expand the post to check its text";
    return true;
  }
  function renderResult(state, result) {
    clearResult(state);
    state.status.textContent = result.label || "Unsure";
    if (result.rating && result.rating !== 3) state.status.dataset.tone = result.rating > 3 ? "true" : "false";
    if (result.rating) {
      state.meter.setAttribute("role", "img"); state.meter.setAttribute("aria-label", `${result.rating} of 5 on the truth scale`);
      state.meter.title = "1 Surely false · 2 Likely false · 3 Unsure/mixed · 4 Probably true · 5 Sure true";
      for (let i = 1; i <= 5; i++) { const dot = element("i"); if (i === result.rating) dot.className = "active"; state.meter.append(dot); }
    }
    if (!result.claims?.length && !result.reuse && !result.original_assessment) return;
    const details = element("details"); details.append(element("summary", "View evidence"));
    details.append(element("p", "This assessment covers the text below. Sources may be incomplete; an unsure result is not a false verdict.", "notice"));
    const verdicts = {supported: "Supported by cited evidence", contradicted: "Contradicted by cited evidence", insufficient_evidence: "Not enough evidence"};
    const appendClaims = (assessment, heading) => {
      if (heading) details.append(element("p", heading, "notice"));
      for (const claim of assessment.claims || []) {
      const article = element("article"); article.append(element("p", claim.text, "claim"), element("p", verdicts[claim.verdict] || verdicts.insufficient_evidence, "verdict"));
      for (const citation of claim.evidence || []) {
        const url = safeSourceUrl(citation.source_url); if (!url) continue;
        const link = element("a", citation.title || new URL(url).hostname);
        link.href = url; link.target = "_blank"; link.rel = "noopener noreferrer";
        article.append(link);
        if (citation.published_at) article.append(element("p", `Source date: ${citation.published_at}`, "date"));
        if (citation.quote) article.append(element("blockquote", citation.quote));
      }
      if (!claim.evidence?.length) article.append(element("p", "No adequate source passage in the current evidence library.", "notice"));
      details.append(article);
      }
    };
    const reuseLabels = {exact_reuse: "Matched this exact checked text", unchanged_repost: "Unchanged native repost; original assessment reused", claim_reuse: "Equivalent claim independently matched", evidence_reuse: "Earlier evidence reused for this separate check", fresh_review: "Checked for this post", abstained: "No assessment was transferred"};
    if (result.reuse) {
      const line = [reuseLabels[result.reuse.kind] || "Assessment route unavailable", result.reuse.reason].filter(Boolean).join(" · ");
      details.append(element("p", line, "notice"));
      if (result.reuse.evidence_reused) details.append(element("p", "Sources were reused; this does not extend the assessment beyond its stated text scope.", "notice"));
    }
    appendClaims(result, null);
    if (result.original_assessment) {
      details.append(element("p", "Quoted/original post assessment (separate scope)", "notice"));
      details.append(element("p", `This is about the quoted/original post only: ${result.original_assessment.label || "Unsure"}. It does not assess this post's added commentary.`, "notice"));
      appendClaims(result.original_assessment, null);
    }
    if (result.checked_at) {
      const date = new Date(result.checked_at);
      if (!Number.isNaN(date.getTime())) details.append(element("p", `Checked ${date.toLocaleString()}`, "date"));
    }
    state.details.append(details);
  }
  function live(post, state, epoch) {
    if (!post.isConnected || state.epoch !== epoch || privatePage() || privatePost(post)) return false;
    // Verify again after every async request: virtual feeds recycle post containers.
    return !invalidate(post, state, snapshot(post));
  }
  function showError(state, error) {
    state.busy = false; state.button.disabled = false;
    clearResult(state);
    state.status.textContent = error.message;
    if (["404", "FORBIDDEN"].includes(error.code)) {state.jobId = null; state.done = false; clearResult(state);}
    state.button.textContent = state.jobId && !state.done ? "Check status" : "Check text";
  }
  async function poll(post, state, epoch) {
    state.timer = null;
    if (!live(post, state, epoch) || state.polling === epoch || !state.visible || document.hidden) return;
    state.polling = epoch;
    try {
      const job = await message({type: "GET_JOB", jobId: state.jobId});
      if (!live(post, state, epoch)) return;
      if (job.status === "pending") {
        const elapsed = Date.now() - state.started;
        state.status.textContent = elapsed >= 15000 ? "Still checking…" : (job.stage || "Checking evidence…");
        if (elapsed > 120000) {
          state.status.textContent = "Review pending"; state.busy = false; state.button.disabled = false; state.button.textContent = "Check status"; return;
        }
        state.timer = setTimeout(() => poll(post, state, epoch), elapsed >= 15000 ? 3000 : 1000);
      } else {
        state.busy = false; state.button.disabled = false; state.done = true; state.lastChecked = Date.now();
        state.button.textContent = "Check again";
        if (job.status === "complete" && job.result) renderResult(state, job.result);
        else {
          clearResult(state);
          state.status.textContent = job.status === "stale" ? "Sources updated · check again" : "Needs further review";
          state.jobId = null;
          if (job.status === "stale") {state.done = false; state.autoTried = false;}
        }
      }
    } catch (error) {
      if (live(post, state, epoch)) { state.lastChecked = Date.now(); showError(state, error); }
    } finally { if (state.polling === epoch) state.polling = null; }
  }
  async function start(post, state, automatic) {
    if (privatePage() || privatePost(post) || document.hidden || !state.visible || state.busy) return;
    invalidate(post, state, snapshot(post));
    const current = state.current;
    if (automatic && (!settings.configured || !settings.autoCheckEnabled)) return;
    if (!automatic && state.jobId && !state.done) {
      state.busy = true; state.button.disabled = true; state.started = Date.now(); poll(post, state, state.epoch); return;
    }
    if (automatic) {
      if (settings.feedCaptureEnabled && state.observedFingerprint !== current.fingerprint) {
        try {
          state.tracking = await message({type: "OBSERVE_POST", post: current.post, version: current.fingerprint,
            text: current.truncated || current.text.length > 5000 ? undefined : current.text, hasMedia: current.media,
            hasQuotedContent: current.quoted, popularity: current.popularity});
          state.observedFingerprint = current.fingerprint;
        } catch (error) { state.tracking = null; }
        if (!live(post, state, state.epoch)) return;
      }
      if (state.autoTried || !eligible(current, settings.autoMode)) return;
      state.autoTried = true;
    }
    if (current.text.length < 3 || current.text.length > 5000 || current.truncated) return;
    const epoch = ++state.epoch;
    state.busy = true; state.done = false; state.jobId = null; state.started = Date.now();
    state.button.disabled = true; clearResult(state); state.status.textContent = "Checking evidence…";
    try {
      const response = await message({type: "CHECK_TEXT", text: current.text, hasMedia: current.media, auto: automatic, popularity: current.popularity, post: current.post,
        tracking: automatic ? state.tracking : null});
      if (!live(post, state, epoch)) return;
      state.jobId = response.job_id; poll(post, state, epoch);
    } catch (error) {
      if (!live(post, state, epoch)) return;
      showError(state, error);
      if (automatic && ["AUTO_BUSY", "OFFLINE", "429"].includes(error.code)) {
        state.autoTried = false; state.retryAt = Date.now() + (error.code === "OFFLINE" ? 30000 : 8000);
      }
    }
  }
  function refresh(post, state) {
    if (!state.visible || document.hidden || privatePage() || privatePost(post)) return;
    ensureBadge(post, state); invalidate(post, state, snapshot(post));
    if (state.jobId && !state.timer && (state.busy || state.done && Date.now() - state.lastChecked > 60000)) poll(post, state, state.epoch);
    else if (settings.autoCheckEnabled && !state.autoTried && !state.busy && !state.done && Date.now() >= state.retryAt) start(post, state, true);
  }
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      const state = states.get(entry.target); if (!state) continue;
      state.visible = entry.isIntersecting && entry.intersectionRatio > 0;
      clearTimeout(state.timer); state.timer = null;
      if (state.visible) refresh(entry.target, state);
    }
  }, {threshold: [0, .01]});
  function remove(post, state) {state.epoch++; clearTimeout(state.timer); state.host?.remove(); observer.unobserve(post); states.delete(post);}
  function scan() {
    pendingScan = false;
    if (privatePage()) { for (const [post, state] of states) remove(post, state); return; }
    for (const [post, state] of states) {
      if (!post.isConnected || privatePost(post)) remove(post, state);
      else if (state.visible) refresh(post, state);
    }
    for (const post of document.querySelectorAll(selector)) {
      if (states.has(post) || privatePost(post) || post.parentElement?.closest(selector)) continue;
      const state = {epoch: 0, visible: false, autoTried: false, fingerprint: null, timer: null, retryAt: 0};
      states.set(post, state); observer.observe(post);
    }
  }
  function queueScan() { if (!pendingScan) {pendingScan = true; setTimeout(scan, 150);} }
  new MutationObserver(queueScan).observe(document.body, {childList: true, subtree: true, characterData: true,
    attributes: true, attributeFilter: ["post-title", "post-type", "data-protected", "protected", "subreddit-type", "permalink", "href", "hidden", "aria-hidden", "score"]});
  chrome.runtime.onMessage.addListener(message => {
    if (message.type !== "SETTINGS_CHANGED") return;
    settings = message.settings;
    for (const state of states.values()) {state.autoTried = false; state.retryAt = 0;}
    queueScan();
  });
  document.addEventListener("visibilitychange", () => { for (const state of states.values()) {clearTimeout(state.timer); state.timer = null;} if (!document.hidden) queueScan(); });
  setInterval(() => {
    if (pageUrl !== location.href) {pageUrl = location.href; queueScan();}
    if (!document.hidden) for (const [post, state] of states) if (state.visible) refresh(post, state);
  }, 3000);
  message({type: "GET_SETTINGS"}).then(value => {settings = value; queueScan();}).catch(() => {});
  scan();
})();
