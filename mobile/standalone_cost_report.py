#!/usr/bin/env python3
"""Combine development ledgers and a metadata-only phone snapshot; no API calls."""
import argparse
import csv
import datetime as dt
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def latest(path, key):
    rows = {}
    if path.exists():
        for line in path.read_text().splitlines():
            row = json.loads(line)
            rows[row[key]] = row
    return list(rows.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phone-state', type=Path, required=True,
                        help='JSON array exported from phone accounting; no message contents')
    parser.add_argument('--output', type=Path, default=ROOT / 'mobile/accounting')
    args = parser.parse_args()
    rows = []

    def add(scope, row, identity, cost_key, reserve, timestamp):
        cost = row.get(cost_key)
        usage = row.get('usage', row)
        rows.append(dict(scope=scope, request_id=row[identity], timestamp=timestamp,
                         model=row.get('model', ''),
                         state=row.get('cost_state', row.get('state', '')),
                         provider_reported_usd=cost if cost is not None else '',
                         unreconciled_reserve_usd=reserve if cost is None else 0,
                         prompt_tokens=usage.get('prompt_tokens', ''),
                         completion_tokens=usage.get('completion_tokens', ''),
                         generation_id=row.get('generation_id', '')))

    for row in latest(ROOT / 'demo/data/openrouter-cost-ledger.jsonl', 'job_id'):
        add('earlier_research', row, 'job_id', 'cost', row.get('reserved_cost', 0), row.get('timestamp', ''))
    for row in latest(ROOT / 'mobile/classifier/contextual/generation-costs.jsonl', 'request_id'):
        add('synthetic_training_examples', row, 'request_id', 'cost_usd', row.get('reserved_usd', 0), row.get('time_ms', ''))
    for row in json.loads(args.phone_state.read_text()):
        add('phone_' + row.get('kind', 'unknown'), row, 'id', 'cost', .10, row.get('time_ms', ''))

    buckets = {}
    for row in rows:
        bucket = buckets.setdefault(row['scope'], dict(requests=0, provider_reported_usd=0., unreconciled_reserve_usd=0.))
        bucket['requests'] += 1
        bucket['provider_reported_usd'] += row['provider_reported_usd'] or 0
        bucket['unreconciled_reserve_usd'] += row['unreconciled_reserve_usd']
    summary = dict(generated_at=dt.datetime.now(dt.timezone.utc).isoformat(),
                   phone_snapshot=str(args.phone_state), scopes=buckets,
                   provider_reported_usd=sum(b['provider_reported_usd'] for b in buckets.values()),
                   unreconciled_reserve_usd=sum(b['unreconciled_reserve_usd'] for b in buckets.values()),
                   notes=['Unreconciled reservations are possible charges, not confirmed spending.',
                          'Phone charges are current only through the supplied snapshot.',
                          'This is project accounting, not a full OpenRouter account statement; other tasks may use separate ledgers.'])
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / 'standalone-costs.csv').open('w', newline='') as out:
        writer = csv.DictWriter(out, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    (args.output / 'standalone-costs.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
