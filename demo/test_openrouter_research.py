"""No-network tests for the opt-in OpenRouter research boundary."""
import asyncio
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from demo.openrouter_research import CostLedger, OpenRouterResearch


class _Response:
    def __init__(self, value):
        self.value = value

    def json(self):
        return self.value


class OpenRouterResearchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.key = root / "openrouter.key"
        self.key.write_text("test-key\n")
        self.ledger_path = root / "ledger.jsonl"

    def tearDown(self):
        self.temp.cleanup()

    def research(self, requester, *, opt_in=True):
        return OpenRouterResearch(enabled=True, cloud_data_opt_in=opt_in,
                                  key_path=self.key, ledger=CostLedger(self.ledger_path), requester=requester)

    def test_privacy_gate_does_not_read_key_or_contact_provider(self):
        calls = []
        async def requester(*args, **kwargs):
            calls.append((args, kwargs))
        result = asyncio.run(self.research(requester, opt_in=False).review("Vaccines prevent disease."))
        self.assertEqual(result["label"], "Unsure")
        self.assertEqual(calls, [])
        self.assertFalse(self.ledger_path.exists())

    def test_request_is_bounded_and_only_verified_literal_citation_can_publish(self):
        calls = []
        async def requester(method, url, **kwargs):
            calls.append((method, url, kwargs))
            if method == "POST":
                return _Response({"id": "gen-123", "usage": {"prompt_tokens": 23}, "choices": [{"message": {"content": json.dumps({"claims": [{
                    "text": "Vaccines prevent disease.", "verdict": "supported", "citations": [{
                        "url": "https://public.example/reference", "quote": "Vaccines prevent disease in this literal official passage."
                    }]}]})}}]})
            return _Response({"data": {"total_cost": 0.0125, "tokens_prompt": 23,
                              "tokens_completion": 42, "native_tokens_reasoning": 4,
                              "num_fetches": 2, "num_search_results": 4}})
        page = {"url": "https://public.example/reference", "title": "Reference",
                "text": "Vaccines prevent disease in this literal official passage."}
        with patch("demo.openrouter_research.fetch_page", return_value=page):
            result = asyncio.run(self.research(requester).review(
                "Vaccines prevent disease.", job_id="12345678-1234-4234-8234-123456789abc", owner_pseudonym="phone_1"))
        self.assertEqual(result["rating"], 4)
        self.assertFalse(result["whole_post_validated"])
        self.assertEqual(result["claims"][0]["evidence"][0]["quote"], page["text"])
        body = calls[0][2]["json"]
        self.assertEqual(body["model"], "deepseek/deepseek-v4.1-flash")
        self.assertEqual(body["provider"], {"data_collection": "deny", "allow_fallbacks": True})
        self.assertEqual(body["reasoning"], {"effort": "low"})
        self.assertEqual(body["max_tokens"], 1500)
        self.assertEqual(body["max_tool_calls"], 2)
        self.assertEqual(body["tools"][0]["parameters"]["max_total_results"], 4)
        self.assertEqual(calls[1][2]["params"], {"id": "gen-123"})
        lines = [json.loads(line) for line in self.ledger_path.read_text().splitlines()]
        self.assertEqual(lines[-1]["cost"], 0.0125)
        self.assertNotIn("Vaccines prevent disease.", self.ledger_path.read_text())
        self.assertNotIn("public.example", self.ledger_path.read_text())

    def test_unverifiable_or_rewritten_provider_claim_fails_closed(self):
        async def requester(method, _url, **_kwargs):
            if method == "POST":
                return _Response({"id": "gen-456", "choices": [{"message": {"content": json.dumps({"claims": [{
                    "text": "A rewritten assertion.", "verdict": "supported", "citations": [{"url": "https://example.org", "quote": "A quote that would be long enough."}]
                }]})}}]})
            return _Response({"data": {"total_cost": 0.001}})
        result = asyncio.run(self.research(requester).review("Original assertion.", job_id="22345678-1234-4234-8234-123456789abc"))
        self.assertEqual(result["label"], "Unsure")
        self.assertEqual(result["claims"], [])

    def test_completed_response_cost_does_not_wait_for_generation_receipt(self):
        calls = []
        async def requester(method, _url, **_kwargs):
            calls.append(method)
            if method != "POST":
                raise AssertionError("A delayed receipt must not block a completed result.")
            return _Response({"id": "gen-cost", "usage": {"cost": 0.0081, "prompt_tokens": 400,
                "completion_tokens": 80, "server_tool_use_details": {"web_search_requests": 1}},
                "choices": [{"message": {"content": json.dumps({"claims": [{"text": "Vaccines prevent disease.",
                    "verdict": "supported", "citations": [{"url": "https://example.org/source",
                    "quote": "Vaccines prevent disease in this literal official passage."}]}]})}}]})
        page = {"url": "https://example.org/source", "title": "Reference",
                "text": "Vaccines prevent disease in this literal official passage."}
        with patch("demo.openrouter_research.fetch_page", return_value=page):
            result = asyncio.run(self.research(requester).review("Vaccines prevent disease."))
        self.assertEqual(calls, ["POST"])
        self.assertEqual(result["rating"], 4)
        self.assertEqual(result["cost_usd"], 0.0081)
        row = json.loads(self.ledger_path.read_text().splitlines()[-1])
        self.assertEqual(row["web_search_requests"], 1)

    def test_ambiguous_charge_remains_reserved_and_same_job_never_retries(self):
        calls = []
        async def requester(method, _url, **_kwargs):
            calls.append(method)
            if method == "POST":
                raise RuntimeError("connection closed after request")
        research = self.research(requester)
        job = "32345678-1234-4234-8234-123456789abc"
        first = asyncio.run(research.review("Original assertion.", job_id=job))
        second = asyncio.run(research.review("Original assertion.", job_id=job))
        self.assertEqual(first["label"], "Unsure")
        self.assertEqual(second["label"], "Unsure")
        self.assertEqual(calls, ["POST"])
        rows = [json.loads(line) for line in self.ledger_path.read_text().splitlines()]
        self.assertEqual(rows[-1]["cost_state"], "unknown_reserved")
        self.assertEqual(rows[-1]["reserved_cost"], 0.10)


if __name__ == "__main__":
    unittest.main()
