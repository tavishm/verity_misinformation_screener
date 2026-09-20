# Media provenance, reuse, and synthetic-content handling

Research checked 19 September 2026. Architecture and quotas below are proposals, not measured throughput or coverage claims. The user's Jantar Mantar example is an illustrative requirement; this memo does not independently verify that incident or the age of the circulating videos.

## Core decision

**Reuse evidence about a media asset; inherit a verdict only for an equivalent claim in equivalent context.** The same authentic video can support one post and contradict another. An old protest video relabeled as today's protest is primarily a date/place verification problem, even if its pixels are completely authentic.

Maintain separate objects:

| Object | Important fields |
| --- | --- |
| Post observation | Platform/post ID, captured text and media URLs, observation time, content version, quoted-post relationship |
| Media asset/version | Exact digest, perceptual signatures, matched regions/segments, fingerprints' algorithm versions, audio identity, dimensions/duration |
| Media provenance | Earliest **verified observation**, evidence URLs, archived/publication timestamps with their uncertainty, claimed creator, verified event date/place if known, transformations |
| Claim | Proposition, entities, action/quantity, time, place, attribution, negation/stance, attached media's evidentiary role |
| Assessment | Claim ID, verdict, cited evidence and excerpts, confidence/calibration information, review time, validity interval, reviewer/model/policy version |
| Dependency edge | Which evidence or assessment was reused, why equivalence passed, and what changes invalidate the dependent result |

