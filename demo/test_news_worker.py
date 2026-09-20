import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from demo import news_worker


class _Response:
    def __init__(self, value):
        self.value = value

    def read(self):
        return json.dumps(self.value).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class NewsWorkerTests(unittest.TestCase):
    def test_initial_due_handles_missing_recent_and_stale_reports(self):
        current = datetime(2026, 9, 19, 12, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            self.assertEqual(news_worker.initial_due(current, report), current + timedelta(seconds=120))
            report.write_text(json.dumps({"finished_at": "2026-09-19T10:00:00+00:00"}))
            self.assertEqual(news_worker.initial_due(current, report), current + timedelta(hours=2))
            report.write_text(json.dumps({"finished_at": "2026-09-19T01:00:00+00:00"}))
            self.assertEqual(news_worker.initial_due(current, report), current)

    def test_pending_probe_authenticates_without_logging_the_token(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("test-local-token")
            seen = {}
            def open_request(request, timeout):
                seen["url"] = request.full_url
                seen["token"] = request.get_header("X-factcheck-token")
                seen["timeout"] = timeout
                return _Response({"pending_jobs": 2})
            with patch("urllib.request.urlopen", open_request):
                self.assertEqual(news_worker.pending_jobs("http://127.0.0.1:8870", token), 2)
            self.assertEqual(seen, {"url": "http://127.0.0.1:8870/api/stats", "token": "test-local-token", "timeout": 3})

    def test_report_failure_overrides_zero_exit_claim(self):
        started = datetime(2026, 9, 19, 12, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            report.write_text(json.dumps({"finished_at": "2026-09-19T12:01:00+00:00", "documents": [{"status": "failed", "error": "model unavailable"}]}))
            self.assertEqual(news_worker.report_result(report, started)[0], False)
            report.write_text(json.dumps({"finished_at": "2026-09-19T12:01:00+00:00", "documents": [{"status": "processed", "candidates": []}]}))
            self.assertEqual(news_worker.report_result(report, started), (True, "complete"))


if __name__ == "__main__":
    unittest.main()
