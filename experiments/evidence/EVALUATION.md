# Evidence-derived development checks

Run from the repository root:

```bash
python3 -B demo/evaluate.py --validate-only
python3 -B demo/evaluate.py --wait-model --output experiments/evidence/eval_results.json
```

The local backend and model must be running. The harness reads `demo/data/local-token` privately and sends it only to a loopback API. It submits cases serially, checkpoints after every result, and returns exit code 1 for expectation mismatches or 2 for setup/runtime errors. `--case CASE_ID` selects a case; repeat it to select several. Stopping the harness does not cancel an already submitted backend job.

`eval_cases.json` contains 14 handwritten assertions selected after reading the pinned 30-document corpus, before their first evaluation. Most resolvable cases use RBI primary feed text. Two Hindi cases test explicitly attributed statements in Alt News articles. Amount/date mutations conflict with explicit source text. The Delhi Metro assertions deliberately lack evidence in this corpus; their real-world truth is not adjudicated. Expected answers and reference passages are kept outside model requests.

The Hindi RBI case uses an English source and tests cross-language retrieval. There are no Hindi RBI documents in this snapshot. The media case supplies only `has_media: true`, testing whether the API leaves media unchecked; it provides no actual image or video.

Mechanical checks require the expected number and multiset of claim verdicts, the expected source/verdict pairing, quotes present in the pinned original documents, and appropriate scope flags. They do not independently establish semantic correctness. Valid differences in claim splitting can produce a mismatch requiring human review. An incorrect extraction with coincidentally matching verdicts and citations can still pass. Read the saved extracted claims and explanations when interpreting results.

Results include cache/coalescing flags, wall latency, raw job results and model-reported tokens. Fresh-job token totals exclude cached and coalesced results. Failed jobs may consume tokens without reporting usage, so these totals are incomplete GPU accounting. Repeated runs may mostly measure cache latency; restart the task-owned backend for a fresh inference run and record that choice.

This source-derived development set is not held out, representative, or independent across all cases. It measures neither social-feed coverage nor independently adjudicated accuracy. Never describe its match rate as “the percentage of posts fact checked.”

## Prepared-demo configuration

`eval_results_final.json` records source-ID citations, original-sentence preservation, numeric/date support vetoes, a short kind/query router, and Qwen's suggested non-thinking sampling parameters (temperature .7, top-p .8, top-k 20; seed 1729 per call). Its source/output review is `eval_semantic_audit_final.json`.

All 14 development checks completed uncached (median 7.597 s; maximum 13.611 s). Twelve matched source/verdict expectations; two conservatively abstained. No incorrect decisive verdict was observed in this set. One generated explanation changed a planned future auction to a completed event. Consequently the web page and extension show original passages without generated explanations; raw model explanations remain in evaluation/API data for inspection. This presentation change does not improve the underlying model's reasoning or convert the evaluation into an independent accuracy result.

Earlier outputs are preserved as `eval_results_initial.json`, `eval_results_revised.json`, and `eval_results_source_ids_greedy.json`, with corresponding separate audits. Changing the protocol in response to this set makes it a development/regression set; a fresh sampled-feed evaluation is still necessary.
