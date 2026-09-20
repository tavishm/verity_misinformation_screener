# Verity validation

## Android update — 20 September 2026

The combined Android source passed **209 host tests**, including 24 message
tracker tests and seven new regressions for repeated pictures, retained choices,
old-state migration and messages arriving beside a greeting. The APK builds,
signature verification passes, and native libraries pass 16 KiB alignment.

The source-only publication snapshot also passed **115 Python tests** and all
four standalone Java feature checks from a clean copy of the staged files,
without private caches, phone records or the oversized contextual model.

The owner confirmed English SMS warnings and persistent Ignore behavior on the
Samsung demo phone, then confirmed the Hindi and unsaved RCS profile-name fixes.
The latest picture-resend change still needs its live phone retest. Automated
sequence tests do not establish reliable extraction on every WhatsApp version.

## Earlier v0.3 evidence prototype — 19 September 2026

This is a local, experimental text-evidence prototype. The tests below establish implemented behavior within their stated scope. They do not establish representative politics-feed coverage, general accuracy or a latency guarantee.

## Automated and browser verification

- **77 Python tests passed** with `python3 -m unittest discover -s demo -t . -p 'test_*.py'`.
- **9 extension bridge tests passed**: trusted pairing, opt-in capture, budgets, job ownership, private routes and metadata boundaries.
- Browser DOM tests passed: X and Reddit insertion, media scope, source rendering, edits, scrolling, checks pending beyond 15 seconds and private-route cleanup.
- Actual packaged MV3 extension passed in Chrome for Testing 153: local pairing, model health, controlled-fixture submission/polling, public-text extraction, modern/classic/old Reddit, edits and default-off capture.
- Real dashboard browser tests passed: APIs, source example, separate live/development counters, hierarchy rendering, readable reuse routes and startup/daily cost arithmetic.
- The package build passed its exact 15-file allowlist and private-token exclusion checks.

Browser fixtures are deliberately synthetic. They were not counted as live-feed captures. No authenticated X/Reddit feed was tested. Screenshot/report artifacts are in `demo/data/dashboard-tests/` and `demo/data/extension-tests/`; do not distribute the entire data directory because it contains local credentials elsewhere.

## Actual hierarchy replay

`experiments/politics/hierarchy-replay.json` records four synthetic requests through the running API and local GPU model:

| Request | Wall time | Result | Generative model calls |
|---|---:|---|---:|
| Original text | 6.6695 s | Probably true; cited official action | 2 |
| Unchanged native repost | 0.0281 s | Original review reused | 0 |
| Same assertion, changed spacing | 0.0472 s | Canonical assertion reused | 0 |
| Opinion added around quoted original | 3.5249 s | No factual claim in wrapper; original assessed separately | 1 |

The formatting case is exact assertion equivalence, not evidence that arbitrary semantic paraphrases are safe. Learned semantic reuse has qualifier/context vetoes, bidirectional entailment and a further source-support check; its general precision remains unmeasured. Multi-number rewordings take fresh verification.

## Automatic source precomputation

Latest final run: `experiments/politics/precompute-report.json`.

- Six actual source documents; 11 accepted exact attributed quotations.
- One invalid model-selected passage ID was rejected before checking.
- Six local selector calls, 8,250 input tokens and 72 output tokens.
- **Zero generative fact-verification calls**: exact title/quotation lookup checked current document versions and returned original offsets/provenance.
- 3.975 seconds of selector generation; 6.210 seconds total wall time.
- All 11 results use neutral rating 3, **Source wording confirmed**, and `whole_post_validated=false`.

These are confirmed source quotations, not independently verified world assertions. They do not contribute to decisive feed coverage. A forged quotation, changed title or wording removed from the current source version cannot pass this deterministic path.

## Source-derived development checks

The final recorded `experiments/politics/development-run.json` contains 12 synthetic, source-derived examples. Eleven returned an assessment; one was unresolved because the model repeated a claim ID and the validator rejected the malformed answer. Five outputs were probably true, two likely false and four unsure. One unchanged input hit cache because the independent hierarchy replay had just checked it. Timing includes local queue contention and must not be presented as an uncached benchmark.

The corpus-derived cases were reused during development. They are not held out, a representative feed, or independently annotated accuracy data. Dates, scope and supporting passages remain reviewable in the raw output. A source-derived changed-object claim with no identified event is conservatively unsure: one different rule does not refute every possible past rule.

The development process exposed real failures:

1. Qwen supported “five years” against a passage saying “1 year.” Added spelled-number/unit vetoes and an English support gate.
2. A source's words were mistaken for proof that a social post was circulating. Added a source-scope abstention.
3. Raw source precomputation initially promoted political rhetoric and sentence fragments. It now creates explicit attributed quotations, preserves abbreviation boundaries and checks quotations by exact containment. Prior unsafe outputs were retired, not treated as valid prelabels.
4. Quote wrappers could outlive an embedded original or retain a partial failed review. Expiry clamping and failure cleanup now prevent both.
5. Numeric sets could miss entity/value swaps. Ordered qualifiers and a block on multi-number semantic transfer now force fresh checking.

Earlier failure artifacts remain in `experiments/politics/development-run-before-*.json` and `experiments/politics/precompute-attempts/` for audit. Retired reviews are shown as expired/superseded, and their old coverage flags cannot revive under a replacement review.

## Live state and limits

At handoff the index held 57 documents, including the previous English/Hindi evidence and current/bounded US acquisitions. Qwen, MiniCheck and the web service all reported ready. The source worker was waiting for its next four-hour cycle; its exact schedule is in `demo/data/news-worker.json`.

**Live observed posts: 0. Representative live-feed coverage: not measured. Paid external API spend: $0.** Model execution uses provided hardware; electricity/hardware/development are excluded from that spend figure. No public deployment, paid provider, image/video authenticity system or user completion-email service is configured.
