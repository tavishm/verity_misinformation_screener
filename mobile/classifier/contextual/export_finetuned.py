"""Export the selected routing checkpoint, quantize it, and verify real outputs.

Run with PYTHONPATH=.cache/context-python. The large intermediate stays in a
temporary RAM directory; only the final quantized model is retained on disk.
"""
import hashlib
import json
from pathlib import Path
import tempfile

import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic
import torch
from tokenizers import Tokenizer

from finetune import RoutingModel, prepare, batch, probabilities, select
from train import CACHE, HERE, LABELS, dataset


def main():
    torch.set_num_threads(4)
    checkpoint = torch.load(CACHE / "finetuned.pt", map_location="cpu", weights_only=True)
    model = RoutingModel().eval()
    model.load_state_dict(checkpoint["state_dict"], strict=False)
    output = CACHE / "routing-int8.onnx"
    ids = torch.tensor([[101, 10117, 13192, 102]])
    with tempfile.TemporaryDirectory(prefix="forward-check-export-", dir="/dev/shm") as folder:
        fp32 = Path(folder) / "routing.onnx"
        torch.onnx.export(model, (ids, torch.ones_like(ids)), str(fp32),
                          input_names=["input_ids", "attention_mask"], output_names=["logits"],
                          dynamic_axes={"input_ids": {0: "batch", 1: "sequence"},
                                        "attention_mask": {0: "batch", 1: "sequence"},
                                        "logits": {0: "batch"}}, opset_version=17,
                          do_constant_folding=True)
        quantize_dynamic(str(fp32), str(output), weight_type=QuantType.QUInt8,
                         per_channel=True, op_types_to_quantize=["MatMul", "Gather"])
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(str(output), options, providers=["CPUExecutionProvider"])
    rows = dataset()
    encoded = prepare(rows)
    p = np.zeros((len(rows), 3), dtype=np.float32)
    indices = [i for i, r in enumerate(rows) if r["split"] != "train"]
    for start in range(0, len(indices), 32):
        chosen = indices[start:start + 32]
        tensors = batch(encoded, chosen, "cpu")
        logits = session.run(None, dict(zip(["input_ids", "attention_mask"], [t.numpy() for t in tensors])))[0]
        scores = np.exp(logits - logits.max(axis=1, keepdims=True))
        p[chosen] = scores / scores.sum(axis=1, keepdims=True)
    # Quantization can shift probabilities. Recalibrate on validation only.
    y = np.array([LABELS.index(r["label"]) for r in rows])
    selection = select(p, rows, y)
    from sklearn.metrics import confusion_matrix
    pred = np.where(p[:, 2] >= selection["spam_threshold"], 2,
                    np.where(p[:, 0] >= selection["personal_threshold"], 0, 1))
    results = {}
    for key in sorted({r["source"] + ":" + r["language"] for r in rows}):
        chosen = [i for i, r in enumerate(rows) if r["split"] == "test" and r["source"] + ":" + r["language"] == key]
        results[key] = {"rows": len(chosen), "confusion_matrix": confusion_matrix(y[chosen], pred[chosen], labels=[0, 1, 2]).tolist()}
    metadata = dict(version=2, output="logits", labels=LABELS, max_tokens=128,
                    encoder_sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                    parameters=sum(p.numel() for p in model.parameters()),
                    trainable_parameters=sum(p.numel() for p in model.parameters() if p.requires_grad),
                    bytes=output.stat().st_size, **selection)
    (HERE / "routing-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (HERE / "quantized-test.json").write_text(json.dumps({"selection": selection, "test_groups": results}, indent=2) + "\n")
    challenge = json.loads((HERE / "challenge.json").read_text())
    tokenized = prepare(challenge)
    fixtures, reports, differences = [], [], []
    for i, row in enumerate(challenge):
        tokens, mask = batch(tokenized, [i], "cpu")
        logits = session.run(None, {"input_ids": tokens.numpy(), "attention_mask": mask.numpy()})[0][0]
        with torch.no_grad():
            original = model(tokens, mask).softmax(-1)[0].numpy()
        score = np.exp(logits - logits.max()); score /= score.sum()
        differences.append(float(np.abs(original - score).max()))
        label = "scam" if score[2] >= selection["spam_threshold"] else "personal" if score[0] >= selection["personal_threshold"] else "claim"
        reports.append(dict(**row, predicted=label, probabilities=score.tolist()))
        fixtures.append(dict(text=row["text"], token_ids=tokenized[i], probabilities=score.tolist()))
    (HERE / "finetuned-challenge.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n")
    (HERE / "android-parity.json").write_text(json.dumps(fixtures, ensure_ascii=False, indent=2) + "\n")
    print("MODEL", json.dumps(metadata), flush=True)
    print("QUANTIZED_TEST", json.dumps(results), flush=True)
    print("quantization_probability_difference", {"median": float(np.median(differences)), "max": max(differences)}, flush=True)
    for lang in ["en", "hi", "hinglish"]:
        subset = [r for r in reports if r["language"] == lang]
        print("DEVELOPMENT", lang, sum(r["predicted"] == r["label"] for r in subset), "/", len(subset), flush=True)


if __name__ == "__main__":
    main()
