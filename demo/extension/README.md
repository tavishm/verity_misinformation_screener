# Evidence Check · Chrome extension 0.3.0

Adds a **Check text** button and evidence-backed text assessment beneath visible public posts on X/Twitter and Reddit. Supports the current Reddit custom-element markup, classic Reddit and old Reddit. English and Hindi text goes to the local checker.

## Install

1. Start the prepared backend by running `./start-checker.sh` in the project folder. It uses the local machine and the connected GPU server named `big`.
2. Open `chrome://extensions`, turn on **Developer mode**, click **Load unpacked**, and select the extracted `evidence-check-0.3.0` folder containing `manifest.json`. Developers can load `demo/extension` directly.
3. Choose **Connect local checker** on the setup page. Pin the extension, then refresh your X or Reddit tab.
4. Click **Check text** under a visible public post. Open **View evidence** to read the original source passages and follow their links.

The ZIP must be extracted first. This is an unpacked extension, not a Chrome Web Store publication. It requires the prepared local backend; the ZIP alone does not include a model or web research service. No API key or pairing token is included in the package.

## Automatic checking

Open the popup and enable **Check as I scroll**. It starts off. Choose all visible posts or popular posts only (X: at least 1 million displayed views; Reddit: at least 1,000 displayed votes). If a count is unavailable, popular-only mode skips that post. Manual checking remains available.

The daily allowance is shared across tabs: 20, 50 (default), 100 or 200 new automatic checks, reset by the browser's local date. Cached checks and duplicate in-flight checks do not use another allowance. At most two automatic jobs are pending at once. This browser allowance controls the demo workload; it is not a server-wide paid-spend cap or a production abuse-control system.

**Record feed exposure** is a separate, off-by-default popup control. When it and automatic checking are enabled, the extension records each visible post version once per browser tab session with the local demo dashboard. It does not crawl hidden posts or report manual checks. The dashboard button opens the local `/dashboard` route.

## What a rating means

The scale runs from 1 (surely false) to 5 (sure true), with 3 for unsure/mixed. This experimental backend publishes only levels 2–4; it reserves definitive ratings pending stronger validation. Personal opinion can return “No factual claim,” without a rating. Missing evidence is not treated as false.

Ratings cover the displayed original text claims, not an entire post. Attached images/videos, quoted posts and linked articles are not authenticated. A native unchanged repost can show its original assessment; a quote's original assessment is rendered separately and never rates its wrapper commentary. Expand a visibly truncated post before checking it. Posts over 5,000 characters need a fuller review and are never silently shortened. Source excerpts appear as plain text; generated model explanations are not displayed.

The current backend searches 30 source documents, not the whole web. It cannot establish 30–50% real-feed coverage. Many claims will be unsure. No promise of a result within 15 seconds is made; longer checks remain visibly pending. Checks continue server-side when you scroll away, and the result is retrieved when you return. This version has no user completion-email feature.

## Data and connection

The extension reads eligible rendered posts, without crawling hidden feeds or using platform API keys. A check includes only publicly rendered post identity/time and explicit repost/quote/crosspost markers when available; unknown provenance remains unknown. Quote text is separately scoped. Choosing a check sends the text, text/media scope and this bounded metadata to `http://127.0.0.1:8870`; the backend forwards inference to the prepared GPU server. No account password, cookie, author identity or email address is submitted in the check request.

Use public feeds. Message, chat and settings routes are excluded; recognizable protected/private containers are skipped. Platforms do not reliably expose all restricted-content indicators, so this is not a guarantee of detecting every private post.

Pairing happens through the extension popup/setup page. Credentials stay in storage restricted to trusted extension contexts; page scripts and content scripts do not receive them. The service worker accepts only fixed API routes, checks sender origins, and scopes review IDs to the submitting tab. Changes to text, media scope or post identity clear old badges. Source changes make completed reviews stale; visible completed reviews are refreshed approximately once a minute.

## Troubleshooting

- **Checker offline:** run `./start-checker.sh` in the project folder, confirm `big` is online, and choose **Connect** again.
- **No buttons after installation/update:** refresh the feed tab. Unsupported or restricted layouts may not be read; use **Open checker** to paste text instead.
- **Unsure / needs further review:** the library or review is insufficient. This is not a false verdict.
- **Stopped after a reboot:** rerun the launcher. It starts a background service for this login session, not an enabled boot service.
- **Disconnect:** use **Setup & details → Disconnect & turn off auto-check**.

## Development checks

From the project root:

```bash
node demo/extension/test-background.mjs
node demo/extension/test-dom.mjs
python3 -m unittest demo.test_extension_api demo.test_evidence demo.test_qualifiers
python3 demo/build_extension.py
node demo/extension/test-e2e.mjs /path/to/chrome-for-testing dist/evidence-check-0.3.0
```

The DOM test injects scripts with mocked messaging. The end-to-end test loads the actual extension and uses the actual local backend and GPU model. Social pages are locally served layout fixtures, not authenticated live feeds. Development-case outcomes are not an accuracy or coverage benchmark. Chrome for Testing or Chromium is required for command-line unpacked-extension loading in the end-to-end test; ordinary Chrome supports manual **Load unpacked**.
