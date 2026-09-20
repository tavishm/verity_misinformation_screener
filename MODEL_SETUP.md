# Context model setup

`mobile/android/assets/context-encoder-int8.onnx` is deliberately excluded
from Git. At about 129.5 MiB it exceeds GitHub's regular 100 MiB file limit.
Git LFS is not required: publish the exact file as a release asset or on a
durable HTTPS artifact host, then fetch it with the included verifier.

The release asset must match the committed
[`context-model-manifest.json`](mobile/android/assets/context-model-manifest.json).
The expected SHA-256 and byte length are checked before the file is placed in
the Android assets directory.

```bash
export FORWARD_CHECK_CONTEXT_MODEL_URL='https://your-artifact-host/context-encoder-int8.onnx'
python3 mobile/tooling/bootstrap_context_model.py
```

The command is idempotent: it keeps an already verified local model. It rejects
HTTP URLs, bad downloads, and a same-named file with a different checksum.

The base model is `sentence-transformers/distiluse-base-multilingual-cased-v2`
at the revision recorded in the manifest. Its Apache-2.0 text and the model
notice are retained in `mobile/android/assets/`. Before publishing any model
asset, verify that its notices and the rights for the fine-tuned checkpoint are
appropriate for the intended release.
