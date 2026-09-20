# Scalable verification with existing GPUs

Research snapshot: 19 September 2026. Scope: an overnight live-feed demonstration using the user's available 8 × A6000 machine, with local inference as the main path and optional small paid escalation capacity. Subsequent measurements on that machine are now available: [MiniCheck methodology](../experiments/minicheck/README.md), [raw throughput and debugging results](../experiments/minicheck/benchmark.json), and [the local-first implementation and capacity plan](../LOCAL_FIRST_PLAN.md). Those synthetic scorer measurements and prototype timings do not establish representative feed coverage or production accuracy. Provider, paper and repository results below are distinguished from our measurements and proposed engineering assumptions.

## Recommendation

Build a **continuously refreshed evidence index**, then run many local claim-versus-evidence checks against it. The reusable object should be an article, official document or event evidence packet—not only a previously fact-checked claim. New posts can then receive new evidence-based checks without a paid search for every post, and newly arriving evidence can refresh earlier uncertain posts.

Three algorithms are the most promising:

1. **Incremental proposition retrieval with source-span preservation:** turn newly ingested articles into searchable factual units, retrieve relevant units for arbitrary incoming claims, and verify against their original passages.
2. **Document-grouped local entailment with selective escalation:** batch many claims against the same evidence, reuse computation, and reserve larger reasoning models for ambiguity or contradictions.
3. **Adaptive retrieval scheduled by marginal feed coverage:** research unresolved claim/event clusters in the order that additional evidence is expected to help the most observed feed impressions; deduplicate queries and propagate only semantically valid results.

This is a proposed combination of established techniques, not a claim of a new research result. The GPU availability materially improves the feasibility of mass inference. It does not establish sufficient evidence coverage for 30–50% of an arbitrary feed.

## 1. Incremental proposition retrieval

**Research basis.** Dense X Retrieval indexes atomic, self-contained propositions instead of only passages. Across its studied retrieval settings, proposition indexing improved average Recall@20 by **10.1 points for unsupervised retrievers and 2.7 points for supervised retrievers** over passage retrieval. These are retrieval results on the paper's datasets, not social-media verification accuracy. [Dense X Retrieval paper](https://aclanthology.org/2024.emnlp-main.845.pdf)

