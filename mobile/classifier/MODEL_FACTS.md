# Current phone classifier

The active gate is now a contextual multilingual transformer, rather than the earlier static token-average model. It screens messages locally; it does **not** decide whether a claim is true.

| Item | Current implementation |
|---|---|
| Base | `sentence-transformers/distiluse-base-multilingual-cased-v2` |
| Architecture | Six DistilBERT layers; 512-dimensional sentence embedding; three-class routing head |
| Parameters | 135,129,347 total; 42,922,499 fine-tuned; word/position embeddings frozen |
| Training | 6,573 unique examples: public English SMS/claims plus synthetic English, Hindi and Romanized Hindi messages |
| Validation / test | 1,296 / 1,605 examples, with translation groups kept in one split |
| Phone model | Int8 ONNX, 135,747,129 bytes; screening has no API cost |
| Phone timing | Samsung SM-S721B: uncached median 6–7 ms, p95 9–10 ms across two small diagnostic runs; initial load 494–836 ms |
| Role | Skip personal messages, offer a fact check, or warn of possible spam; app language does not change screening |

Full training details, artifact digest, source-specific results and limitations are in [contextual/README.md](contextual/README.md). These are development measurements, not a representative WhatsApp accuracy study. Hindi/Hinglish training is partly synthetic, and Romanized Hindi still has misses. The old 0.09 ms timing below is **not** the current model's speed.

## Earlier static classifier (retained fallback)

The on-phone gate estimates check-worthiness. DeepSeek does the later source-based factual research after approval.

| Item | Actual implementation |
|---|---|
| Pretrained model | sentence-transformers/static-similarity-mrl-multilingual-v1 |
| Upstream data | Model card lists 62,698,210 training examples across 18 datasets and 51 languages |
| Upstream training | Sentence-pair contrastive learning (MultipleNegativesRankingLoss) with Matryoshka training across embedding sizes |
| Full upstream table | 105,879 token vectors × 1,024 dimensions = 108,420,096 stored parameters |
| Our features | First 128 dimensions, mean-pooled; pretrained embeddings kept frozen |
| Our fitted head | Class-balanced logistic regression: 128 weights + one bias = 129 learned parameters |
| Task data | 7,974 ClaimBuster training sentences after removing two train/test overlaps; 1,994 check-worthy and 5,980 other sentences |
| Selection | Stratified 80/20 training split selected 128 vs 64 dimensions and threshold; then refitted on all 7,974 training sentences |
| Held-out test | 702 separate sentences; 80.91% accuracy, 86.29% precision, 73.50% recall, F1 0.7938 at threshold 0.60 |
| Deployed arithmetic | Precompute each token's embedding dot classifier weights; average token scores and apply sigmoid |
| Phone model values | 105,879 compiled token scores + one bias = 105,880 values; metadata retains the small head too |
| Phone assets | About 1.30 MB including vocabulary; score table alone 423,528 bytes |

The 62.7 million upstream examples were used by the original model authors, not collected or trained by this project. Our classifier labels are English presidential-debate data. No Hindi, Hinglish or WhatsApp accuracy claim follows. The actual offer route also uses a lower uncertainty threshold and a few greeting/link/factual cues, so the test accuracy above is not end-to-end product accuracy. Static embeddings provide word-level learned semantics, without contextual transformer reasoning.

Sources: [upstream model card](https://huggingface.co/sentence-transformers/static-similarity-mrl-multilingual-v1), [pinned ClaimBuster export](https://github.com/utaresearch/claimbuster-spotter/tree/332bba0819d79f8e42d0a70277597dfe97036873/data/two_class), and this repository's train_embedding.py and forward_classifier.json.

## Local spam warning (20 September update)

A separate 129-parameter logistic head uses the same frozen 128-dimensional multilingual embedding. Its public training source is the [UCI SMS Spam Collection](https://archive.ics.uci.edu/dataset/228/sms%2Bspam%2Bcollection), Almeida & Hidalgo (2011), DOI 10.24432/C5CC84, CC BY 4.0. The downloaded file contains 5,574 rows; normalized exact deduplication leaves 5,159 messages, split into **3,611 training, 774 validation, 774 test** before fitting. No phone or WhatsApp messages were used for training.

At the conservative 0.99 model threshold, held-out SMS precision is **90.91%** and recall **82.47%** (8 false positives, 17 false negatives). The validation set did not meet the training script's 97% precision target, so the documented fallback threshold is used. This is an old English SMS benchmark, not measured WhatsApp/scam/Hindi accuracy. The final Android route additionally requires commercial wording for model-only alerts, adds narrow credential/prize/phishing risk patterns, and suppresses cautionary advice. These policy additions have synthetic regression tests, not a representative accuracy evaluation.

The extra compiled score table is 423,528 bytes of values plus a 12-byte header. Vocabulary is shared on disk; total primary model assets are approximately **1.73 MB**, and the updated signed APK is approximately **1.43 MB**. Training and screening use no paid API calls. Spam risk and factual accuracy remain separate: a supported fact cannot remove the local spam warning.

Reproduce training: `python3 mobile/classifier/train_spam.py`. Exact splits, archive digest, confusion matrices and limitations: `mobile/classifier/spam_training_report.json`. Regression checks: `python3 mobile/test_android_features.py`.
