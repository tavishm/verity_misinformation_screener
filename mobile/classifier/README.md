# On-device forward classifier

This package routes a forwarded message locally. It never predicts truth. Its primary model averages real multilingual pretrained WordPiece embeddings and applies a tiny learned binary head estimating whether a sentence warrants an offered fact check. Exact social-only greetings, affection, and pride messages are skipped, while each clause is scored so an opinion or personal preamble cannot hide a factual claim. A detected link returns `offer_link_check` without fetching it. The app must wait for an explicit **Yes** before uploading message text or fetching a link.

## Model and provenance

`train_embedding.py` uses the pinned Apache-2.0 [static-similarity-mrl-multilingual-v1](https://huggingface.co/sentence-transformers/static-similarity-mrl-multilingual-v1/tree/b68f4122911bcffcd6e1f695f2d99cd6788972d8) model. It has 105,879 pretrained token vectors, supports Hindi among 51 listed languages, and uses mean pooling with no neural-network inference. We selected between its Matryoshka 64- and 128-dimensional representations only on a stratified 20% validation slice of the training partition. The 128-dimensional model won (validation F1 0.6859 versus 0.6596), so that dimension was fixed before evaluating the official test split.

Training labels come from pinned `two_class/train.json` and `test.json` files in the University of Texas at Arlington [ClaimBuster Spotter repository](https://github.com/utaresearch/claimbuster-spotter/tree/332bba0819d79f8e42d0a70277597dfe97036873/data/two_class). That repository declares GPL-3.0. The underlying ClaimBuster dataset is described in [Zenodo record 3609356](https://doi.org/10.5281/zenodo.3609356): U.S. presidential-debate sentences annotated as non-factual, unimportant factual, or check-worthy factual. This export uses the repository's binary train/test partitions; label `1` is the check-worthy class.

The official test partition is held out. Two normalized strings duplicated across the supplied partitions are removed from training. On 702 held-out rows, the quantized embedding gate at its validation-selected 0.60 threshold gives accuracy 0.8091, precision 0.8629, recall 0.7350, F1 0.7938, ROC AUC 0.8870, and average precision 0.8723. These are dataset metrics, not WhatsApp accuracy. The older n-gram fallback scores F1 0.8128 and ROC AUC 0.9044 on this English benchmark, so the embedding model is chosen for multilingual semantic coverage rather than a claim of better English benchmark accuracy.

The training reference contains a 128-dimensional int8 embedding table with per-dimension scales. Because mean pooling and the learned linear head are both linear, export algebraically fuses them into one float32 score per pretrained token: `mean(E[token]) · w = mean(E[token] · w)`. The Android projection is 424 KB, its UTF-8 WordPiece vocabulary is 872 KB, and the JSON head/metadata is 4.5 KB, for 1.30 MB of primary assets. This preserves the quantized embedding classifier's score to float32 tolerance, but it cannot output a general-purpose sentence embedding. `TinyEmbedding.java` implements the exact normalizer, greedy WordPiece tokenizer, pooled token-score inference, and sigmoid using only platform APIs. The full 13.55 MB matrix remains in the classifier directory as a reproducibility/parity reference and is excluded from Android assets. `forward_classifier_ngram_fallback.json` retains and accurately labels the prior 168 KB hashed n-gram model as a fallback; it is not the primary scorer.

## Portable scoring contract

1. Limit input to the first 5,000 Unicode code points.
2. Apply the pinned multilingual BERT uncased normalizer: clean control characters, space Chinese characters, Unicode lowercase, NFD decomposition, and accent removal.
3. Apply BERT punctuation splitting and greedy WordPiece with `##` continuations. Add token IDs 101 (`[CLS]`) and 102 (`[SEP]`) and cap at 256 tokens, preserving final `[SEP]`.
4. Look up each token's compiled scalar `dequantized_embedding[token] · head_weights` and mean-pool all values, including special tokens.
5. Compute `sigmoid(intercept + mean(token_scores))`. This is the algebraic equivalent of applying the linear head after mean pooling.

Routing takes the maximum learned score over the bounded whole message and clauses split at sentence punctuation or contrast conjunctions. Small deterministic cause, cure, death, prevention, change, announcement, reporting, numeric, and Hindi factual cues conservatively offer a check when the English debate model is uncertain. These cues only decide whether to offer a check; they do not assess truth.

`parity_fixtures.json` fixes decisions and scores for English, Hindi, links, greetings, opinions, and private text; its feature-index digests also guard the retained fallback. Java tests compare full compiled embedding scores and exact tokenizer IDs with the Python reference, including Hindi combining marks, whitespace, ASCII symbols, unknown words, and truncation. `LocalClassifier.java` and `TinyEmbedding.java` implement the primary contract without external libraries.

## Limitations

- Training text is English political debate, not WhatsApp forwards, private chats, Indian discourse, or phone-user validation.
- Hindi has no supervised classifier-label coverage. The primary tokenizer and pretrained embeddings support Hindi, and explicit Hindi routing cues conservatively offer uncertain claims, but that is not a Hindi WhatsApp evaluation.
- Text after 5,000 Unicode code points is not classified. This keeps CPU and memory work bounded, but a claim placed only after that boundary can be missed.
- “Personal” and “opinion” are routing labels, not sensitive-content guarantees. Rules will miss some private text and sometimes skip factual text.
- A factual offer means “ask the user whether to check,” never “this is false,” “this is true,” or “this is harmful.”
- Link detection does not access the link. Message content and URLs remain local until the user opts in.

Reproduce the primary export with `python3 mobile/classifier/train_embedding.py`. Reproduce the fallback with `python3 mobile/classifier/train.py`; test with `python3 -m unittest mobile.classifier.test_classifier` and the Java parity harness.

## Added spam/scam route

Android now checks a separate embedding-trained SMS spam head before check-worthiness routing. `train_spam.py` fits it using public UCI SMS data; `spam_training_report.json` records its held-out results and limitations. `SpamPolicy.java` combines its score with narrow request-pattern and cautionary-advice rules. This route returns `spam_warning`, not a factual truth rating. See `MODEL_FACTS.md` for data counts and the distinction between old SMS benchmark accuracy and unmeasured WhatsApp/scam accuracy. `python3 mobile/test_android_features.py` runs 21 synthetic spam-routing cases (including a model-only case), projection parity, extraction and acknowledgement regressions.
