#!/usr/bin/env python3
"""Dependency-free planning calculator. No API calls or spending occur."""

import argparse
import json
from decimal import Decimal, InvalidOperation
from pathlib import Path


def decimal(value):
    return Decimal(str(value))


def nonnegative(value):
    try:
        number = decimal(value)
    except InvalidOperation as error:
        raise argparse.ArgumentTypeError("must be a finite nonnegative number") from error
    if not number.is_finite() or number < 0:
        raise argparse.ArgumentTypeError("must be a finite nonnegative number")
    return number


def calculate(config, scenario_name, tier="standard", search_provider=None,
              output_multiplier=Decimal("1"), license_daily=None):
    scenario = config["scenarios"][scenario_name]
    provider = search_provider or scenario["search_provider"]
    rates = config["rates"]
    multiplier = decimal(output_multiplier)
    if not multiplier.is_finite() or multiplier < 0:
        raise ValueError("output multiplier must be finite and nonnegative")
    license_cost = None if license_daily is None else decimal(license_daily)
    if license_cost is not None and (not license_cost.is_finite() or license_cost < 0):
        raise ValueError("license cost must be finite and nonnegative")

    costs = {}
    units = {}
    queries = 0
    for job_name in ("triage", "quick", "deep"):
        job = config["jobs"][job_name]
        model = rates["triage"] if job_name == "triage" else rates[tier]
        token_cost = (
            decimal(job["input_tokens"]) * decimal(model["input_per_million"])
            + decimal(job["output_tokens"]) * multiplier * decimal(model["output_per_million"])
        ) / Decimal(1_000_000)
        search_cost = (
            decimal(job["search_queries"]) * decimal(rates["search_per_thousand"][provider])
            / Decimal(1000)
        )
        units[job_name] = token_cost + search_cost
        costs[job_name] = units[job_name] * scenario[job_name]
        queries += scenario[job_name] * job["search_queries"]

    costs["media_lookup"] = decimal(scenario["media_units"]) * decimal(rates["web_detection_per_thousand"]) / 1000
    costs["x_reads"] = decimal(scenario["x_reads"]) * decimal(rates["x_post_read"])
    costs["infrastructure_allowance"] = decimal(scenario["infrastructure_daily"])
    subtotal = sum(costs.values(), Decimal(0))
    # Do not silently turn an unknown commercial license into a zero-dollar one.
    modeled_total = None if license_cost is None else subtotal + license_cost
    comparison = subtotal if modeled_total is None else modeled_total
    budget = decimal(scenario["budget"])
    return {
        "scenario": scenario_name,
        "tier": tier,
        "search_provider": provider,
        "output_multiplier": multiplier,
        "volumes": scenario,
        "unit_costs": units,
        "costs": costs,
        "search_queries": queries,
        "operating_subtotal": subtotal,
        "license_daily": license_cost,
        "modeled_total_before_tax_and_labor": modeled_total,
        "budget": budget,
        "remaining_before_unknowns": budget - comparison,
        "subtotal_within_budget": comparison <= budget,
        "all_in_feasibility_established": False,
        "caveats": config["caveats"]
    }


def main():
    config_path = Path(__file__).with_name("cost_assumptions.json")
    config = json.loads(config_path.read_text())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=config["scenarios"], default="pilot")
    parser.add_argument("--tier", choices=("standard", "contributor"), default="standard")
    parser.add_argument("--search-provider", choices=("native", "brave"))
    parser.add_argument("--output-multiplier", type=nonnegative, default=Decimal(1),
                        help="Stress all billed output, including triage and reasoning (default 1)")
    parser.add_argument("--license-daily", type=nonnegative, default=None,
                        help="Known daily platform/partner fee; omitted means unknown, not free")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = calculate(config, args.scenario, args.tier, args.search_provider,
                       args.output_multiplier, args.license_daily)
    if args.json:
        print(json.dumps(result, indent=2, default=str))
        return
    print(f"{args.scenario} | {args.tier} | {result['search_provider']} search")
    print(f"Rates verified {config['verified_on']}; planning assumptions, not measurements.")
    for name, cost in result["costs"].items():
        print(f"  {name.replace('_', ' '):27} ${cost:.4f}/day")
    print(f"Operating subtotal: ${result['operating_subtotal']:.4f}/day")
    if result["license_daily"] is None:
        print("Platform/partner license: UNKNOWN; all-in cost is not established.")
    else:
        print(f"License: ${result['license_daily']:.4f}/day")
        print(f"Modeled total before tax/labor: ${result['modeled_total_before_tax_and_labor']:.4f}/day")
    print(f"Remaining against ${result['budget']:.2f}, before unknowns: ${result['remaining_before_unknowns']:.4f}")
    print(f"Search queries/day: {result['search_queries']:,}")
    print("Deep work is incremental; queued or failed reviews are not completed coverage.")
    if args.tier == "contributor":
        print("Contributor permits model training on requests; verify rights and disclosures.")
    if result["search_provider"] == "native":
        print("Native query counts need enforceable limits before promising a hard cap.")
    print("Excluded: taxes, labor/development; infrastructure remains an allowance.")


if __name__ == "__main__":
    main()
