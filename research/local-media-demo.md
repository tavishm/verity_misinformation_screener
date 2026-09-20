# Local multimodal demo: bounded route and verified building blocks

Checked 19 September 2026. This is a proposed demo architecture, not a throughput measurement. No models were downloaded or jobs started for this research memo. The available eight RTX A6000s change local inference capacity; they do not supply missing source evidence or establish general-feed coverage.

## Recommendation for tomorrow

Build one evidence-backed path covering ordinary text claims, text screenshots, and identifiable reused images/short clips. Process a representative feed slice in advance, show the cached results in the extension, and allow live manual requests through the same bounded queue. Clearly distinguish preprocessed results from new investigations. Do not add a generic AI detector to the critical path.

Use a small text model for extraction, CPU/mobile OCR, local image/frame matching, a small multilingual text retriever, and one vision-language worker only when visual context matters. Retain explicit uncertainty for unseen footage, ambiguous quotations, or missing evidence. Source fetching and evidence validation—not whether an 8B model fits in memory—are likely the main limitations on conclusive coverage.

## Practical local model shortlist

Memory below is **estimated weight storage**, calculated from rounded parameter counts and dtype; it excludes activations, KV cache, image tokens, allocator reserve, serving graphs, and batching. Runtime peak memory must be measured on the actual server. License entries reflect the official repository/model-card declarations, not a review of every dependency or training asset.

