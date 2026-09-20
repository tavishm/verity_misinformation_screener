# Precise claim reuse for the politics demo

Planning snapshot: 19 September 2026. This answers the pre-implementation design question and the request for startup/daily cost estimates. It does not change the running extension/backend, start training, buy platform access, or claim measured politics-feed coverage.

## Recommendation

Use X first, English text, and a defined recent US-politics window. Research shared events and claims, but issue each assessment against that post's actual assertions and context. Reposts and equivalent claims provide two different reuse paths. An evidence index containing original passages and structured facts is the useful initial “news world model”; a large new trained model is unnecessary.

The economic hypothesis is that a small fraction of evidence investigations can resolve a large share of observed feed impressions. The research unit is an information gap shared by claims, rather than one post. Whether that achieves 50% must be measured; popularity, repeated impressions and topic concentration alone do not establish it.

## The hierarchy

1. **Topic:** US politics. Used for routing, never truth inheritance.
2. **Event:** a specific bill, court case, speech, election result or statistical release. Groups related evidence, including reports that disagree.
3. **Atomic assertion:** a canonical predicate and arguments, with event identity, time, jurisdiction, amount/unit, polarity, attribution and modality. “A bill was introduced,” “passed the House,” “became law” and “takes effect” are different assertions.
4. **Post version:** all material assertions, their spans, original publication time, referenced original version, attached-media identity and checked scope.
5. **Exposure:** a user sees a version. Multiple exposures can legitimately reuse its assessment; this does not create additional independent accuracy-test examples.

The graph also connects assertions to immutable evidence passages and their source versions. Assessments depend on both post context and evidence validity. When evidence changes or an essential context dependency becomes unknown, an old result must be rechecked.

## Precise reuse rules

| Relationship | What may be reused | Required check |
|---|---|---|
| Native unchanged repost | The original assessment for its original checked scope | Same original version; no added text; matching media/context; evidence still valid. Attribute the assessment to the original claim, not the reposter. |
| Identical copied text | Candidate assessment | Resolve time words such as “today,” quote framing, source attribution, relevant media and original publication context. Text hash alone is insufficient. |
| Paraphrase | Canonical-claim evidence and, after validation, its assessment | Independently compare the new assertion to the canonical record; compatible entity roles, date, number, unit, negation, modality and attribution; verify it against original evidence. |
| Quote post or added commentary | The quoted original's assessment, scoped to that original | New wrapper text gets its own claim detection/check. Even opinion-only commentary does not automatically receive a whole-post green badge. |
| Changed date, number, actor, negation or legal status | Event evidence | Create a different assertion and assess it. It might be true, false or unresolved; difference alone does not make it false. |
| Same event, different assertion | Source documents and research work | No verdict inheritance. Retrieve the passages appropriate to the new assertion. |
| Unknown context or ambiguous equivalence | Retrieval candidates | Abstain from transferring a verdict and route to more evidence/review. |

Do not force clusters to contain 100 posts. Use embeddings, lexical search, entity/event IDs and hashes to find candidates. Then apply structured checks and a narrow entailment/equivalence model. Avoid transitive similarity chains: A matching B and B matching C does not let C inherit from A. Every accepted member must match the canonical assertion and evidence independently.

Five to seven representative samples can map an event's variants, prioritize research and audit an existing cluster. They cannot certify all unsampled members. With 10 incompatible items among 100, seven uniform random samples miss all ten about 46.7% of the time. Sampling cluster centers provides even less assurance about exceptional members.

## The inexpensive checking path

- Exact-version and unchanged-repost cache lookup.
- Local factual-claim and topic routing. Opinion/no-checkable-claim is not a true verdict.
- Hybrid retrieval of existing assertions and original passages; filter by entity/event/time before expensive comparisons.
- Deterministic checks for dates, quantities, units, predicate direction and explicit status transitions. Use arithmetic code for numerical derivations.
- Batch compact claim–passage support comparisons locally. MiniCheck's unsupported output is not a false verdict; contradiction requires positive incompatible evidence and a separately tested route.
- Escalate ambiguous/complex cases to the existing local multilingual model or a bounded stronger-model review. Models select and assess source evidence; they are not the source of ground truth.
- Research a missing evidence packet once, then independently recheck affected claims. Prioritize the expected additional supported/contradicted feed impressions per incremental dollar, subtracting overlap with already scheduled work. Reserve capacity for exploration and random audits.

A source record should retain URL, exact passage offsets, publication/retrieval times, source lineage, jurisdiction, version and correction status. A government statement can establish what the government said; it does not automatically establish every underlying assertion. Syndicated articles derived from one report are one evidence lineage, not multiple independent witnesses.

