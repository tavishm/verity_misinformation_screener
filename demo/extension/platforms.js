/* Read visible public post markup only; no platform API calls or page-world scripts. */
(() => {
  "use strict";
  const selector = 'article[data-testid="tweet"], shreddit-post, [data-testid="post-container"], .thing.link';
  const visibleText = node => node && node.getClientRects().length && getComputedStyle(node).visibility !== "hidden" && !node.closest('[hidden],[aria-hidden="true"]') ? (node.innerText || "").trim() : "";
  const unique = parts => [...new Set(parts.filter(Boolean))].join("\n\n");
  const nullable = value => value || null;
  function count(value) { const m = value == null ? null : String(value).replace(/,/g, "").match(/(?:^|\s)(\d+(?:\.\d+)?)\s*([KMB])?(?:\s|$)/i); return m ? Math.round(Number(m[1]) * ({K:1e3,M:1e6,B:1e9}[m[2]?.toUpperCase()] || 1)) : null; }
  function cleanUrl(value) { if (typeof value !== "string" || !value) return null; try { const url = new URL(value, location.origin); url.search = ""; url.hash = ""; return url.href; } catch { return null; } }
  function xId(url) { try { return new URL(url).pathname.match(/^\/[A-Za-z0-9_]{1,15}\/status\/(\d+)(?:\/|$)/)?.[1] || null; } catch { return null; } }
  function redditUrlId(url) { try { const id = new URL(url).pathname.match(/\/comments\/([a-z0-9]+)(?:\/|$)/i)?.[1]; return id ? `t3_${id.toLowerCase()}` : null; } catch { return null; } }
  function redditId(post, url) {
    const full = post.getAttribute("data-fullname") || post.getAttribute("post-fullname") || post.getAttribute("post-id") || post.getAttribute("id") || "";
    if (/^t3_[a-z0-9]+$/i.test(full)) return full.toLowerCase();
    return redditUrlId(url);
  }
  function isoTime(node, post) {
    const value = node?.getAttribute("datetime") || post.getAttribute("created-at") || post.getAttribute("created-timestamp") || "";
    // Numeric attributes have platform-specific units, so never infer them.
    if (!value || /^\d+(?:\.\d+)?$/.test(value)) return null;
    const date = new Date(value); return Number.isNaN(date.getTime()) ? null : date.toISOString();
  }
  function mediaFingerprint(post) {
    const nodes = [...post.querySelectorAll('[data-testid="tweetPhoto"], [data-testid="videoPlayer"], video, shreddit-player, a[href*="/photo/"], a[href*="/video/"], img[src*="preview.redd.it"], img[src*="i.redd.it"], a.thumbnail img')].filter(n => n.closest(selector) === post);
    const inputs = nodes.map(n => n.currentSrc || n.src || n.href || n.getAttribute("poster") || n.getAttribute("data-testid") || "").filter(Boolean).sort();
    if (!inputs.length) return null;
    let hash = 2166136261; for (const char of inputs.join("\n")) {hash ^= char.charCodeAt(0); hash = Math.imul(hash, 16777619);}
    return `dom-fnv1a-${(hash >>> 0).toString(16)}`;
  }
  function postMetadata(post, platform, url, relation, quotedText, quotedUrl, originalId, hasCommentary) {
    const id = platform === "x" ? xId(url) : redditId(post, url);
    const quotedId = platform === "x" ? xId(quotedUrl) : redditId(post, quotedUrl);
    const quoteTime = post.querySelector('[data-testid="quoteTweet"] time, [data-testid="quotedTweet"] time, [data-testid="crosspost"] time');
    return {platform, post_id: nullable(id), post_url: nullable(url), published_at: isoTime(post.querySelector("time"), post), original_post_id: nullable(originalId), relation, quoted_text: nullable(quotedText), quoted_post_id: nullable(quotedId), quoted_post_url: nullable(quotedUrl), quoted_published_at: isoTime(quoteTime, post), has_commentary: hasCommentary, media_fingerprint: mediaFingerprint(post)};
  }
  function snapshot(post) {
    let body = "", url = null, platform, popularity = null, quoted = false, truncated = false, metadata;
    const owned = query => [...post.querySelectorAll(query)].filter(node => node.closest(selector) === post);
    if (post.matches('article[data-testid="tweet"]')) {
      platform = "x"; const quote = post.querySelector('[data-testid="quoteTweet"], [data-testid="quotedTweet"]');
      const texts = owned('[data-testid="tweetText"]'), primary = texts.find(n => !n.closest('[data-testid="quoteTweet"], [data-testid="quotedTweet"]'));
      body = visibleText(primary); quoted = Boolean(quote);
      const ownStatus = owned('a[href*="/status/"]').find(n => !n.closest('[data-testid="quoteTweet"], [data-testid="quotedTweet"]'));
      url = cleanUrl(ownStatus?.href);
      const quoteUrl = cleanUrl(quote?.querySelector('a[href*="/status/"]')?.href), quoteText = visibleText(quote?.querySelector('[data-testid="tweetText"]'));
      const nativeRepost = !quoted && /\brepost(?:ed)?\b/i.test(visibleText(post.querySelector('[data-testid="socialContext"]'))) && Boolean(xId(url));
      const relation = quoted ? "quote" : nativeRepost ? "native_repost" : url ? "original" : "unknown";
      metadata = postMetadata(post, platform, url, relation, quoteText, quoteUrl, nativeRepost ? xId(url) : null, quoted ? body.length > 0 : nativeRepost ? false : null);
      const views = post.querySelector('a[href$="/analytics"], a[href*="/analytics?"], [data-testid="app-text-transition-container"][aria-label*="views"]'); popularity = count(views?.getAttribute("aria-label")) ?? count(visibleText(views));
      truncated = Boolean(post.querySelector('[data-testid="tweet-text-show-more-link"]'));
    } else if (post.matches('.thing.link')) {
      platform = "reddit"; body = unique([visibleText(post.querySelector("a.title")), visibleText(post.querySelector(".expando .usertext-body"))]); url = cleanUrl(post.querySelector("a.comments")?.href);
      const crosspost = post.classList.contains("crosspost") || post.hasAttribute("data-crosspost-root"), originalUrl = cleanUrl(post.querySelector('[data-crosspost-root] a.comments, .crosspost a.comments')?.href);
      metadata = postMetadata(post, platform, url, crosspost ? "crosspost" : url ? "original" : "unknown", null, originalUrl, crosspost ? redditUrlId(originalUrl) : null, crosspost ? body.length > 0 : null);
      popularity = count(post.getAttribute("data-score")) ?? count(post.querySelector(".score.unvoted")?.getAttribute("title")) ?? count(visibleText(post.querySelector(".score.unvoted")));
    } else {
      platform = "reddit"; const title = visibleText(owned('[slot="title"], [data-testid="post-title"], h1, h3')[0]) || post.getAttribute("post-title") || "";
      const bodyNodes = owned('[slot="text-body"], [data-testid="post-content"], [data-click-id="text"]'); body = unique([title, ...bodyNodes.filter(n => !n.parentElement?.closest('[slot="text-body"], [data-testid="post-content"], [data-click-id="text"]')).map(visibleText)]);
      url = cleanUrl(post.getAttribute("permalink") || post.querySelector('a[slot="comments-button"], a[data-click-id="comments"], a[href*="/comments/"]')?.href);
      const crosspost = post.getAttribute("post-type") === "crosspost" || post.hasAttribute("crosspost-parent-post-id"), originalUrl = cleanUrl(post.getAttribute("crosspost-parent-permalink") || post.querySelector('[slot="crosspost"], [data-testid="crosspost"]')?.querySelector('a[href*="/comments/"]')?.href);
      metadata = postMetadata(post, platform, url, crosspost ? "crosspost" : url ? "original" : "unknown", null, originalUrl, post.getAttribute("crosspost-parent-post-id") || (crosspost ? redditUrlId(originalUrl) : null), crosspost ? body.length > 0 : null);
      popularity = count(post.getAttribute("score")) ?? count(post.getAttribute("data-score")); truncated = Boolean(post.querySelector('[data-testid="post-text-read-more"], [slot="text-body"] [data-clamped="true"]'));
    }
    const media = /^(image|video|gallery|gif)$/i.test(post.getAttribute("post-type") || "") || Boolean(metadata.media_fingerprint);
    const identity = metadata.post_id || url || post.getAttribute("id") || post.getAttribute("data-fullname") || "";
    return {text: body.trim(), media, quoted, truncated, platform, popularity, url, identity, post: metadata, fingerprint: JSON.stringify([identity, body.trim(), media, quoted, truncated, metadata])};
  }
  function privatePage() { return !FACTCHECK_GUARDS.allowedPlatformUrl(location.href) || Boolean(document.querySelector('shreddit-subreddit-header[community-type="private"], [data-community-type="private"], [data-subreddit-type="private"]')); }
  function privatePost(post) { return Boolean(post.matches('[protected="true"], [data-protected="true"], [subreddit-type="private"]') || post.querySelector('[data-testid="icon-lock"], [aria-label="Protected account"], [aria-label="Protected Tweets"]')); }
  function eligible(value, mode) { return mode !== "popular" || (value.popularity !== null && value.popularity >= (value.platform === "x" ? 1000000 : 1000)); }
  globalThis.FACTCHECK_PLATFORMS = Object.freeze({selector, snapshot, privatePage, privatePost, eligible, count});
})();
