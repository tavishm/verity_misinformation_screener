#!/usr/bin/env python3
"""Illustrative local GPU workload arithmetic; not a capacity or accuracy estimate.

Defaults reproduce LOCAL_FIRST_PLAN.md. All rates are user-changeable assumptions.
The heavier-route time is elapsed latency from one prototype request, treated as
serialized single-GPU-equivalent time; it is not measured GPU utilization.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import json
import math


@dataclass(frozen=True)
class Assumptions:
    users: int = 10_000
    impressions_per_user: float = 200
    unique_fraction: float = 0.2
    factual_fraction: float = 0.65
    claims_per_version: float = 1.5
    candidates_per_claim: int = 3
    pairs_per_second: float = 28
    generation_route_fraction: float = 0.05
    heavy_seconds: float = 12.752
    gpus: int = 8

    def validate(self) -> None:
        for name in ("users", "candidates_per_claim", "gpus"):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, int):
                raise ValueError(f"{name} must be an integer")
            if value < (0 if name == "users" else 1):
                raise ValueError(f"{name} must be {'nonnegative' if name == 'users' else 'positive'}")
        for name, value in asdict(self).items():
            try:
                finite = not isinstance(value, bool) and math.isfinite(value)
            except (TypeError, OverflowError):
                finite = False
            if not finite:
                raise ValueError(f"{name} must be a finite number")
        for name in ("unique_fraction", "factual_fraction", "generation_route_fraction"):
            if not 0 <= getattr(self, name) <= 1:
                raise ValueError(f"{name} must be between 0 and 1, inclusive")
        if self.impressions_per_user < 0:
            raise ValueError("impressions_per_user must be nonnegative")
        for name in ("claims_per_version", "pairs_per_second", "heavy_seconds"):
            if getattr(self, name) <= 0:
                raise ValueError(f"{name} must be greater than zero")


EXCLUSIONS = [
    "Claim routing and extraction outside the specified heavier route",
    "Source acquisition, ingestion, indexing, embeddings and source assessment",
    "Media processing and verification",
    "Quality audits, retries, operational overhead and model loading",
    "Idle capacity, peak traffic, queueing, utilization and multi-GPU serving efficiency",
    "Hardware, electricity, infrastructure and data/content licensing costs",
]


def calculate(assumptions: Assumptions) -> dict:
    assumptions.validate()
    try:
        impressions = assumptions.users * assumptions.impressions_per_user
        unique_versions = impressions * assumptions.unique_fraction
        factual_versions = unique_versions * assumptions.factual_fraction
        claims = factual_versions * assumptions.claims_per_version
        comparisons = claims * assumptions.candidates_per_claim
        heavy_versions = factual_versions * assumptions.generation_route_fraction
        scorer_hours = comparisons / assumptions.pairs_per_second / 3600
        heavy_hours = heavy_versions * assumptions.heavy_seconds / 3600
        subtotal = scorer_hours + heavy_hours
        available = assumptions.gpus * 24
        share = subtotal / available
        percent = share * 100
    except (OverflowError, ZeroDivisionError) as error:
        raise ValueError("Inputs exceed the numeric range of this model") from error
    values = [impressions, unique_versions, factual_versions, claims, comparisons,
              heavy_versions, scorer_hours, heavy_hours, subtotal, available, share, percent]
    if not all(math.isfinite(value) for value in values):
        raise ValueError("Inputs exceed the numeric range of this model")
    return {
        "model": "Illustrative daily local-compute workload",
        "assumptions": asdict(assumptions),
        "workload_per_day": {
            "feed_impressions": impressions,
            "distinct_post_versions": unique_versions,
            "factual_versions": factual_versions,
            "claims": claims,
            "claim_passage_comparisons": comparisons,
            "heavier_route_versions": heavy_versions,
        },
        "compute_per_day": {
            "scorer_gpu_hours": scorer_hours,
            "heavier_route_gpu_hours": heavy_hours,
            "subtotal_gpu_hours": subtotal,
            "nominal_gpu_hours": available,
            "share_of_nominal_gpu_hours": share,
            "percent_of_nominal_gpu_hours": percent,
        },
        "interpretation": [
            "An arithmetic scenario, not demonstrated service capacity, coverage or accuracy.",
            "All factual versions take the scorer route; heavier work is additional on a subset, not extra unique posts.",
            "Scorer rate defaults to a rounded synthetic one-A6000 medium-passage measurement including tokenization.",
            "Heavier-route hours assume serialized single-GPU-equivalent work using one observed end-to-end latency; GPU occupancy and concurrent throughput were not measured.",
            "Nominal hours are GPU count times 24, without any availability, utilization or peak-demand guarantee.",
        ],
        "exclusions": EXCLUSIONS,
        "references": {
            "workload": "LOCAL_FIRST_PLAN.md",
            "scorer_methodology": "experiments/minicheck/README.md",
            "scorer_measurements": "experiments/minicheck/benchmark.json",
            "prototype_latency_context": "LOCAL_FIRST_PLAN.md#what-we-actually-measured-on-big",
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    defaults = Assumptions()
    parser.add_argument("--users", type=int, default=defaults.users, help="Daily active users; nonnegative integer")
    parser.add_argument("--impressions-per-user", type=float, default=defaults.impressions_per_user, help="Visible impressions per user per day")
    parser.add_argument("--unique-fraction", type=float, default=defaults.unique_fraction, help="Distinct post versions / all impressions, 0..1")
    parser.add_argument("--factual-fraction", type=float, default=defaults.factual_fraction, help="In-scope factual versions / distinct versions, 0..1")
    parser.add_argument("--claims-per-version", type=float, default=defaults.claims_per_version, help="Mean claims per factual version; positive")
    parser.add_argument("--candidates-per-claim", type=int, default=defaults.candidates_per_claim, help="Passages compared per claim; positive integer")
    parser.add_argument("--pairs-per-second", type=float, default=defaults.pairs_per_second, help="Single-GPU comparison throughput; strictly positive")
    parser.add_argument("--generation-route-fraction", type=float, default=defaults.generation_route_fraction, help="Factual versions needing additional heavy work, 0..1")
    parser.add_argument("--heavy-seconds", type=float, default=defaults.heavy_seconds, help="Serialized single-GPU-equivalent seconds per heavier version; positive")
    parser.add_argument("--gpus", type=int, default=defaults.gpus, help="GPU count for nominal 24-hour denominator; positive integer")
    parser.add_argument("--json", action="store_true", help="Print machine-readable assumptions, arithmetic and caveats")
    arguments = vars(parser.parse_args(argv))
    json_output = arguments.pop("json")
    try:
        result = calculate(Assumptions(**arguments))
    except ValueError as error:
        parser.error(str(error))
    if json_output:
        print(json.dumps(result, indent=2, allow_nan=False))
        return 0
    work, compute = result["workload_per_day"], result["compute_per_day"]
    print("Illustrative daily local-compute workload")
    print(f"{work['feed_impressions']:,.0f} impressions → {work['distinct_post_versions']:,.0f} distinct versions → {work['factual_versions']:,.0f} factual versions")
    print(f"{work['claim_passage_comparisons']:,.0f} claim–passage comparisons; {work['heavier_route_versions']:,.0f} versions with additional heavy work")
    print(f"Scorer:        {compute['scorer_gpu_hours']:,.2f} GPU-hours/day")
    print(f"Heavier route: {compute['heavier_route_gpu_hours']:,.2f} GPU-hours/day (serialized equivalent)")
    print(f"Subtotal:      {compute['subtotal_gpu_hours']:,.2f} GPU-hours/day")
    print(f"Nominal share: {compute['percent_of_nominal_gpu_hours']:,.2f}% of {compute['nominal_gpu_hours']:,} GPU-hours/day ({arguments['gpus']} GPUs × 24 hours)")
    print()
    for statement in result["interpretation"]:
        print(statement)
    print("Excluded: " + "; ".join(EXCLUSIONS) + ".")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