MiniCheck already measured about 28 medium-length claim–passage comparisons/second on one of the available A6000s. At an illustrative two claims and three candidate passages per post, scoring 10,000 new post versions requires 60,000 comparisons: about 35.7 GPU-minutes at that measured rate. This excludes extraction, retrieval, source research, the contradiction route and audits; it is not complete-post throughput or a capacity promise. Source: `experiments/minicheck/RESULTS.md`.

Fine-tune later for routing, equivalence and evidence judgments, using audited examples and adversarial edits. Do not continually train current news facts into model weights. By tomorrow, inference and indexing can improve the demo more directly than an unvalidated training run.

## What to show tomorrow

Reuse the existing extension interface. The next implementation should replace its small fixed corpus/demo route with bounded US-politics evidence ingestion, claim/evidence lookup, provenance-aware repost handling, batched local validation and a shared cache. The current extension and backend do not yet implement these additions.

Demonstrate five visibly different cases: unchanged repost; equivalent wording; changed number/date; quote post adding a new factual assertion; and a new unsupported claim. Only the first two may reuse a matching assessment. The changed-detail example reuses evidence while being judged separately. Each displayed decisive result must have an inspectable source passage. Label recorded feed replays and synthetic robustness examples clearly.

Collect a consecutive consented politics-feed sample before choosing favorable examples, ideally 300–500 impressions. Use an earlier portion for thresholds; retain a later portion for evaluation. Do not claim general-user representativeness from one selected feed. Count actual media and opinion items in the denominator, even when the text demo cannot assess them.

Report separately:

- Fully assessed impressions / all observed impressions.
- Assessed in-scope text impressions / in-scope text impressions.
- Assessments already available when first viewed, versus automatic checks completed afterward.
- Unique assertions researched, unchanged-repost hits, individually verified paraphrases, and evidence reuse.
- Audited correct/incorrect supported and contradicted labels, uncertainty and partial coverage.
- Paid spend, GPU time, new evidence investigations and delay to a badge.

An unsure/opinion/related-event label does not count toward decisive factual coverage. An unchanged repost can count as another covered exposure if its scope/context match; it cannot count as another independent correctness test. Unchecked media prevents a whole-post coverage claim when the factual assertion depends on that media.

For example, 80% eligible text impressions × 80% of those served by current evidence × 80% of those safely resolved = 51.2%. These are hypothetical factors, not our observed rates. If only 45% of a feed can be fully checked by the text pipeline, 50% whole-feed verification is impossible without widening scope. Likewise, the >1M-view restriction sets a ceiling at the fraction of that user's feed that actually meets it.

## Acquisition and the $15 limit

Do not start with “find every US-politics X post over one million views.” X exposes impression counts, but no minimum-impression operator is documented in its search operator list. Retrieving and filtering candidates is a bounded search, not an exhaustive census. Published X reads cost $0.005 per returned post resource; 1,000 candidates cost $5 and 100,000 cost $500 before model work. At 100,000/day, 30 days uses the complete published 3M-read self-service monthly allowance; higher volumes require a separate Enterprise quote. Additional returned resource types can cost extra.

For a $15 OpenRouter-credit demo, use a consented observed feed or already-authorized sample and directly available source material. Keep bulk inference on the existing GPUs. Reserve at most $10 for model-based evidence review/audits and $5 for retries or harder cases. No paid calls have been made for this proposal. OpenRouter credits do not pay X or Brave; any paid acquisition/search would need a separately funded account or a revised cash allocation. Do not assume free promotional credits in expansion economics.

For tomorrow, the proposed initial scope is 20–50 event/evidence investigations and a few hundred observed posts, with workload reduced if research is harder than expected. These counts are workload limits, not a promise of 50% coverage. The number of distinct events and usable evidence packets must be discovered from the actual sample.

## One-time and daily cost estimates

There is no finite, once-and-for-all inventory called “all internet facts.” Quote a bounded corpus across topics with a defined lookback window and daily workload. Startup pays to prepare that initial evidence collection; ongoing work pays for new information, changes, corrections, expiry checks and previously unresolved claims.

Illustrative **research-attempt** unit: six paid searches, 20,000 aggregate billed input tokens and 3,000 aggregate billed output/reasoning tokens across the entire investigation, plus a 25% planning reserve. All attempts, including failed/unresolved ones, consume budget. These limits do not guarantee adequate evidence or equal quality between models.

Rates checked 19 September 2026:

- Brave Search: $5 per 1,000 requests, without free-credit assumptions.
- OpenRouter DeepSeek V3.2 catalog: $0.269/M input, $0.40/M output.
- OpenRouter Claude Sonnet 4.6 catalog: $3/M input, $15/M output.