| Role | Concrete candidate and official source | License | Weight-only planning estimate | Demo decision |
| --- | --- | --- | --- | --- |
| Claim extraction and source-grounded explanation | [Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507), 4B, non-thinking model | Apache-2.0 | ~8 GB BF16 | Default text worker; cap input/output lengths |
| Difficult screenshot/visual claim extraction | [Qwen3-VL-8B-Instruct](https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct); repository reports ~9B total including visual components | Apache-2.0 | ~18 GB BF16 | One 48 GB card, initially one request at a time, bounded image tokens |
| Text retrieval and cross-language candidate clustering | [Qwen3-Embedding-0.6B](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B), 100+ languages, output dimensions up to 1024 | Apache-2.0 | ~1.2 GB BF16 | Default; begin with full 1024 dimensions |
| Evidence relevance ranking | [Qwen3-Reranker-0.6B](https://huggingface.co/Qwen/Qwen3-Reranker-0.6B) | Apache-2.0 | ~1.2 GB BF16 | Optional top-20 → top-5 evidence step; relevance is not support |
| Cheap evidence/claim relationship check | [mDeBERTa-v3-base-mnli-xnli](https://huggingface.co/MoritzLaurer/mDeBERTa-v3-base-mnli-xnli), ~0.3B | MIT | ~1.2 GB FP32 | Optional baseline; card flags FP16 issues, so start FP32 |
| Cross-modal retrieval candidates | [SigLIP2-base-patch16-224](https://huggingface.co/google/siglip2-base-patch16-224), repository reports ~0.4B | Apache-2.0 | ~1.6 GB FP32 | Add after exact/perceptual matching works; never use similarity as a truth score |
| Selective speech extraction | [Qwen3-ASR-0.6B](https://huggingface.co/Qwen/Qwen3-ASR-0.6B), repository reports ~0.9B total; includes English and Hindi | Apache-2.0 | ~1.8 GB BF16 | Optional if audio is central; defer otherwise |

Prefer already-working local checkpoints over switching models for a benchmark headline. The NLI checkpoint is an older, inexpensive baseline, not a claim that it is the current best verifier. Its card reports Hindi XNLI accuracy of 0.771 versus English 0.883; those are benchmark results, not expected factuality accuracy on social posts. NLI asks whether a provided premise supports a hypothesis. It cannot establish that the premise is correct, current, independent, or complete. [NLI model card](https://huggingface.co/MoritzLaurer/mDeBERTa-v3-base-mnli-xnli)

PaddleOCR's [PP-OCRv5 language models](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.en.md) include explicit English and Devanagari mobile recognizers. Select the language model; the default configuration is not interchangeable with full multilingual support. The [recognition documentation](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/module_usage/text_recognition.en.md) lists a 7.5 MB Devanagari recognition model; that is model file size, not whole-pipeline memory. Repository code is [Apache-2.0](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE). Use the small OCR path first and vision-language extraction when ordinary OCR fails.

## Minimal media route

1. **Normalize a post snapshot.** Save post ID, exact observed text, quote/reply context, timestamps, canonical links, and content hash. Separate what the post alleges from what media visibly shows. Preserve an unchanged source span for every extracted claim.
2. **Cheap media processing once per unique asset.** Compute a cryptographic digest and local perceptual signature. OCR screenshots and visible captions once, saving confidence, boxes, and language. Parse available provenance metadata. A low-confidence digit, date, or negation prevents automatic score inheritance.
3. **Bound video work.** For the initial demo, support authorized clips up to 90 seconds. Sample at most eight timestamps across the clip for candidate retrieval, preserving original time offsets. For selected unmatched clips, scene-change sampling can replace redundant frames; [PySceneDetect](https://www.scenedetect.com/docs/api/detectors.html) provides content/adaptive change detectors. Decoding still costs work, and sparse sampling does not prove the entire clip is unchanged. Longer or speech-dependent clips become explicit deferred cases.
4. **Retrieve local matches.** Exact digest → PDQ candidates → corresponding frame/region verification. Meta's [PDQ/vPDQ tools](https://github.com/facebook/ThreatExchange) provide 256-bit image hashes and frame-based video matching under a BSD license with noted file-level exceptions. Do not require deploying the entire moderation platform to use the hashing components. For a small demo index, simple CPU matching is sufficient; [FAISS](https://github.com/facebookresearch/faiss) is an existing path for larger vector/binary indexes.
5. **Extract only needed claims.** Give the text worker the post, OCR, and reference spans. Ask for a maximum of three material claims with entity, quantity, date/place, attribution, negation, and ambiguity fields. Use the vision worker for bounded observations or comparing candidate crops/frames, not guessed dates, identities, or locations. If a post has more material claims, disclose partial checking instead of stamping the whole post as true.
6. **Retrieve evidence by claim cluster.** Search cached source text and known checked claims; fetch fresh public evidence only on a cache miss or expiry. Rank relevant excerpts, preserve URLs and source timestamps, then assess support/contradiction/unresolved. A source saying someone made a claim supports the attribution, not necessarily the claim itself.
7. **Show narrow results.** Prefer “This clip was online by [date]; it does not show a newly filmed event” when chronology is established. The same media with a changed date or location starts a new claim assessment. Cache the supporting provenance and propagate later corrections through explicit dependencies.

Example extraction contract:

```json
{
  "source_span": "exact post or OCR span",
  "claim_text": "standalone claim preserving scope",
  "entities": [],
  "time_claim": null,
  "place_claim": null,
  "stance": "asserted|quoted|denied|unclear",
  "media_role": "evidence|illustration|unclear",
  "needs_audio": false,
  "ambiguity": null
}
```

Null context remains unknown; never fill missing date/place using a visually plausible guess. Microsoft [Claimify](https://www.microsoft.com/en-us/research/blog/claimify-extracting-high-quality-claims-from-language-model-outputs/) provides a useful decomposition principle: identify factual content, resolve context-supported ambiguity, then extract standalone propositions. Its study concerns language-model answers, so the proposed social-post adaptation needs its own evaluation. A single bounded extraction call is a demo simplification, not a reproduction of its published multistage method.

## Cross-modal clustering without false inheritance

Maintain three related indexes: `media_asset`, `event`, and `claim`. These are distinct levels:

- A **media cluster** means verified overlapping image regions/video segments.
- An **event cluster** means candidate reports about a related occurrence, with explicit entity/date/place constraints.
- A **claim cluster** means equivalent assertions, including attribution and negation. Only this can reuse a still-valid assessment after an equivalence check.

Retrieve text/OCR candidates with multilingual embeddings and media candidates with hashes. Optionally fuse SigLIP image/text retrieval rankings for recall. Check explicit date/place/name/number conflicts before joining candidates. Translation or transliteration can help retrieval but must retain the original. “The minister resigned” and “The minister denied resigning” may be close in embedding space and must remain separate claims.

Existing research supports using retrieved external evidence, but gives no off-the-shelf general-feed truth engine. [SNIFFER](https://github.com/MischaQI/Sniffer) studies explainable out-of-context detection with specialized tuning and external knowledge; it is a reference design, not a tomorrow dependency. [Similarity over Factuality](https://arxiv.org/abs/2407.13488) finds simple similarity baselines competitive on benchmark datasets and warns that shortcut success can avoid logical factuality. [VERITE](https://github.com/stevejpapad/image-text-verification) is useful for testing image-caption mistakes and unimodal bias; its dataset access may require an institutional email. Do not make gated benchmark access a demo blocker.

## GPU and cost plan

Assuming these are the original RTX A6000 cards, NVIDIA specifies [48 GB memory and 300 W board power](https://www.nvidia.com/content/dam/en-zz/Solutions/products/workstations/nvidia-rtx-a6000-datasheet.pdf). Confirm actual devices/free memory before running anything. Start with one GPU for text and one for vision; place OCR/retrieval on CPU or an additional card only if measurements justify it. Reserve remaining devices or add independent worker replicas for throughput. Small models do not require eight-way tensor parallelism, and eight 48 GB cards are not automatically one contiguous memory pool.

Initial unmeasured limits: text context 4–8K tokens, output 256–512 tokens; vision 1–4 images per call with a processor-controlled pixel/token cap; ASR only for relevant speech, with clip limits. Exact limits should follow runtime smoke tests. Pin a tested model revision, processor, and serving/runtime version; do not use obsolete version snippets from model cards blindly.

At the board-power rating, eight cards running for 24 hours imply **57.6 kWh/day**, before host/cooling. At an illustrative $0.10/kWh, that is $5.76/day for GPU energy alone. Actual draw/utilization and tariff may differ. Local inference replaces per-token API spending with hardware/energy costs; the $10/day accounting must say whether those are included.

## Demo evidence and unsolved cases

Use two clearly separated sets: a small curated demonstration of known success/failure modes, and an unfiltered consecutive feed sample to measure coverage. Aim for 200–500 representative posts in the evaluation sample, subject to authorized access, and manually inspect a stratified subset of outputs. Measure source-backed conclusive ratings/all encountered posts, completed-but-unsure reviews, no-factual-claim skips, unsupported media, and pending jobs separately. Also report cache hit rate, duplicate-claim reuse rate, incorrect inheritance, extraction omissions, latency percentiles, GPU time, and external calls.

**30–50% of general-feed posts is a target to test, not a result to promise.** If only 30% of posts contain checkable assertions, 50% conclusive factual coverage of all posts is impossible without changing the denominator or mislabeling nonclaims. Source-backed coverage equals the checkable fraction multiplied by the fraction actually resolved; GPUs alone do not change that identity. Never increase apparent coverage by assigning “unsure” without an investigation or calling opinion filtering fact-checking.

Explicit unsolved/deferred cases: unseen footage without external provenance; exact recording location/date; speaker identity and edited/dubbed audio; short inserted video segments missed by sampling; ambiguous satire and quote rebuttals; illegible multilingual OCR; inaccessible/private/deleted originals; breaking events with conflicting or absent primary evidence; medical/legal/financial causal claims; long multi-claim threads; and coordinated copied reports that are not independent corroboration. A trustworthy demo shows these boundaries and the evidence inspected.
