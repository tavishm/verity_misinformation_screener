"""Fine-tune six multilingual transformer layers for local routing.

Validation alone selects the checkpoint and thresholds. Synthetic translation
groups remain in one split. Public test data and development examples are never
used as training examples. No private messages or paid APIs are used.
"""
import argparse
from collections import Counter
import json
import random
import time

import numpy as np
import torch
from safetensors.torch import load_file
from tokenizers import Tokenizer
import transformers.utils as transformer_utils
# This small encoder uses ordinary attention; do not import a system-installed
# FlashAttention binary built for a different PyTorch version.
transformer_utils.is_flash_attn_2_available = lambda: False
from transformers import AutoConfig, AutoModel
from sklearn.metrics import confusion_matrix

from train import CACHE, HERE, LABELS, SEED, dataset


class RoutingModel(torch.nn.Module):
    def __init__(self):
        super().__init__()
        config = AutoConfig.from_pretrained(CACHE, local_files_only=True)
        config.output_hidden_states = False
        config._attn_implementation = "eager"
        self.encoder = AutoModel.from_config(config)
        self.encoder.load_state_dict(load_file(CACHE / "model.safetensors"))
        self.dense = torch.nn.Linear(768, 512)
        dense = load_file(CACHE / "2_Dense/model.safetensors")
        self.dense.load_state_dict({k.removeprefix("linear."): v for k, v in dense.items()})
        self.head = torch.nn.Linear(512, 3)
        torch.nn.init.normal_(self.head.weight, std=0.02)
        torch.nn.init.zeros_(self.head.bias)
        for parameter in self.encoder.parameters():
            parameter.requires_grad_(False)
        for layer in self.encoder.transformer.layer:
            for parameter in layer.parameters():
                parameter.requires_grad_(True)

    def forward(self, input_ids, attention_mask):
        hidden = self.encoder(input_ids=input_ids, attention_mask=attention_mask)[0]
        mask = attention_mask.unsqueeze(-1).to(hidden.dtype)
        pooled = (hidden * mask).sum(1) / mask.sum(1).clamp_min(1)
        embedding = torch.nn.functional.normalize(torch.tanh(self.dense(pooled)), dim=1)
        return self.head(embedding) * 20.0


def prepare(rows):
    tokenizer = Tokenizer.from_file(str(CACHE / "tokenizer.json"))
    tokenizer.enable_truncation(max_length=128)
    return [e.ids for e in tokenizer.encode_batch([r["text"] for r in rows])]


def batch(encoded, indices, device):
    length = max(len(encoded[i]) for i in indices)
    ids = np.zeros((len(indices), length), dtype=np.int64)
    mask = np.zeros_like(ids)
    for j, i in enumerate(indices):
        ids[j, :len(encoded[i])] = encoded[i]
        mask[j, :len(encoded[i])] = 1
    return torch.tensor(ids, device=device), torch.tensor(mask, device=device)


def probabilities(model, encoded, indices, device):
    model.eval()
    output = np.zeros((len(encoded), 3), dtype=np.float32)
    with torch.no_grad():
        for start in range(0, len(indices), 32):
            selected = indices[start:start + 32]
            output[selected] = model(*batch(encoded, selected, device)).softmax(-1).cpu().numpy()
    return output


