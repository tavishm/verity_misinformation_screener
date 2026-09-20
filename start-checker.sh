#!/usr/bin/env bash
set -euo pipefail
FACTCHECK_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ "${1:-}" == "--status" ]]; then
  python3 "$FACTCHECK_ROOT/demo/run.py" --status
  exit
fi
if [[ "${1:-}" == "--stop" ]]; then
  systemctl --user stop fact-checker-local.service
  echo 'Local checker stopped. The model on big remains loaded.'
  exit
fi
if ! systemctl --user show-environment >/dev/null 2>&1; then
  echo 'Background services are unavailable. Running in this terminal; keep it open.'
  exec python3 "$FACTCHECK_ROOT/demo/run.py"
fi
if ! systemctl --user is-active --quiet fact-checker-local.service; then
  systemctl --user reset-failed fact-checker-local.service >/dev/null 2>&1 || true
  systemd-run --user --unit=fact-checker-local --collect \
    --property=Restart=on-failure --property=RestartSec=5 \
    --property="WorkingDirectory=$FACTCHECK_ROOT" \
    "$(command -v python3)" "$FACTCHECK_ROOT/demo/run.py"
fi
python3 - <<'PY'
import time, urllib.request
for _ in range(45):
    try:
        with urllib.request.urlopen('http://127.0.0.1:8870/', timeout=1) as response:
            if response.status == 200:
                print('Forward Check phone pairing: http://127.0.0.1:8870/mobile')
                break
    except Exception:
        pass
    time.sleep(1)
else:
    print('Service is still starting. Status: ./start-checker.sh --status')
    print('Startup log: journalctl --user -u fact-checker-local -n 20')
PY
