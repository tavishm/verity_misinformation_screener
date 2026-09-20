# SMS scam screening

Status, 20 September 2026: **installed and enabled on the Samsung demo phone. The owner confirmed a scam warning for an incoming SMS from an unsaved sender, and Ignore stayed quiet after reopening the conversation. A subsequent new incoming English request also warned.** Follow-up found two issues: urgent Hindi requests for banking information were missed, and an unsaved RCS sender's chosen profile name was skipped. Both fixes pass regression tests and are installed in the combined release. On 20 September at 11:17 IST, the owner confirmed the final RCS/Hindi retest worked. This is part of the native Android app, now named Verity. The functionality was validated in version `0.4.0-sms` / versionCode 5; the cosmetic Verity update is `0.4.1-verity` / versionCode 6. The supported adapter is **Google Messages** (`com.google.android.apps.messaging`). Samsung Messages and other SMS apps are not implemented.

## What it does

While the user reads an individual conversation in Google Messages, the existing accessibility helper checks visible incoming text from an unsaved sender. The same local English/Hindi contextual model used by WhatsApp screens it, with the existing narrow credential/prize/link scam rules. Only the `spam_warning` result produces a panel: **This may be a scam → Ignore / Why?** The explanation also stays over Messages.

Ordinary greetings, factual claims, standalone links and uncertain fact-check classifications produce no SMS prompt. Saved contacts, outgoing messages, recognized groups, drafts, conversation-list snippets, quoted replies and ambiguous message directions are skipped. A missing Contacts permission or failed Contacts lookup is unresolved, not evidence that the sender is unknown.

SMS screening has no online fact-check route. It does not use an API key, spend OpenRouter credit, read the SMS database, listen for incoming SMS or notifications, send replies, or require changing the default SMS app. Pictures are outside this narrowed SMS feature. No SMS or contact names are stored in dismissal history; the existing bounded history retains hashes and choices. The model and temporary text/contact lookup caches are on the phone.

## Setup after installation

1. Open Verity → **Settings → SMS scam warnings**.
2. Tap **Turn on** and allow **Contacts** so the app can exclude saved people.
3. Enable the existing **Verity** accessibility helper if the setup screen asks.
4. Open an unsaved sender's conversation in **Google Messages**. No computer connection is needed for screening.

SMS is off by default. Revoking Contacts access disables screening; turning off SMS warnings does not disable the WhatsApp helper. The display language defaults to English and can be switched to Hindi; the local model screens both languages regardless of that choice.

## Implementation

- `SmsExtractor` copies message boundaries into a pure-Java representation. It recognizes specific Google Messages resource IDs / Compose tags and reads only message-body fields inside a conversation. The actual Compose layouts are covered by captured regressions: older versions expose an untagged sender beside an avatar, while the phone's September 2026 version exposes `top_app_bar_title_row` above a list that extends behind the toolbar. A one-to-one header with an individual call action can expose an RCS profile name; it still goes through the Contacts lookup. A profile photo need not expose a monogram tag. Recognized groups and ambiguous headers are skipped. Explicit direction metadata wins over geometry; ambiguous layouts produce no warning. Tree size and depth are bounded.
- `SmsSenderLookup` queries Android's local Contacts provider on the background worker. Numeric senders use `PhoneLookup` for local/international matching; displayed names and sender IDs use an exact contact-name lookup. Results expire, contact changes invalidate the cache, and incomplete lookups suppress screening. No entire address-book copy is created.
- `SmsSnapshot` adapts eligible SMS bubbles to the existing overlay and dismissal machinery. Timestamp show/hide changes do not change message identity. A new harmless last message does not silently dismiss an earlier unseen scam.
- `SmsPolicy` keeps SMS routing separate from WhatsApp fact checking. The overlay's online-check entry point additionally refuses SMS decisions even if called indirectly.
- `SmsSettingsActivity` owns the optional Contacts request and SMS switch. The home screen recognizes SMS-only readiness without requiring a checking key.

The shared social-media implementation and its ONNX Runtime `1.23.2` compatibility fix are preserved. The combined update was installed with `install -r`, preserving existing data. Both helpers reconnected. No phone instrumentation or message-history reset was used during this rollout.

## Validation and remaining rehearsal checks

Build and host tests:

```bash
python3 mobile/tooling/localize_ui.py
python3 mobile/tooling/standalone_build.py :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

The final combined release passed **199 host tests**, including 139 social tests. After the RCS/Hindi fixes, the targeted SMS suite passes **39 tests**, including captured-layout regressions and urgent financial-information request tests. SMS coverage includes extraction boundaries, outgoing/ambiguous/group suppression, Hindi text, drafts, bounded traversal, routing, delayed contact resolution and dismissal persistence. The current APK and validation manifest are `dist/forward-check-debug.apk` and `dist/forward-check-build.json`. The manifest distinguishes installed candidates from artifacts awaiting a coordinated rollout.

All **three `SmsLocalGateTest` Android tests passed** on an empty Android 15 VM through the bundled screening pipeline. They cover English/Hindi/Hinglish credential requests, normal OTP/greeting/fact/link suppression and permission denial. No API key or paid requests were used.

The VM ran genuine Google Messages, first the supplied 2024 version and then the exact `messages.android_20260911_02_RC00.phone_samsung_openbeta_dynamic` APK copied from the demo phone. Only application binaries were copied; no personal messages, contacts or account data were copied. An incoming UPI-PIN request displayed the warning; Ignore stayed quiet and a subsequent greeting did not revive it. Testing exposed and fixed the Compose header differences. VM work was stopped after the development machine restarted; further validation uses the phone. These results do not establish the full repeated-message or saved-contact workflow on the phone.

Confirmed on the phone: the final RCS/Hindi fixes worked in the owner’s retest; SMS was enabled through the app with Contacts access; the helper remained connected; diagnostics reported a local SMS scam decision; the owner saw the warning and confirmed that Ignore stayed quiet after reopening. No phone instrumentation was run. Remaining rehearsal checks:

1. Incoming English/Hindi credential requests warn; outgoing requests and normal OTP alerts stay quiet.
2. Ignore / Why? → Close stays quiet across scrolling, reopening and app restart.
3. Saving that sender in Contacts suppresses subsequent warnings; denial/revocation of Contacts permission does not classify them as unknown.
4. Existing WhatsApp and social helpers still work; unplugged SMS screening needs no network.

Like the WhatsApp helper, this adapter sees the user interface rather than stable database message IDs. Layout changes, truncated contact names, unidentified group layouts and indistinguishable repeated copies need live validation; the present implementation does not establish detection accuracy across devices. Use `PHONE_COORDINATION.md` before any later shared-phone rollout.
