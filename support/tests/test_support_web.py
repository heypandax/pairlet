import importlib.util
import os
import pathlib
import unittest


MODULE_PATH = pathlib.Path(__file__).parents[1] / "web" / "server.py"
SPEC = importlib.util.spec_from_file_location("support_web", MODULE_PATH)
support_web = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(support_web)


class SupportWebTest(unittest.TestCase):
    def test_rate_limiter_hides_ip_and_enforces_minute_limit(self):
        limiter = support_web.RateLimiter(b"x" * 32, minute_limit=2, day_limit=10)
        self.assertNotIn("203.0.113.4", limiter.visitor_hash("203.0.113.4"))
        self.assertEqual((True, 0), limiter.allow("203.0.113.4", now=100))
        self.assertEqual((True, 0), limiter.allow("203.0.113.4", now=101))
        allowed, retry = limiter.allow("203.0.113.4", now=102)
        self.assertFalse(allowed)
        self.assertGreater(retry, 0)
        self.assertEqual((True, 0), limiter.allow("203.0.113.4", now=161))

    def test_rate_limiter_enforces_daily_limit(self):
        limiter = support_web.RateLimiter(b"y" * 32, minute_limit=10, day_limit=2)
        self.assertTrue(limiter.allow("198.51.100.7", now=1)[0])
        self.assertTrue(limiter.allow("198.51.100.7", now=2)[0])
        self.assertFalse(limiter.allow("198.51.100.7", now=3)[0])
        self.assertTrue(limiter.allow("198.51.100.7", now=86_401)[0])

    def test_session_key_is_stable_and_scoped_to_visitor(self):
        secret = b"z" * 32
        first = support_web.derive_session_key(secret, "visitor-a", "browser_session_1234")
        self.assertEqual(first, support_web.derive_session_key(secret, "visitor-a", "browser_session_1234"))
        self.assertNotEqual(first, support_web.derive_session_key(secret, "visitor-b", "browser_session_1234"))
        self.assertTrue(first.startswith("agent:cc-pocket-support:web-"))

    def test_extract_answer_uses_final_visible_text(self):
        payload = {
            "result": {
                "payloads": [{"text": "⚠️ internal diagnostic"}],
                "meta": {"finalAssistantVisibleText": "Public answer\nhttps://example.com/help"},
            }
        }
        self.assertEqual("Public answer\nhttps://example.com/help", support_web.extract_answer(payload))

    def test_extract_answer_skips_diagnostic_payload(self):
        payload = {
            "result": {
                "payloads": [
                    {"text": "⚠️ internal diagnostic"},
                    {"text": "Safe fallback answer"},
                ]
            }
        }
        self.assertEqual("Safe fallback answer", support_web.extract_answer(payload))

    def test_extract_answer_strips_leading_retrieval_narration(self):
        payload = {
            "result": {
                "meta": {
                    "finalAssistantVisibleText": (
                        "看起来手册是作为 URL 引用的。搜索结果命中 canonical，"
                        "这已经足够回答，不需要再查看代码。\n\n"
                        "普通用户应先安装 daemon。\n\n"
                        "Source: https://example.com/manual"
                    )
                }
            }
        }
        self.assertEqual(
            "普通用户应先安装 daemon。\n\nSource: https://example.com/manual",
            support_web.extract_answer(payload),
        )

    def test_agent_environment_does_not_inherit_web_secret(self):
        previous = os.environ.get("CC_SUPPORT_WEB_SECRET")
        os.environ["CC_SUPPORT_WEB_SECRET"] = "do-not-pass-to-agent"
        try:
            environment = support_web.agent_environment()
        finally:
            if previous is None:
                os.environ.pop("CC_SUPPORT_WEB_SECRET", None)
            else:
                os.environ["CC_SUPPORT_WEB_SECRET"] = previous
        self.assertNotIn("CC_SUPPORT_WEB_SECRET", environment)
        self.assertEqual("/home/admin", environment["HOME"])


if __name__ == "__main__":
    unittest.main()
