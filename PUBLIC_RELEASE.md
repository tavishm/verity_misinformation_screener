# Public-upload checklist

Public repository: [tavishm/verity_misinformation_screener](https://github.com/tavishm/verity_misinformation_screener).
This is a source release. Private phone state, credentials, accounting records,
local tools and generated APKs are excluded.

Before publishing a change, run these checks from the project root:

```bash
git status --ignored
git check-ignore -v .cache/android/debug.keystore dist/forward-check-debug.apk \
  mobile/accounting/standalone-costs.csv demo/data/local-token \
  mobile/android/assets/context-encoder-int8.onnx
git add .
git status
```

Review the staged file list before committing. It must not contain ignored
artifacts via `git add -f`, including APKs, the excluded contextual model,
credentials, phone records, local databases, caches, or accounting data.

Publish the contextual model as a separately controlled HTTPS release asset,
then follow [MODEL_SETUP.md](MODEL_SETUP.md). Do not use Git LFS unless the
repository owner later decides that its quota, billing, and access model are
appropriate.

Choose a top-level project license only after resolving the provenance noted in
[NOTICE.md](NOTICE.md). Add a repository owner contact before relying on the
private vulnerability-reporting route in [SECURITY.md](SECURITY.md).
