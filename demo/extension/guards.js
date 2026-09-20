/* Shared allowlists contain no credentials and do not make network requests. */
(() => {
  "use strict";
  const xHosts = new Set(["x.com", "www.x.com", "twitter.com", "www.twitter.com"]);
  const redditHosts = new Set(["reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com"]);
  const privatePaths = /^\/(?:messages?|chat|settings|prefs|account|accounts|notifications|compose|login|logout|register|mod|premium|coins|i)(?:\/|$)/i;
  function allowedPlatformUrl(value) {
    try {
      const url = new URL(value);
      if (url.protocol !== "https:" || url.port || url.username || url.password) return false;
      let path;
      try { path = decodeURIComponent(url.pathname); } catch { return false; }
      if (privatePaths.test(path)) return false;
      if (xHosts.has(url.hostname)) {
        return /^\/(?:home|explore|search)?\/?$/.test(path) ||
          /^\/[A-Za-z0-9_]{1,15}(?:\/status\/\d+)?\/?$/.test(path);
      }
      if (redditHosts.has(url.hostname)) {
        if (/\/(?:saved|hidden|upvoted|downvoted|gilded|inbox|compose|about|submit)(?:\/|$)/i.test(path)) return false;
        return path === "/" || /^\/(?:r\/[^/]+(?:\/.*)?|comments\/.*|(?:best|hot|new|top|rising)\/?)$/.test(path);
      }
    } catch { /* Invalid origins are not trusted. */ }
    return false;
  }
  function safeSourceUrl(value) {
    try {
      const url = new URL(value);
      return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password ? url.href : null;
    } catch { return null; }
  }
  globalThis.FACTCHECK_GUARDS = Object.freeze({allowedPlatformUrl, safeSourceUrl});
})();
