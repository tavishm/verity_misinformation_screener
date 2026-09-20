# Contextual message screening

This model routes a message to **personal**, **offer a fact check**, or **possible spam**. It never predicts truth. Screening and tokenization run on the phone in English, Hindi and Romanized Hindi, independently of the app's display language.

## Model and training

Base: [DistilUSE multilingual cased v2](https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v2), Apache-2.0, pinned revision `bfe45d0732ca50787611c0fe107ba278c7f3f889`. Six DistilBERT transformer layers, 768 hidden dimensions, 119,547 WordPiece vocabulary entries, mean pooling, a 512-dimensional dense/tanh projection, normalized embeddings and a three-class head.

There are **135,129,347 parameters**, of which **42,922,499 were fine-tuned**. Word/position embeddings are frozen. Training used six epochs of 7,200 stratified sampled examples on a local GTX 1080 Ti. Validation selected epoch five. The checkpoint search and final conservative int8 threshold calibration are separate, and neither uses test labels.

The actual training set has **6,573 unique examples**:

- 3,611 deduplicated English UCI SMS examples.
- 1,594 ClaimBuster positive training examples. Its negative class is excluded from training: “not check-worthy” includes public facts and does not mean “personal.”
- 1,014 synthetic English/Hindi/Hinglish messages, plus 354 training-only compositions joining a greeting and a public claim.

There are 1,296 validation examples and 1,605 test examples. Translation groups share one split; compositions use training parents only; exact text duplicates across splits are removed. Source snapshots and hashes should accompany any new trained version. The original public ClaimBuster test is retained for comparison, but its negative class has a different meaning from this product's personal-message class.

The 1,266 revised synthetic examples were generated with DeepSeek V4.1 Flash for **$0.05649036**. No phone messages were used. `generation-costs.jsonl` records reservations and provider-reported charges. Quality filters reject wrong-script Hindi and English copies labelled Hinglish. This data is **not human-labelled WhatsApp data**. The earlier local 4B teacher produced poor translations; `synthetic-training.jsonl` and `baseline-v1/` remain development records and are not inputs to the deployed model.

## Export and evaluation

The deployed ONNX file is **135,747,129 bytes** (about 129.5 MiB), SHA-256 `0b69329eaf329868e8f00f93d8ba6c286a09dae15e4fac0785f7610de766251e`. MatMul and embedding Gather weights are quantized. Final thresholds are 0.8 for skipping personal messages and 0.9 for possible spam. These are routing thresholds, not calibrated truth probabilities. Narrow credential/payment patterns, a link-only guard and a public-health recall guard are additional app policy; model-only reports do not measure that entire pipeline.

`quantized-test.json` contains source-specific confusion matrices. Among 351 public ClaimBuster positive test examples, four were skipped; among 677 legitimate SMS test examples, 653 were skipped, 12 offered a check and 12 flagged as spam. These are different tasks and distributions from WhatsApp. Synthetic English/Hindi results are optimistic; Romanized Hindi still has missed claims. Do not advertise these as real-user accuracy or universal coverage.

`challenge.json` contains 114 fixed **development diagnostics**, not an untouched accuracy benchmark. `ContextGateTest` compares the final Android pipeline with the previous gate, verifies all 114 token sequences and quantized scores, and measures uncached inference. On the owner's Samsung SM-S721B, initial loading took 836 ms and uncached inference had a 7 ms median and 10 ms p95 in the first measured run. This is a small device test, not a battery or sustained-load benchmark.

## Reproduction

The known Python environment is torch 2.4.1, transformers 4.45.2, tokenizers 0.20.1, safetensors 0.4.2, numpy 1.23.5, scikit-learn 1.5.1, onnxruntime 1.17.1 and onnx 1.17.0. ONNX was installed in `.cache/context-python`; the other existing packages were not modified. The trainer explicitly uses ordinary attention, avoiding an incompatible system FlashAttention binary.

```bash
python3 mobile/classifier/contextual/finetune.py
PYTHONPATH=.cache/context-python python3 mobile/classifier/contextual/export_finetuned.py
python3 mobile/tooling/standalone_build.py :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Base artifacts and public dataset snapshots are in `.cache/context-gate/`. The selected trainable checkpoint is `finetuned.pt`; the exported model and vocabulary are packaged in `mobile/android/assets/`. The Android test APK, rather than the main app, holds diagnostic examples. `generate_revised_data.py` makes paid calls only when explicitly run and has a persistent $0.60 budget; training/export do not use an API.

Remaining validation includes representative, consented Hindi/Hinglish messages and independent labels. Treat this as a tested pilot model, not a claim of production-level accuracy.