def select(prob, rows, labels, personal_floor=.8, spam_floor=.9, step=.01):
    validation = np.array([r["split"] == "validation" for r in rows])
    keys = sorted({r["source"] + ":" + r["language"] for r in rows})
    groups = {key: validation & np.array([r["source"] + ":" + r["language"] == key for r in rows]) for key in keys}
    personal = 1.000001
    for threshold in np.arange(personal_floor, 1.0, step):
        if all((prob[mask & (labels == 1), 0] >= threshold).mean() <= .06
               for mask in groups.values() if (mask & (labels == 1)).sum() >= 8):
            personal = float(threshold)
            break
    spam = 1.000001
    for threshold in np.arange(spam_floor, 1.0, step):
        warnings = prob[:, 2] >= threshold
        if all((warnings[mask] & (labels[mask] == 2)).sum() / max(1, warnings[mask].sum()) >= .95
               for mask in groups.values() if (mask & (labels == 2)).sum() >= 8):
            spam = float(threshold)
            break
    pred = np.where(prob[:, 2] >= spam, 2, np.where(prob[:, 0] >= personal, 0, 1))
    scores = [np.mean([(pred[mask & (labels == k)] == k).mean()
                       for k in range(3) if (mask & (labels == k)).any()])
              for mask in groups.values() if mask.any()]
    return dict(personal_threshold=personal, spam_threshold=spam,
                validation_group_balanced_accuracy=float(np.mean(scores)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--epochs", type=int, default=6)
    parser.add_argument("--samples", type=int, default=7200)
    parser.add_argument("--device", default="cuda")
    options = parser.parse_args()
    random.seed(SEED)
    np.random.seed(SEED)
    torch.manual_seed(SEED)
    torch.set_num_threads(4)
    rows = dataset()
    encoded = prepare(rows)
    y = np.array([LABELS.index(r["label"]) for r in rows])
    train_indices = np.array([i for i, r in enumerate(rows) if r["split"] == "train"])
    val_indices = np.array([i for i, r in enumerate(rows) if r["split"] == "validation"])
    # Equal mass per language/source/label stratum, rather than allowing the
    # much larger English debate corpus to drown out Hindi conversation data.
    strata = [(rows[i]["source"], rows[i]["language"], rows[i]["label"]) for i in train_indices]
    counts = Counter(strata)
    weights = np.array([1 / counts[key] for key in strata], dtype=np.float64)
    weights /= weights.sum()
    model = RoutingModel().to(options.device)
    optimizer = torch.optim.AdamW([
        {"params": [p for p in model.encoder.parameters() if p.requires_grad], "lr": 1e-5},
        {"params": model.dense.parameters(), "lr": 1e-4},
        {"params": model.head.parameters(), "lr": 3e-4},
    ], weight_decay=.01)
    print(json.dumps({"parameters": sum(p.numel() for p in model.parameters()),
                      "trainable_parameters": sum(p.numel() for p in model.parameters() if p.requires_grad),
                      "rows": dict(Counter(r["split"] for r in rows))}), flush=True)
    best = -1
    reports = []
    start = time.monotonic()
    rng = np.random.default_rng(SEED)
    for epoch in range(options.epochs):
        model.train()
        # Frozen layers should not add dropout noise to the learned embedding.
        model.encoder.embeddings.eval()
        chosen = rng.choice(train_indices, size=options.samples, replace=True, p=weights)
        losses = []
        for offset in range(0, len(chosen), 16):
            indices = chosen[offset:offset + 16]
            optimizer.zero_grad(set_to_none=True)
            logits = model(*batch(encoded, indices, options.device))
            loss = torch.nn.functional.cross_entropy(logits, torch.tensor(y[indices], device=options.device))
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            losses.append(float(loss.detach()))
            if offset % 800 == 0:
                print("epoch", epoch + 1, "examples", offset, "loss", round(np.mean(losses), 4),
                      "seconds", round(time.monotonic() - start), flush=True)
        prob = probabilities(model, encoded, val_indices, options.device)
        # Broad validation search chooses the floating-point checkpoint. The
        # actual int8 export separately applies stricter deployment thresholds.
        report = select(prob, rows, y, personal_floor=.4, spam_floor=.4, step=.02)
        report.update(epoch=epoch + 1, train_loss=float(np.mean(losses)))
        reports.append(report)
        print("VALIDATION", json.dumps(report), flush=True)
        if report["validation_group_balanced_accuracy"] > best:
            best = report["validation_group_balanced_accuracy"]
            # Save only updated parameters; the immutable pinned base is separate.
            updated={name: parameter.detach().cpu() for name, parameter in model.named_parameters() if parameter.requires_grad}
            torch.save({"state_dict": updated, "selection": report,
                        "seed": SEED, "epochs_requested": options.epochs}, CACHE / "finetuned.pt")
        (HERE / "finetuning-validation.json").write_text(json.dumps(reports, indent=2) + "\n")
    checkpoint = torch.load(CACHE / "finetuned.pt", map_location=options.device, weights_only=True)
    model.load_state_dict(checkpoint["state_dict"], strict=False)
    tests = np.array([i for i, r in enumerate(rows) if r["split"] == "test"])
    prob = probabilities(model, encoded, tests, options.device)
    selected = checkpoint["selection"]
    pred = np.where(prob[:, 2] >= selected["spam_threshold"], 2,
                    np.where(prob[:, 0] >= selected["personal_threshold"], 0, 1))
    report = {"selection": selected, "test_groups": {}, "synthetic_is_not_deployment_evidence": True}
    for key in sorted({r["source"] + ":" + r["language"] for r in rows}):
        indices = [i for i in tests if rows[i]["source"] + ":" + rows[i]["language"] == key]
        report["test_groups"][key] = {"rows": len(indices), "confusion_matrix":
                                        confusion_matrix(y[indices], pred[indices], labels=[0, 1, 2]).tolist()}
    (HERE / "finetuning-test.json").write_text(json.dumps(report, indent=2) + "\n")
    print("TEST", json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
