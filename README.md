# Verity

<img src="mobile/branding/verity-icon.png" alt="Verity app icon" width="96" />

Verity is an Android pilot that helps people check suspicious messages while
they read. It screens English and Hindi locally, then offers a simple warning
or an optional online fact check.

- **WhatsApp:** screen forwards and messages from unknown or selected contacts.
  Read text from visible pictures on the phone. Answers stay over the chat.
- **Google Messages:** optional scam warnings for incoming SMS and RCS from
  unsaved senders. This screening stays on the phone.
- **Social apps:** opt-in checking of public posts in X, Reddit and Google News.
  Selected post content is sent online when this feature is enabled.
- **Simple controls:** English or Hindi UI, large buttons, message-specific
  choices, source explanations and a usage-cost record.

The Android app uses an accessibility helper while supported conversations or
feeds are visible. It is not a background reader of every message. Local
screening needs no computer connection; online checking needs internet and
an OpenRouter key configured in the app. See the
[Android guide](mobile/STANDALONE_GUIDE.md) and [SMS details](SMS_SCREENING.md).

This is research software, not a production service or a general claim of
fact-checking accuracy. The contextual router does not determine whether a
claim is true. Its Hindi and Romanized-Hindi evaluation includes synthetic data,
and no representative WhatsApp, SMS, or social-media accuracy study is claimed.

## Source checkout

Prerequisites for the Android build are Python 3.10+, a JDK 17 runtime, and an
Android SDK containing Platform 35 and Build Tools 35.0.0. Set `ANDROID_HOME`
to that SDK. The build helper downloads its pinned Gradle and JDK copies into
an ignored local cache; it makes no global Java changes. It creates a local
Android debug signing key on a fresh checkout and preserves an existing key.

The contextual Android model is a separately hosted artifact because it is
larger than GitHub's regular file-size limit. Fetch and verify it before an APK
build:

```bash
export FORWARD_CHECK_CONTEXT_MODEL_URL='https://your-artifact-host/context-encoder-int8.onnx'
python3 mobile/tooling/bootstrap_context_model.py
python3 mobile/tooling/standalone_build.py :app:testDebugUnitTest :app:assembleDebug
```

The resulting APK is `mobile/android/app/build/outputs/apk/debug/app-debug.apk`.
Running `python3 mobile/tooling/standalone_build.py` without task arguments also
copies it to the ignored `dist/` directory. See
[MODEL_SETUP.md](MODEL_SETUP.md) for the required checksum verification and
[mobile/classifier/MODEL_FACTS.md](mobile/classifier/MODEL_FACTS.md) for the
model’s scope and limitations. The workspace also includes the earlier Chrome
evidence-checking prototype and cost-model experiments.

Python checks for the local evidence prototype can be run with:

```bash
python3 -m pip install -r requirements-dev.txt
python3 -m unittest discover -s . -t . -p 'test_*.py'
```

## Privacy and publication

Keep credentials, phone data, local evidence databases, screenshots, APKs,
signing material, accounting records, and generated caches out of commits.
The repository ignore rules enforce the normal path; review every staged file
before publishing. [PUBLIC_RELEASE.md](PUBLIC_RELEASE.md) contains the first
upload checklist, [SECURITY.md](SECURITY.md) explains how to report security
issues, and [NOTICE.md](NOTICE.md) records the pending project-license decision.
