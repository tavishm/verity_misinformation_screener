"""Bounded local scheduler for refreshing official-source precompute work.

It never uses paid services. The worker is started only by a launcher that owns
the local web child, so a second foreground launcher cannot duplicate it.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "demo" / "data"
REPORT = ROOT / "experiments" / "politics" / "precompute-report.json"
STATE = DATA / "news-worker.json"
LOG = DATA / "news-worker.log"
TOKEN_FILE = DATA / "local-token"
INTERVAL = timedelta(hours=4)
FAILURE_BACKOFF = timedelta(hours=1)
BUSY_BACKOFF = timedelta(minutes=5)
INITIAL_WAIT = timedelta(seconds=120)
MAX_RUNTIME_SECONDS = 30 * 60


def now() -> datetime:
    return datetime.now(timezone.utc)


def stamp(value: datetime | None = None) -> str:
    return (value or now()).isoformat()


def atomic_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def finished_at(report: Path = REPORT) -> datetime | None:
    try:
        value = json.loads(report.read_text(encoding="utf-8")).get("finished_at")
        parsed = datetime.fromisoformat(value) if isinstance(value, str) else None
        return parsed.astimezone(timezone.utc) if parsed and parsed.tzinfo else None
    except (OSError, ValueError, json.JSONDecodeError, AttributeError):
        return None


def initial_due(current: datetime | None = None, report: Path = REPORT) -> datetime:
    """Use the latest completed precompute run; an absent report gets 120 seconds."""
    current = current or now()
    prior = finished_at(report)
    if prior is None:
        return current + INITIAL_WAIT
    return max(current, prior + INTERVAL)


def pending_jobs(api_base: str, token_file: Path = TOKEN_FILE) -> int | None:
    """Return the optional queue signal; older servers simply return None."""
    try:
        token = token_file.read_text(encoding="utf-8").strip()
        if not token:
            return None
        request = urllib.request.Request(api_base.rstrip("/") + "/api/stats",
                                         headers={"X-Factcheck-Token": token})
        with urllib.request.urlopen(request, timeout=3) as response:
            payload = json.loads(response.read().decode("utf-8"))
        value = payload.get("pending_jobs") if isinstance(payload, dict) else None
        return value if isinstance(value, int) and value >= 0 else None
    except (OSError, ValueError, json.JSONDecodeError):
        return None


def report_result(report: Path, started: datetime) -> tuple[bool, str]:
    """A zero exit is useful only when the fresh report is valid and clean."""
    try:
        payload = json.loads(report.read_text(encoding="utf-8"))
        finished = finished_at(report)
        if not isinstance(payload, dict) or finished is None or finished < started:
            return False, "missing or stale precompute report"
        documents = payload.get("documents")
        if not isinstance(documents, list):
            return False, "precompute report has no document statuses"
        bad = [row for row in documents if not isinstance(row, dict) or
               row.get("status") in {"failed", "partial_failure", "invalid_candidates"}]
        model_errors = [row for row in documents if isinstance(row, dict) and
                        (row.get("error") or any(candidate.get("pipeline", {}).get("error")
                                                  for candidate in row.get("candidates", [])
                                                  if isinstance(candidate, dict)))]
        if bad or model_errors:
            return False, f"precompute reported {len(bad) + len(model_errors)} failed document outcome(s)"
        return True, "complete" if documents else "complete with no eligible documents"
    except (OSError, ValueError, json.JSONDecodeError, AttributeError, TypeError):
        return False, "invalid precompute report"


def worker_command() -> list[str]:
    return [sys.executable, "-m", "demo.precompute", "--documents", "6",
            "--claims-per-document", "2", "--max-model-tokens", "2400", "--refresh"]


def record(state: dict, **changes: object) -> dict:
    state.update(changes)
    state["updated_at"] = stamp()
    atomic_json(STATE, state)
    return state


def sleep_until(due: datetime, stop: callable = lambda: False) -> bool:
    """Sleep in short intervals so service shutdown is prompt and observable."""
    while True:
        remaining = (due - now()).total_seconds()
        if remaining <= 0:
            return True
        if stop():
            return False
        time.sleep(min(60, max(1, remaining)))


def run_once(state: dict, api_base: str) -> tuple[dict, timedelta]:
    queued = pending_jobs(api_base)
    if queued is not None and queued > 0:
        return record(state, status="skipped_busy", pending_jobs=queued,
                      last_error=None, last_result="pending jobs active",
                      next_due_at=stamp(now() + BUSY_BACKOFF)), BUSY_BACKOFF
    started = now()
    record(state, status="running", pending_jobs=queued, last_started_at=stamp(started),
           last_error=None, command=worker_command())
    DATA.mkdir(parents=True, exist_ok=True)
    with LOG.open("a", encoding="utf-8") as log:
        log.write(f"\n[{stamp(started)}] starting bounded official-source precompute\n")
        log.flush()
        try:
            process = subprocess.Popen(worker_command(), cwd=ROOT, stdout=log, stderr=log)
        except OSError as error:
            finished = now()
            return record(state, status="failed_start", last_finished_at=stamp(finished),
                          last_error=f"could not start precompute: {error}",
                          last_result="start failure",
                          next_due_at=stamp(finished + FAILURE_BACKOFF)), FAILURE_BACKOFF
        try:
            code = process.wait(timeout=MAX_RUNTIME_SECONDS)
        except subprocess.TimeoutExpired:
            stop_process(process)
            finished = now()
            return record(state, status="failed_timeout", last_finished_at=stamp(finished),
                          last_error=f"precompute exceeded {MAX_RUNTIME_SECONDS}s",
                          last_result="timeout",
                          next_due_at=stamp(finished + FAILURE_BACKOFF)), FAILURE_BACKOFF
        except (KeyboardInterrupt, SystemExit):
            # The worker owns this foreground child. Stop it before propagating
            # the launcher/systemd shutdown so it cannot outlive the worker.
            stop_process(process)
            raise
    finished = now()
    if code == 0:
        valid, outcome = report_result(REPORT, started)
        if not valid:
            return record(state, status="failed_report", last_finished_at=stamp(finished), last_exit_code=code,
                          last_error=outcome, last_result="invalid or failed report",
                          next_due_at=stamp(finished + FAILURE_BACKOFF)), FAILURE_BACKOFF
        return record(state, status="complete", last_finished_at=stamp(finished), last_exit_code=code,
                      last_error=None, last_result=outcome,
                      next_due_at=stamp(finished + INTERVAL)), INTERVAL
    return record(state, status="failed", last_finished_at=stamp(finished), last_exit_code=code,
                  last_error=f"precompute exited {code}", last_result="nonzero exit",
                  next_due_at=stamp(finished + FAILURE_BACKOFF)), FAILURE_BACKOFF


def stop_process(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def main() -> int:
    def interrupt(_signal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupt)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-base", default="http://127.0.0.1:8870")
    parser.add_argument("--print-initial-due", action="store_true",
                        help="Print scheduling state without starting precompute.")
    args = parser.parse_args()
    due = initial_due()
    if args.print_initial_due:
        print(json.dumps({"report_finished_at": stamp(finished_at()) if finished_at() else None,
                          "initial_due_at": stamp(due)}))
        return 0
    state: dict = {"started_at": stamp(), "status": "waiting", "interval_seconds": int(INTERVAL.total_seconds()),
                   "max_runtime_seconds": MAX_RUNTIME_SECONDS, "paid_api_spend": 0,
                   "initial_due_at": stamp(due)}
    record(state, next_due_at=stamp(due))
    while sleep_until(due):
        state, delay = run_once(state, args.api_base)
        due = now() + delay
        # Persist the calculated next run even if the process is stopped while waiting.
        record(state, next_due_at=stamp(due))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
