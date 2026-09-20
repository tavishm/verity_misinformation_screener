# Fact-checker unit economics

Researched **19 September 2026**. USD, before taxes. These are current published rates, not a supplier quote or measured accuracy/latency. Recheck rates, model availability and account access before buying. Calculations below use paid prices without free credits or caching discounts.

## Conclusion

**$10/day can fund a constrained pilot checking hundreds of new claims per day and serving cached results repeatedly. It cannot buy fresh research for 50,000 claims per day, much less one million.** The limiting variable is new claims researched, not badges displayed. Platform access, media processing and operational costs must fit the same budget; neither platform licenses nor available capacity are established here.

Use a cheap classifier only on the shortlist, share evidence at claim level, and prioritize research by expected user exposure. Keep automatic and user-requested reviews inside one explicit daily allowance. Model-generated verdicts need evidence and validation; a cheap generation is not automatically a successful fact-check.

## Verified paid rates

| Service | Input / 1M tokens | Output / 1M tokens | Search |
| --- | ---: | ---: | ---: |
| Muse Spark 1.3 Standard | $1.25 | $4.25 | $2.50 / 1,000 queries |
| Muse Spark 1.3 Contributor | $0.10 | $0.20 | $2.50 / 1,000 queries |
| Gemini 3.1 Flash-Lite | $0.25 | $1.50 | $14 / 1,000 Google queries after included allowance |
| Gemini 3.5 Flash-Lite | $0.30 | $2.50 | $14 / 1,000 Google queries after included allowance |
| Gemini 2.5 Flash-Lite, legacy reference | $0.10 | $0.40 | $35 / 1,000 grounded prompts after included allowance |
| Brave Search API | — | — | $5 / 1,000 requests |

