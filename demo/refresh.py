"""Reload the local evidence index after fetching/updating source records."""
import json
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler

if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1]
    token = (root / 'demo/data/local-token').read_text().strip()
    request = Request('http://127.0.0.1:8870/api/reload', data=b'{}', method='POST',
                      headers={'Content-Type': 'application/json', 'X-Factcheck-Token': token})
    with build_opener(ProxyHandler({})).open(request, timeout=20) as response:
        print(json.dumps(json.load(response), indent=2))
