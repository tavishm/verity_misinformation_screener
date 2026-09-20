# Verity on Android

Open the app, choose English or Hindi, and enable the WhatsApp helper. English is the default. Add your OpenRouter checking key in Settings for online fact checks.

If **Keep checks on** appears, tap it and allow Android to keep Verity running. This changes the setting for this app only. Some Galaxy phones also have a **Never sleeping apps** list under Battery → Background usage limits. See [Android's background power guidance](https://developer.android.com/training/monitoring-device-state/doze-standby) and [Samsung's per-app settings](https://developer.samsung.com/mobile/app-management.html). These settings help avoid background restrictions; they are not a guarantee against every platform interruption.

While you read a WhatsApp chat, the helper screens forwarded messages, incoming messages from unknown numbers, and incoming messages from people selected under **Settings → People to check**. Screening understands English and Hindi regardless of the app's display language. Ordinary outgoing messages are not screened unless marked forwarded.

Personal messages and greetings stay quiet. A possible scam gets an **Ignore** button. A factual message gets **Check** and **Not now**. Only after **Check** does the phone send that message's text for research. The answer stays in a panel over WhatsApp. **Why?** shows the explanation and sources, or lets you request an online check of a quick AI answer.

For pictures, the phone reads visible text locally before offering a check. The image and nearby chat are not uploaded. A greeting picture should normally stay quiet; a news report or health claim should be offered. If Android cannot expose the picture, the app offers a clearer-picture/share fallback. Experimental AI-photo detection is separate from fact checking and cannot prove that a picture is real or fake.

**Not now**, **Ignore**, and **Check** remember the current message choices. A newly received copy can be offered again. The app uses the visible WhatsApp interface, not private database message IDs; layout changes, clipped content and indistinguishable same-time pictures remain cases to validate across devices.

No computer connection is needed during use. Screening and OCR run offline. A fact-check answer needs the phone's internet connection. The Samsung test phone has no ADB network forwarding configured. USB is only used for installing development updates and running diagnostics.

## Optional SMS and RCS scam warnings

The `0.4.0-sms` build adds **Settings → SMS scam warnings**. Turn it on and allow Contacts to screen visible incoming text from unsaved senders in **Google Messages**. The existing message helper shows a local **This may be a scam** warning with **Ignore** and **Why?**. Saved contacts are skipped. SMS does not go online and needs no checking key. English and Hindi messages are screened under either app language.

This feature is off by default. The demo phone's owner confirmed the warning and persistent Ignore behavior, followed by the Hindi and unsaved RCS profile-name fixes. See [SMS_SCREENING.md](../SMS_SCREENING.md) for the implemented scope, automated checks and remaining rehearsal cases.

## Rehearsal

1. Unplug USB and leave Wi-Fi or mobile data on.
2. Have a test contact forward a **new copy** of a factual text, then a factual picture. Open the chat and keep each visible.
3. Tap **Check**. Confirm the answer stays over WhatsApp.
4. Tap **Close** or **Not now**. Reopen that same message and confirm it stays quiet.
5. Try a greeting, a harmless link, a Hindi factual message and a direct credential request from an unsaved test number. Greetings should stay quiet; a link should offer checking without automatically being called a scam.

There is no promise that every factual claim can be resolved, or that every search will finish in two seconds. **Do not share yet** means the app has no clear answer; it does not establish that the claim is false. Current news needs up-to-date sources. Quick model-only answers are labelled **Quick AI check · can be wrong**.

## Measured development results

| Operation | Samsung SM-S721B measurement |
|---|---|
| Contextual local classification | 6–7 ms median, 9–10 ms p95 in two small uncached diagnostic runs |
| Initial contextual model load | 494–836 ms |
| One uncached quick cloud check | 2.62 seconds; $0.000018495 |
| One comparable bounded web check after search changes | 5.44 seconds; $0.001704976 |

These are individual-device development checks, not field accuracy, battery-life or latency guarantees. Training and evaluation details are in [MODEL_FACTS.md](classifier/MODEL_FACTS.md). Costs are recorded under **Settings → Costs → Share record**; broader development charges are tracked separately. The development APK is not a store-ready production release.