An investigation that uses all six paid searches and a paid review costs **$0.045725** with DeepSeek or **$0.16875** with Sonnet, including reserve. Those are provider alternatives, not a confidence interval or a claim of equal quality.

The intended architecture should avoid paid calls on most investigations by using the existing GPUs and directly acquired source documents. The required fractions are not yet measured. For an explicit local-heavy hypothesis, assume **20% need paid search and 5% need a paid Sonnet review**, each with the allowances above. The average paid research allowance becomes:

`1.25 × [(0.20 × 6 × $0.005) + (0.05 × (20,000 × $3/M + 3,000 × $15/M))] = $0.0140625/investigation`

| Workload across supported topics | Local-heavy hypothesis: 20% search, 5% paid review | More escalation: 50% search, 20% paid review | Paid search + Sonnet on every investigation |
|---|---:|---:|---:|
| 10,000 initial investigations | $141 | $450 | $1,688 |
| 1,000 new/changed/rechecked investigations per day | $14/day | $45/day | $169/day |
| 100,000 initial investigations | $1,406 | $4,500 | $16,875 |
| 10,000 new/changed/rechecked investigations per day | $141/day | $450/day | $1,688/day |

These are **paid research subtotals**, not complete service prices. The local-heavy and higher-escalation fractions are hypotheses; all three columns use the same token/query allowances and 25% reserve. They do not establish that the allowed investigations succeed, that a given inventory covers all topics, or that any column delivers equal quality/50% feed coverage. The last column is not an upper bound on arbitrarily complex research.

Using the cheaper catalog model for the 5% paid-review route changes the local-heavy allowance to $0.00791125/investigation: $791 for 100,000 initial attempts and $79/day for 10,000 daily attempts. Whether that cheaper reviewer is adequate must be evaluated rather than assumed. Do not trade measured precision for a lower quoted price.

Direct-source feeds and local inference reduce API spend while adding indexing, GPU and maintenance work. If a stronger paid review follows a paid cheap review, count both; the table assumes local processing followed by at most the stated paid-review route. Daily counts include refreshes/corrections/rechecks, not just brand-new facts. Full-text sources, licenses, actual local capacity and operating costs still need their own measurements/quotes.

Neither the 10,000 nor the 100,000 inventory is a measured estimate of all-topic requirements. They are workload scenarios for a calculator. The demonstration should measure evidence investigations and exposure reuse so those inputs can later be replaced by observed counts.

For a defined service:

`startup = initial research attempts × attempt cost + initial post acquisition + initial indexing/setup + other quoted startup costs`

`daily = new/changed/recheck attempts × attempt cost + daily post acquisition + local processing + hosting/storage/delivery + licensed content + media work + quality review`

X acquisition should be a visible separate row. As an arithmetic illustration, seven days at 100,000 returned posts/day adds $3,500 to startup and the same continuing volume adds $500/day, assuming this fits the applicable account limits. This is not an estimate of how many reads discover all viral posts. Reddit access/rights, news licensing, electricity, hosting, media checking, maintenance and human audits remain additional/unknown, not silently zero. The current owned hardware has no incremental model-API fee but has energy and capacity costs.

The useful presentation claim is: **one researched evidence packet can support many independently checked claims and many more feed impressions.** Demonstrate and measure that reuse; do not convert cluster size into a verification claim.

## Primary references

- X pricing: https://docs.x.com/x-api/getting-started/pricing
- X impression metrics: https://docs.x.com/x-api/fundamentals/metrics
- X search operators: https://docs.x.com/x-api/posts/search/integrate/operators
- Brave search pricing: https://brave.com/search/api/
- OpenRouter live model catalog: https://openrouter.ai/api/v1/models
- DeepSeek V3.2: https://openrouter.ai/deepseek/deepseek-v3.2
- Claude Sonnet 4.6: https://openrouter.ai/anthropic/claude-sonnet-4.6
- MiniCheck: https://aclanthology.org/2024.emnlp-main.499/
- Claim matching: https://aclanthology.org/2021.acl-long.347/
- AVeriTeC real-world web-evidence benchmark: https://arxiv.org/abs/2305.13117

Editable arithmetic: `python3 research/politics_cost_model.py --initial 100000 --daily 10000`. Defaults model the 20% search / 5% paid-review hypothesis. Use `--paid-search-share 0.5 --paid-model-share 0.2` for more escalation or set both to 1 for the fully paid-review scenario. X acquisitions are optional explicit arguments, not assumed free when omitted.