The authors release a local propositionizer, `chentong00/propositionizer-wiki-flan-t5-large`. Their released FactoidWiki contains **257M propositions** derived from an **October 2021 Wikipedia snapshot**. Reuse the extraction idea/model, not that snapshot as current-event truth or as an overnight download requirement. [Author repository](https://github.com/chentong0/factoid-wiki)

**Proposed implementation.** Start from the articles already linked by observed posts, fresh publisher feeds whose use is allowed, and relevant primary documents. Ingest new pages continuously; canonicalize URLs and hash content so each version is processed once. Cluster near-identical syndicated articles to retain one evidence lineage, not ten apparently independent confirmations.

Store the original text plus sentence boundaries immediately. Initially index sentences/passages with BM25; add dense embeddings and extracted propositions asynchronously. This keeps the pipeline useful even before proposition extraction finishes.

For every proposition retain:

```text
proposition text
subject / predicate / object
time interval / location / numeric units
negation / uncertainty / attribution / quotation speaker
document_id / document_version / original source offsets
published_at / observed_at / evidence lineage
```

For an incoming post, extract the essential checkable claims, preserve the above qualifiers, and retrieve candidate passages using lexical plus semantic matching. Propositions serve as retrieval keys; **the final verifier reads original passages**. An extraction that accidentally strengthens a statement must never become evidence for itself.

Maintain a reverse index from unresolved claims to entities/events. When a new document enters the corpus, match its propositions against pending claims and re-evaluate relevant posts. This gives the system an expanding body of evidence without requiring a human to seed each fact-check.

**Critical distinction.** “The minister said unemployment fell” supports a statement about what was said. It does not establish that unemployment fell. Dates, units, quoted claims, allegations and uncertainty must survive extraction. Government statements can be primary evidence of government announcements; they are not automatically independent proof of disputed conduct.

**Tomorrow's demo.** Index current feed-linked articles, verify unseen paraphrases and changed numbers/dates, show the exact supporting passage and timestamp, then demonstrate an unresolved post being updated when a new source is ingested. Start with sentences if the propositionizer's news-domain performance is weak. No model training or full-web indexing is required.

## 2. Document-grouped local entailment

**Research basis.** MiniCheck's 770M-parameter Flan-T5 verifier reports GPT-4-comparable performance on its grounding benchmark at **400× lower evaluation cost**. This means checking whether supplied documents support a statement; it is not proof of comparable open-world political fact-checking. [MiniCheck paper](https://aclanthology.org/2024.emnlp-main.499/)

The author repository reports **29,000 claim/document benchmark examples in 30 minutes on one NVIDIA A6000 with prefix caching**, versus **55 minutes without caching**, for the 7B variant. Several documents recur in that workload. This is unusually relevant hardware evidence, but it excludes article discovery, extraction, source assessment and whole-post aggregation. [Author implementation and throughput](https://github.com/Liyan06/MiniCheck)

`lytang/MiniCheck-Flan-T5-Large` lists an **MIT** license and outputs **supported versus unsupported**, not true versus false. The Bespoke 7B model card instead lists **CC BY-NC 4.0** and a commercial licensing route. Prefer the small MIT model for the initial product experiment; treat the 7B timing as a reference until an appropriate model and license are chosen. [770M model card](https://huggingface.co/lytang/MiniCheck-Flan-T5-Large), [7B model card](https://huggingface.co/bespokelabs/Bespoke-MiniCheck-7B)

**Proposed implementation.** For each claim, retrieve a small candidate set, filter impossible entity/time matches, and batch the claim/passage pairs. Group jobs by document and keep original evidence first in decoder-model prompts so repeated document prefixes can be reused. Encoder-decoder models such as the 770M model need their own supported batching/caching implementation; do not assume vLLM's decoder prefix cache applies to them unchanged.

The first-stage output should be `supported_by_passage` or `not_supported`. For publication:

- Require a source passage that addresses the essential factual assertion, plus appropriate source quality, scope and freshness.
- Treat unsupported as unresolved. A false rating requires **positive contradictory evidence**, with a separate contradiction check that preserves time, entities and numerical definitions.
- Escalate conflicting sources, causal conclusions, multi-hop synthesis, medical/legal claims, sarcasm and ambiguous temporal claims to a local larger model or paid reviewer.
- Keep a partially checked post distinguishable from a post whose essential claims have all been assessed.

vLLM documents that prefix caching reduces **prefill** computation; it does not accelerate generation of new output tokens. Short structured verdicts with shared evidence are a suitable workload to test. [vLLM prefix caching](https://docs.vllm.ai/en/latest/features/automatic_prefix_caching/)

**Capacity arithmetic, not a deployment result.** The repository's 29K/30-minute result is about **16 claim/document pairs per second on one A6000**. At two claims per post and three evidence candidates per claim, 50,000 posts require 300,000 pair checks: about **5.2 GPU-hours** at that particular measured rate. This excludes all other stages and assumes comparable lengths and reuse. Eight GPUs do not automatically provide eightfold whole-system speedup. Benchmark the selected 770M model, extraction model and retrieval pipeline locally before sizing the service.

**Tomorrow's demo.** Run a small batched verifier service; log examples/second, batch sizes, passage lengths and GPU utilization. Compare repeated-document batches with randomized batches. Keep one fast path responsive while a separate worker handles longer review jobs. Start with a small number of independent GPU workers and scale from measured demand rather than using eight-way tensor parallelism for every small request.

## 3. Adaptive research that maximizes observed coverage

**Research basis.** Adaptive-RAG routes questions among no retrieval, one retrieval and iterative retrieval using predicted complexity. Its published experiment reports different route times of **0.35s, 3.08s and 27.18s**; these are measurements for that QA setup, not expected latency here. Borrow adaptive effort allocation, but replace its no-retrieval answer route with a **valid cached-evidence route** for this product. A model's memory alone does not earn a fact-check badge. [Adaptive-RAG paper, Table 3](https://aclanthology.org/2024.naacl-long.389.pdf), [Author code](https://github.com/starsuzi/Adaptive-RAG)

FaStFact combines chunk-level claim extraction, selective verification and full webpage evidence, addressing the cost of per-sentence calls and the insufficiency of search snippets. Its existing implementation is useful reference material. Do not copy confidence-based skipping as a license to mark an unsupported social post true. [FaStFact paper](https://aclanthology.org/2025.findings-emnlp.1295.pdf), [Author code](https://github.com/Yingjia-Wan/FastFact)

**Proposed retrieval ladder.**

1. Look up exact post/claim versions and valid prior evidence.
2. Search the local live article index.
3. Follow a relevant already-known source page or authoritative event page.
4. Perform one shared fresh search for an unresolved cluster; ingest returned documents once and re-run all matching claims.
5. Add another search only when an explicit evidence gap remains and its likely coverage gain justifies the cost.

Make the request key include normalized entities, predicate, time window, location and a query/evidence version. Use single-flight jobs so simultaneous requests for the same missing evidence await one retrieval. Cache unsuccessful searches briefly, with event-sensitive expiry; a failure yesterday must not suppress a newly reportable event today.

**Scheduler.** For each proposed evidence-acquisition action, estimate which unresolved claim impressions it could resolve. Prioritize:

`expected newly resolvable weighted claim impressions / (search cost + estimated GPU time)`

Recompute the marginal gain after every result to avoid paying twice for overlapping coverage. Add a small exploration/audit allocation so low-frequency topics are not permanently excluded and the scheduler learns where evidence coverage is poor.

This resembles budgeted maximum coverage: choose sets of weighted elements under a cost budget. The classical paper supplies approximation algorithms for that formal problem. **The product heuristic is not automatically covered by its guarantees**: evidence yields are unknown, costs change, and proving all essential claims in a post can require complementary actions. Use claim-level coverage as a scheduling proxy and measure fully assessed posts separately. [Khuller, Moss and Naor, original paper](https://www.sciencedirect.com/science/article/pii/S0020019099000319)

The useful operational difference is that a new article may resolve several **distinct** claims about one event—not merely reposts of one claim. Conversely, similar wording must not merge “last year,” “today,” “police hit students” and “students hit police.” Cluster association only narrows retrieval; entailment and qualifier checks control verdict reuse.

**Tomorrow's demo.** Implement deterministic routing and a priority queue; do not train an adaptive router overnight. Show unique unresolved claims, claims resolved per fetched article, deduplicated search requests and exposure-weighted coverage. Use any OpenRouter allowance only for unresolved high-value cases or quality comparison; it is not required for the local bulk verifier.

## What 30–50% feed validation would actually require

Report both **all-feed coverage** and **checkable-post coverage**, plus whether only text or also the attached media was assessed. “No checkable claim,” “queued,” and “not enough evidence” do not count as validated. Neither does a check of a headline when an attached video carries the disputed assertion.

Let `f` be the fraction of feed impressions containing in-scope factual assertions and `r` the fraction of those fully assessed with adequate evidence. Then all-feed factual coverage is at most `f × r`. For example, if `f = 0.40`, reaching 30% requires assessing 75% of those factual impressions; 50% is impossible under that mix. These are arithmetic examples, not predicted feed statistics.

Measure on a consented, consecutively collected feed sample rather than selecting easy articles. Freeze the input sample and report counts, not just a percentage. Separate repeated appearances of already-reviewed claims from first-seen claims. The desired coverage may be achievable for news-heavy feeds; no cited paper demonstrates 30–50% across arbitrary X/Reddit feeds with this proposed system.

## Overnight build order and acceptance checks

1. **Useful core first:** ingest live articles linked from a fresh sample; retain source text/times; add sentence BM25 retrieval and the small local grounding model. Return source-linked assessments with explicit unresolved states.
2. **Make it generalize:** add claim extraction and dense/proposition retrieval, with original-text checks. Verify arbitrary incoming claims instead of matching only a manually written claim list.
3. **Make it economical:** add document-grouped batches, exact query deduplication and a shared event/claim queue; benchmark on the actual GPU machine.
4. **Prove the behavior:** test held-out paraphrases, entity swaps, negation, old-versus-current dates, numerical unit changes, unsupported causal conclusions and quoted allegations. If evidence is removed, the result must become unresolved unless other valid evidence remains.
5. **Measure the demo honestly:** audit a small random sample of published ratings and rejected candidates; record evidence sufficiency, wrong inheritance, retrieval misses, latency and all-feed coverage. A few dozen audited cases can reveal severe failures but cannot establish production accuracy.

Defer massive Wikipedia downloads, whole-web crawling, a trained adaptive policy, general AI-media detection and fully autonomous forensic video analysis. They are separate workstreams; the overnight product can demonstrate a growing evidence corpus, efficient local verification, claim-level reuse and measured real-feed coverage.

## Additional primary references

- [FActScore](https://arxiv.org/abs/2305.14251): atomic claim decomposition and checking against a knowledge source. Its reported low aggregate scoring error is not a claim that every individual fact verdict is 98% accurate.
- [VeriScore author code](https://github.com/Yixiao-Song/VeriScore): downloadable extraction and verification models, plus supported/contradicted/inconclusive modes. Useful component interfaces; its default external search stage is not a free bulk data source.
- [SAFE / LongFact](https://deepmind.google/research/publications/85420/): a multi-step search-based comparison system, useful for expensive audit samples rather than an obligatory path for every post.
- [PLAID paper](https://arxiv.org/abs/2205.09707): reports up to 7× GPU and 45× CPU speedup over vanilla ColBERTv2 in its experiments, including retrieval over 140M passages. Relevant later if retrieval scale becomes limiting; plain hybrid retrieval is sufficient to establish the demo's core behavior first.