“Earliest found” is not automatically “original creator” or “recorded on this date.” TinEye explicitly says its crawl date is not the date an image first appeared on the page. Display a bounded statement such as “This footage was already online by 12 January,” when that is what the evidence establishes. [TinEye date explanation](https://help.tineye.com/article/246-can-i-sort-my-results)

## Low-cost retrieval cascade

1. **Look up the post and referenced originals.** A canonical post ID, quoted-post ID, linked article URL, or media URL may already resolve to evidence. Normalize tracking parameters while retaining content versions; a URL alone is not immutable identity.
2. **Hash available media bytes.** SHA-256 catches exact copies. Save the original digest before decoding or normalizing. Hashes identify bytes, not truth. Avoid downloading every full video: start with an authorized thumbnail or bounded sample and progressively fetch only priority candidates. Account for downloads, decoding, storage, and CPU in cost measurements.
3. **Retrieve visually similar candidates locally.** Use a perceptual image hash index; add crop/rotation variants only when needed. Meta's open-source PDQ produces 256-bit image signatures. Its vPDQ matches videos using shared similar frames; TMK+PDQF produces substantially larger video signatures. These are possible building blocks, not evidence that a particular deployment will meet the cost target. [Meta implementation](https://github.com/facebook/ThreatExchange)
4. **Verify a candidate match.** Compare the relevant crop/region and, for video, a sequence of corresponding frames. Track overlap coverage and time offsets. A single matching frame or a semantic embedding hit is insufficient: a compilation can mix old and new clips. Keep replaced audio and changed captions as separate variants. Never propagate identity through an unchecked chain of approximate matches.
5. **OCR screenshots and overlays.** Extract claimed author, quoted text, date, visible URL, and captions. Search locally for distinctive text plus platform IDs; then retrieve the actual source where possible. A screenshot does not establish that the depicted person posted it. Treat OCR as uncertain extraction, including Hindi, English, and code switching; retain source crops for verification. Small changes to “not,” numbers, dates, or usernames must prevent verdict inheritance.
6. **Use audio selectively.** Preserve audio-to-video alignment; transcribe important claims only for unresolved priority clips. A reused soundtrack proves audio reuse, not that the accompanying scene is the same event. Chromaprint is designed for near-identical audio and explicitly is not a general-purpose fingerprinting solution; do not assume it reliably identifies short, noisy, altered protest clips. [Chromaprint scope](https://github.com/acoustid/chromaprint)
7. **Search the web for only unresolved, valuable cases.** Submit a small number of representative frames/crops to a documented API, then inspect matching pages and their chronology. Google Cloud Vision Web Detection returns full/partial image matches and pages containing matching images. Those are retrieval candidates requiring verification, not a source-of-truth or original-upload guarantee. [Web Detection API](https://docs.cloud.google.com/vision/docs/detecting-web)
8. **Escalate a bounded queue.** Research time/place, original statement, source footage, or manipulation only where evidence could materially change the assessment. Deduplicate in-flight work across users. Apply per-claim locks, query/frame/token/time limits, and a daily spend ceiling.

An initial implementation can use exact hashes and a small perceptual hash index before adding embeddings. It should record `no_match_in_our_index`, not infer `new_media`. A cold index has little historical coverage: seed it using permitted fact-check/reference material and retain verified matches as they are encountered. Media databases do not become comprehensive merely because their hashes are cheap.

## Safe inheritance rule

```text
candidate = retrieve_by_post_id_or_claim_or_media(post)
media_match = verify_relevant_regions_segments_and_audio(post, candidate)
claims = extract_claims(post.text, overlays, quoted_context, media_role)

for claim in claims:
    previous = find_assessed_equivalent_claim(claim)
    if previous exists
       and entities, quantities, time, place, attribution, negation agree
       and relevant media relationship agrees
       and evidence is still valid
       and there is no contradictory new evidence:
        reuse previous with explicit evidence dependencies
    else:
        reuse applicable evidence only; assess the changed claim
```

Canonical claim matching is a retrieval aid, not a proof of equivalence. Start with conservative field checks, require explicit evidence for missing context, and abstain on uncertain equivalence. A meme's opinion label must not hide an embedded factual assertion. A corrective quote of a false claim must not receive the quoted claim's false verdict. Do not merge posts just because their wording or topic resembles each other.

Example: a clip is verified to have appeared eight months earlier. Post A says “Footage from last year's protest”; post B says “Students attacking police today.” Both use the same frames. Reuse the older publication evidence; evaluate each caption separately. The earlier appearance contradicts B's implied filming date if identity and chronology are established. It does not, by itself, establish the participants' identities, location, who initiated the confrontation, or the complete event narrative. “Old footage presented as current” is a clearer explanation than “fake video.”

Corrections must traverse dependency edges and invalidate inherited assessments. Keep append-only assessment versions and a visible correction trail. Time-sensitive propositions need expiry or event-triggered rechecks; historical provenance can persist longer. A post edit, new overlay, newly attached media, changed quoted post, or conflicting source must reopen the relevant claim.

## What reverse search costs

Google's published Web Detection rate is **$3.50 per 1,000 images** for the main tier after the first 1,000 free units per month. Features are charged separately, so OCR is additional if purchased. Other cloud resources also cost extra. [Cloud Vision pricing](https://cloud.google.com/vision/pricing)

| Workload | Web Detection alone, ignoring monthly free allowance |
| --- | ---: |
| 50,000 unique images/day, one request each | $175/day |
| 50,000 videos/day, three frames each | $525/day |
| 300 image/frame requests/day | $1.05/day |

These are arithmetic estimates, not all-in operating budgets. A reasonable experiment inside a **total** $10/day envelope might reserve $1.05/day for 300 requests: at most 100 videos if each consumes three frames, fewer if retries/crops are needed. Decide the final allocation alongside platform access, model/search spending, compute, storage, and monitoring. Cache frame results and count every attempted paid request conservatively. Do not spend the full daily budget on media while assuming research is free.

TinEye has an automation API; its official purchase guide illustrates 5,000 searches for $200, equivalent to $0.04/search, and describes optional automatic replenishment. Treat that as an indicative bundle example and verify checkout pricing before purchase. At that example rate, 50,000 searches would cost $2,000. TinEye MatchEngine is a separate service for a customer's own collection and should not be confused with internet reverse search. [TinEye purchase guide](https://help.tineye.com/article/275-signing-up), [MatchEngine plans](https://help.tineye.com/article/211-matchengine-pricing)

Use documented APIs in the production budget. This research did not establish a supported, generally available Google Lens automation API; a consumer Lens workflow should not be assumed to be a free, unlimited production dependency. No reverse-search provider offers a justified guarantee that no match means footage is original or authentic.

## AI-generated content and harmful deception

Keep three separate questions: **Was AI used? What factual claim is being made? What harm could a mistaken assessment cause?** An openly labeled illustration can be harmless; authentic footage with a false caption can be dangerous. Neither AI presence nor graphic content is itself a truth verdict.

- **Check provenance early when accessible.** Validate C2PA signatures, content binding, trust chain, and the actual assertions. Keep `valid`, `invalid`, `untrusted_signer`, and `absent` distinct. C2PA can record creation/editing history, and soft bindings can help recover removed credentials. Missing credentials are not evidence of fabrication. [C2PA FAQ](https://c2pa.org/faqs/)
- **Read provenance narrowly.** A valid signature supports an assertion about provenance/integrity under its trust model; it does not certify that a scene was unstaged, a caption is accurate, or a depicted event occurred as described. C2PA's own explainer distinguishes provenance from factual truth. [C2PA explainer](https://spec.c2pa.org/specifications/specifications/1.3/explainer/_attachments/Explainer.pdf)
- **Check supported watermarks when a supported interface is available.** Google's SynthID identifies content generated or edited with participating Google tools. Its public description offers checking through Gemini and describes the separate detector portal as being tested with journalists/media professionals. This does not establish a general-purpose production detector API. No detected watermark means only that this signal was not detected, not that no AI was used. [Google SynthID](https://deepmind.google/models/synthid/)
- **Use forensic classifiers as prioritization signals.** Generic synthetic-content detectors must be evaluated on unseen generators, re-encoded social videos, screenshots, crops, and the languages/media encountered in the product. NIST describes generalization and real-world noise limitations. A detector score must not automatically become “surely false.” Disagreeing/weak signals remain inconclusive. [NIST synthetic-content report](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.100-4.pdf)
- **Prioritize concrete risk.** Claimed violence, imminent emergencies, identity impersonation, scams, and fabricated quotations merit faster evidence review because mistakes can matter quickly. Public-figure status or political viewpoint must not determine truth. First retrieve known media/claims and corroborating source material; run costly forensic models only where they could resolve a relevant question.

Do not put every image through several expensive detectors. Cheap provenance parsing and known-media retrieval run once per distinct asset; deeper detection is reserved for high-impact unresolved factual presentations. A tiny budget cannot imply staffed human review on demand. If no human review capacity exists, label unresolved cases as unresolved and make that limitation explicit.

## User-facing behavior and validation

The 15-second target is realistic as a **response** target, not a promised complete investigation. Return a cached, source-backed result where available; otherwise show “Review in progress” and any established provenance finding. “Not checked,” “No checkable factual claim,” “Review in progress,” and a completed “Unsure” assessment are different states. Never fill a truth rating merely to meet the coverage target. Manual request emails require the user's opt-in/address and should contain the final evidence, not speculative interim accusations.

Before permitting broad inheritance, evaluate a labeled set of genuine reposts, recaptioned old videos, crops, compilations, screenshots with altered text, translations, quote rebuttals, satire with embedded facts, and audio swaps. Measure **incorrect verdict inheritance**, match precision, misses on recaptioning, source chronology errors, per-unique-asset cost, and latency. Use temporally separated tests and unseen transformation/generator families. NIST's image evaluation includes false-positive-sensitive and calibration metrics, reinforcing that headline accuracy alone is inadequate. [NIST image challenge](https://ai-challenges.nist.gov/t2i)

The first product advantage should be reliable recognition of already-investigated claims and reused media. Broad “AI detection” is an expensive, uncertain supplement; context-aware evidence reuse directly addresses the old-video problem and improves with each verified case.