Sources: [Meta pricing and rate limits](https://dev.meta.ai/docs/pricing-rate-limits), [Google API pricing](https://ai.google.dev/gemini-api/docs/pricing), [Brave Search API](https://brave.com/search/api/).

Meta lists no minimum or upfront commitment. Contributor permits training on prompts/completions; Standard does not. Contributor limits are 100 requests/minute and 3M tokens/minute; Standard is 3,000 and 4M, shared per team. Cached-input prices are $0.002/M and $0.15/M respectively. [Meta pricing](https://dev.meta.ai/docs/pricing-rate-limits)

Google 3.x includes 5,000 search queries/month shared across models; 2.5 Flash/Lite includes 1,500 grounded prompts/day shared across those models. 3.x can execute several billable queries per model request. Listed batch token rates are half of standard. [Google pricing](https://ai.google.dev/gemini-api/docs/pricing)

Brave advertises 50 queries/second and $5 monthly credits. Its public Search offer lists usage pricing without a fixed subscription minimum; confirm dashboard billing terms at signup. We do not spend the credits in forecasts. [Brave Search API](https://brave.com/search/api/)

Google's direct deprecation page currently lists no shutdown date for stable 2.5 Flash-Lite and May 7, 2027 for 3.1 Flash-Lite. However, Google support reports restricting 2.5 access to previously active users. Use 3.1 or newer for a new project's forecast unless access is confirmed. [Deprecations](https://ai.google.dev/gemini-api/docs/deprecations), [Google support response](https://discuss.ai.google.dev/t/gemini-2-5-flash-deprecated-without-warning-earlier-than-shutdown-date/174217/27)

## The requested Muse model exists, with a material tier difference

The exact identifier is `muse-spark-1.3-contributor`. Meta confirms its launch and API availability. [Meta announcement](https://research.meta.ai/blog/introducing-muse-spark-1-3), [Model API product page](https://dev.meta.ai/products/meta-model-api)

**Contributor does not support `max` reasoning.** That setting is available only on Standard Muse Spark 1.3; Contributor supports up to `xhigh`. Reasoning cannot be disabled, and internal reasoning tokens are billed as output. Any budget that counts only the final 200-word explanation will underestimate cost. Output caps include reasoning plus visible output; truncation must yield an incomplete job, never a published rating. [Meta reasoning documentation](https://dev.meta.ai/docs/reasoning)

Recommendation: model Standard as the default for user traffic. Contributor is a separate, cheaper scenario only after deciding that training use is acceptable for the data, permissions and product promises. Public accessibility alone does not settle those questions. The tier is not merely a billing toggle.

## Cost algebra and explicit assumptions

For a review with `I` total billed input tokens, `O` total billed output tokens including reasoning, and `Q` search queries:

`cost = I × input_rate / 1,000,000 + O × output_rate / 1,000,000 + Q × search_rate / 1,000`

Sum tokens across **all** calls, retries and source-reading turns. Cached prefixes are upside, not an assumed saving. The following are planning envelopes to benchmark, not observed consumption or quality guarantees.

| Job | Input | Total output | Queries | Muse Standard + native search | Muse Contributor + native search |
| --- | ---: | ---: | ---: | ---: | ---: |
| Shortlist classification only | 500 | 200 | 0 | $0.001475 | $0.000090 |
| Focused quick review | 3,000 | 1,000 | 2 | $0.01300 | $0.00550 |
| Deep escalation, additional to quick review | 20,000 | 5,000 | 8 | $0.06625 | $0.02300 |
| Difficult review stress case | 50,000 | 20,000 | 12 | $0.17750 | $0.03900 |

The shortlist classifier at 500 input / 200 total output tokens costs **$0.000425** with Gemini 3.1 Flash-Lite, or **$0.425 for 1,000 candidates**. Use it only for selected ambiguous cases; clear exclusions and exact cache hits should not require generation. These are token allowances, not measured averages.

Gemini 3.1 Flash-Lite supports `minimal` thinking, but this does **not** guarantee zero reasoning tokens. Evaluate completed outputs at the proposed allowance and count reasoning in usage. [Google thinking controls](https://ai.google.dev/gemini-api/docs/generate-content/thinking) An alternative initial pilot can omit API triage, use a measured local filter, and audit a random sample within the quick-review allowance; local CPU, hosting and model maintenance still have costs.

Deep escalations are **additional work on a subset of quick-reviewed claims**. Therefore 300 quick reviews and 10 deep escalations cover at most 300 new claims, not 310. The deep row counts aggregate extra tokens and queries after the quick review; repeated context and retries must fit those totals.

## Why the proposed million-post funnel remains too large

With the proposed percentages, one million posts becomes:

- 700,000 excluded opinions/personal posts, assuming that exclusion can actually be achieved reliably.
- 150,000 text posts still needing claim matching or evidence.
- 150,000 media posts, of which 105,000 are hypothesized reposts and **45,000** are novel media.

That leaves up to **195,000 new items**, not just the novel-media tail. Neither text-only nor apparently easy to search means already checked. Also, matching a media asset does not establish that its new caption or date claim is true.

Illustrative lower-cost pressure tests:

- One search for each of 50,000 claims costs **$125/day** on Meta, **$250/day** on Brave, or **$700/day** at Google's 3.x marginal price, before model tokens.
- 50,000 quick reviews under the table's assumptions cost **$650/day Standard** or **$275/day Contributor**.
- Quick reviews of all 195,000 novel items plus 45,000 additional deep escalations cost **$5,516.25/day Standard**, before triage and ingestion.
- Even classification of every one of one million posts costs **$90/day Contributor** or **$425/day Gemini 3.1** at 500 input / 200 total output tokens. One request per post would also exceed Contributor's published 144,000-request theoretical daily maximum. Packing multiple independent items can reduce request overhead, but does not remove token cost or quality risk.

Therefore, the first funnel must happen before paid per-post work: platform eligibility, extension-local filtering where authorized, post-ID lookup, normalized URL lookup, exact hashes, and previously reviewed claim/media matching. Semantic matching itself has a cost and needs measurement. Do not forecast a 70%/70% cache hit rate until a real feed sample establishes it.

## A $10/day pilot envelope

The recommended controlled pilot uses Standard Muse for research, Gemini 3.1 for limited triage, and **application-controlled Brave search**. Every search, model call and escalation needs a reserved spending allowance. **This is conditional on obtaining authorized platform access inside the remaining budget; unknown Reddit and other license costs are not included in the subtotal.** Infrastructure is an allocation to price and cap, not a supplier quote.

| Daily allocation | Quantity / basis | Amount |
| --- | --- | ---: |
| Ambiguous shortlist triage | 1,000 × $0.000425, validate output use | $0.4250 |
| Focused reviews, Brave + Standard Muse | 300 × $0.018 | $5.4000 |
| Additional deep escalations on 10 of those claims | 10 × $0.08625 | $0.8625 |
| Media source lookup | 100 Web Detection image units × $0.0035 | $0.3500 |
| X post reads | 100 returned posts × $0.005 | $0.5000 |
| Hosting, DB, queue, email allocation | Must be independently priced and capped | $1.000 |
| **Subtotal** | Unknown licenses, labor and taxes excluded | **$8.5375** |
| Remaining allowance | Licenses, extra media, retries, refreshes, contingency | $1.4625 |
| **Operating budget ceiling** | Workload must shrink if remaining costs exceed allowance | **$10.0000** |

Web Detection's main paid tier is $3.50 per 1,000 image units; its monthly free allowance is ignored here. Additional features and cloud resources cost extra. [Cloud Vision pricing](https://cloud.google.com/vision/pricing) X currently lists $0.005 per returned Post, before separately billed resource expansions. [X API pricing](https://docs.x.com/x-api/getting-started/pricing) These 100 X reads do not acquire 1,000 triage candidates by themselves; other candidates need an authorized, funded source. Nor do 100 image units imply 100 entire videos: each submitted frame/crop consumes a unit.

The 300 quick-review slots include automatic and manual triggers together, plus a random audit sample. For example, reserve 100 slots for user requests; unused reserved capacity can release later that day. Ten claims can receive additional deep review. Serving an existing valid review does not consume a fresh-review slot, but still consumes storage/network capacity. If difficult jobs exceed the modeled allowance or licenses cost more, the controller reduces job count. Exhausting budget produces queued/unreviewed status, not an unsupported verdict.

An alternative using native Meta search can fit **400 quick reviews + 20 additional deep escalations** at **$6.525 research**, or **$8.800 subtotal** after the same $0.425 triage, $0.350 media, $0.500 X reads and $1.000 infrastructure allocations. It covers at most 400 new claims. Its $1.200 remainder still excludes unknown licenses; use this option only after native-tool spending can be bounded as described below.

For that native-search alternative, Contributor research would cost $2.66/day versus $6.525 Standard. That saving does not establish equivalent permitted data use, reasoning configuration, reliability or latency.

## Search controls and latency

Meta's native search returns citation annotations and optional raw results; the model decides whether to search, so enabling the tool does not guarantee evidence retrieval. Meta also states incomplete source coverage and lower dependability for complex, multi-hop research. Require evidence URLs/results and independently check their relevance before publication. [Meta search-grounding documentation](https://dev.meta.ai/docs/search-grounding)

The public search guide did not establish a hard query-count limit per native request. Budgeted query counts are assumptions until confirmed against usage. An expected-cost ledger alone cannot guarantee a hard $10 ceiling when native tools can perform unbounded billable queries. Use verified provider-side spend/query limits, or application-mediated tools with explicit per-query authorization and aggregate token caps; reserve worst-case spending before dispatch and reconcile the real bill. Stop dispatch when available balance is fully reserved, including concurrent jobs. Confirm whether any provider budget setting actually stops requests, rather than merely sending alerts.

Brave adds $0.005 per quick review and $0.020 per deep escalation compared with native Meta search at the same modeled query counts. Thus 400 quick + 20 deep would cost **$8.925 research** and **$11.200 subtotal** with the same other allocations. The recommended 300-quick / 10-escalation workload keeps this controllable option inside the target. Native Meta remains an economical candidate only after its enforceable limits are established, or with an explicitly soft operating target.

Do not promise every new review finishes within 15 seconds. Cached results can be fast; a fresh review should show progress promptly and offer a later result. Batch processing can lower nonurgent token cost, but Google's documented target turnaround is 24 hours, so it cannot support the interactive promise. [Google Batch API](https://ai.google.dev/gemini-api/docs/batch-api)

## Expansion and decision metrics

Scale budget with **new claim clusters**, not raw post counts. Under the recommended Brave envelopes, 3,000 quick + 100 additional deep escalations cost $62.625/day Standard before triage/operations; 30,000 + 1,000 cost $626.25. These cover at most 3,000 and 30,000 new claims respectively. Search, platform licenses, human adjudication, video analysis and storage may dominate well before model capacity.

Measure: spend per completed evidence-backed review; completed vs queued jobs; source-fetch failure rate; retry and reasoning-token distributions; eligible impressions served from cache; reuse errors caused by changed dates/captions; and new claim clusters per 1,000 eligible impressions. Track daily and p95 review costs, not only means. These observations determine whether wider coverage requires more money, better matching, narrower scope, or more reliable evidence sources.

The 60% coverage target should name its denominator: all feed impressions, factual-claim impressions, or viral eligible impressions. Excluding 70% as nonfactual while promising truth ratings on 60% of all posts would conflict unless the exclusions and user-feed mix differ. Distinguish "examined, no checkable claim" from "fact checked."
