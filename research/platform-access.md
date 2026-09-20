# Platform access feasibility

Verified against official documentation on 2026-09-19. This memo distinguishes technical capabilities and published platform requirements from permission for this particular product; no access approval or commercial quote has been obtained.

## Decision

Start with a tightly scoped, approved pilot and a reusable evidence database. Do not budget for collecting one million new social posts each day at $10/day. A browser extension can technically read visible posts, but that does not establish permission to harvest them into a shared commercial corpus. Data access is an independent product gate, not a problem solved by cheaper models.

## X: cost and discovery

X publishes pay-per-use pricing of **$0.005 per Post returned**, with a self-serve ceiling of **3 million Post reads per monthly billing cycle**; larger volume requires Enterprise. Resources are normally charged once within each UTC day, with deduplication described as a soft guarantee. Expanded user resources have separate pricing. Current rates must be checked in the developer console before purchase. [X pricing](https://docs.x.com/x-api/getting-started/pricing)

Arithmetic using that published Post price:

| Unique X Post reads per day | Post access alone |
| --- | ---: |
| 400 | $2/day |
| 2,000 | $10/day |
| 10,000 | $50/day |
| 1,000,000 | $5,000/day |

The million-post example is a unit-price extrapolation, **not an available self-serve plan or Enterprise quote**. At that sustained volume, roughly 30 million reads/month also exceeds the self-serve ceiling. Screening out opinions after retrieval does not refund acquisition cost.

Post impressions are available as `public_metrics.impression_count`; video views are a different metric, `media.public_metrics.view_count`. Use post impressions for the requested one-million-view rule. [X metrics](https://docs.x.com/x-api/fundamentals/metrics)

The current search reference supports minimum likes, replies, and reposts (`min_likes`, `min_replies`, `min_reposts`), plus account, language, and topic filters. It does **not document a minimum-impressions operator**. Therefore a proposed implementation would obtain a limited candidate set and inspect impressions afterward; it cannot claim to find every post above one million impressions cheaply. This is an inference from the documented interface, not proof that no negotiated Enterprise capability exists. [Search operators](https://docs.x.com/x-api/posts/search/integrate/operators)

Published per-app limits include recent search at 450 requests/15 minutes and up to 100 results/request; multi-post lookup at 3,500 requests/15 minutes; filtered stream at one connection and up to 250 posts/second. Throughput limits do not waive billing or monthly caps. [X rate limits](https://docs.x.com/x-api/fundamentals/rate-limits)

## X: access and reuse constraints

X's Terms of Service prohibit crawling or scraping without prior written consent. A visible-DOM extension should not be described as an automatically permitted workaround. The precise treatment of a local annotation tool, user-requested checking, or shared backend collection needs confirmation for the proposed workflow. [X Terms of Service](https://x.com/en/tos)

The Developer Agreement licenses API analysis as explicitly approved by X. The application should describe factual-claim analysis, external model processing, storage, shared results, and deletion handling accurately. [Developer Agreement](https://docs.x.com/developer-terms/agreement)

X's policy requires current content for redisplay, removal handling, and generally restricts redistribution to IDs. It also requires written permission for services identifying content that violates X policies. General factual accuracy and violation detection are not identical; avoid assuming either that all fact-checking is prohibited or that this use case is already approved. A verdict API containing evidence and links is architecturally preferable to republishing a raw social-post archive, but its allowed scope still depends on the agreement. [Developer Policy](https://docs.x.com/developer-terms/policy)

## Reddit: approval before scale

Reddit's current Responsible Builder Policy requires explicit approval before API access and explicit written approval for commercial use. It directs developers toward Devvit, with an exception-request route for unsupported use cases. It also prohibits circumventing access limits and using moderator access for unrelated functionality. A Chrome extension with an external evidence service should be described in an access request rather than assumed to inherit a moderation exemption. [Responsible Builder Policy](https://support.reddithelp.com/hc/en-us/articles/42728983564564-Responsible-Builder-Policy)

The Data API Terms require a separate agreement for commercial use and reserve Reddit's right to set fees. No applicable public commercial price for this product was verified; **do not assume Reddit costs zero or use a historical third-party quote as a current offer**. Permission to conduct inference should be stated separately from model training; training has additional rights restrictions. [Data API Terms](https://redditinc.com/policies/data-api-terms)

For eligible free access, Reddit documents **100 queries/minute per OAuth client**, averaged over ten minutes. OAuth and an accurate identifying User-Agent are required. Stored deleted posts/comments and author-identifying information for deleted accounts must be removed; Reddit recommends routinely deleting stored user data/content within 48 hours to facilitate compliance. The 48 hours is a recommendation, not a blanket permission to keep everything indefinitely while checking every two days. [Reddit Data API Wiki](https://support.reddithelp.com/hc/en-us/articles/16160319875092-Reddit-Data-API-Wiki)

For an approved pilot, poll a named subreddit set using `hot`, `rising`, and `top` listings, with paging and deduplication. The technical documentation lists these endpoints and allows up to 100 items per listing request; Reddit warns that legacy technical documentation can be stale, so validate the approved endpoint set when access is granted. [API listing documentation](https://www.reddit.com/dev/api/#section_listings)

Prioritize by rank, score, comment activity, age, and observed demand. Reddit's current Post model includes score, comment count, and crosspost parent ID. [Post model](https://developers.reddit.com/docs/api/redditapi/models/classes/Post) Reddit documents view insights for authors and moderators of their communities; no universal public view-count entitlement was established. Consequently, “important Reddit post” should use a transparent engagement policy rather than copy X's impressions threshold. [Post and Comment Insights](https://support.reddithelp.com/hc/en-us/articles/35363096996500-Post-Comment-Insights)

## Chrome extension feasibility and privacy

Chrome content scripts can read and modify the page DOM and communicate with the extension. That supports adding an independent rating beneath a rendered post. It is a technical capability, not social-platform authorization. [Chrome content scripts](https://developer.chrome.com/docs/extensions/develop/concepts/content-scripts)

The Web Store permits collecting browsing activity only when required for a prominently disclosed user-facing feature; it applies its use restrictions to scraped, aggregated, anonymized, and derived data too. Transfer to an inference provider must be necessary to the extension's disclosed purpose. Sharing a cache does not automatically escape these rules merely because it contains hashes or derived scores. [Limited Use](https://developer.chrome.com/docs/webstore/program-policies/limited-use)

A product handling user data needs an accurate privacy policy, clear disclosures and informed consent, secure transmission, and narrow permissions. The extension package must keep executable logic local; backend research can return data. [Web Store Program Policies](https://developer.chrome.com/docs/webstore/program-policies/policies) Local-only processing still requires disclosure of data handling. [User Data FAQ](https://developer.chrome.com/docs/webstore/program-policies/user-data-faq)

Recommended design: host access restricted to X and Reddit; local rejection of irrelevant/private content; no cookies, private messages, full browsing history, or account credentials sent to the research service; minimum post identifiers/claim context needed for checking; separate opt-in email address and report delivery preference; retention and deletion workers; independently sourced evidence stored separately from platform content. These are design choices to reduce exposure and operating cost, not a claim of platform or store approval.

## Concrete next steps

1. Prototype annotation and queue behavior against synthetic fixtures or authorized sample content while access requests are prepared.
2. Request Reddit approval and a commercial quote with named subreddits, anticipated request volume, third-party inference, shared cache, and retention plan.
3. Confirm X analysis and extension collection/redisplay scope; use a hard capped API pilot instead of assuming DOM harvesting is permitted.
4. Separate the research budget from data-license costs. A $10/day pilot can allocate, for example, $2/day to 400 X reads and reserve the rest for evidence search, inference, hosting, and notifications; that allocation is illustrative and still requires a Reddit access arrangement.
5. Measure coverage on opted-in users' eligible factual feed impressions. Global collection counts do not demonstrate feed coverage, and a one-million-view threshold alone will miss lower-view posts in individual feeds.
