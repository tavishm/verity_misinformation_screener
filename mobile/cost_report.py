#!/usr/bin/env python3
"""Export the latest accounting state per research request; no message data."""
import csv
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
ledger = ROOT / 'demo/data/openrouter-cost-ledger.jsonl'
states = {}
for line in ledger.read_text().splitlines() if ledger.exists() else []:
    row = json.loads(line)
    states[row['job_id']] = row
columns = ['timestamp', 'job_id', 'owner_pseudonym', 'model', 'generation_id',
           'prompt_tokens', 'completion_tokens', 'reasoning_tokens',
           'web_search_requests', 'web_fetch_requests', 'cost_state', 'cost', 'reserved_cost']
destination = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'mobile/research-costs.csv'
with destination.open('w', newline='') as handle:
    writer = csv.DictWriter(handle, fieldnames=columns, extrasaction='ignore')
    writer.writeheader()
    writer.writerows(states.values())
print(json.dumps({'requests': len(states),
                  'provider_reported_usd': round(sum(row.get('cost', 0) for row in states.values()), 9),
                  'unreconciled_reserve_usd': round(sum(row.get('reserved_cost', 0) for row in states.values()), 9),
                  'csv': str(destination)}, indent=2))
