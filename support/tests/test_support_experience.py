import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SUPPORT_HTML = ROOT / "site" / "support" / "index.html"
SUPPORT_JS = ROOT / "site" / "support" / "support.js"
SUPPORT_CSS = ROOT / "site" / "support" / "support.css"
OPEN_URL = ROOT / "mobile" / "composeApp" / "src" / "commonMain" / "kotlin" / "dev" / "ccpocket" / "app" / "OpenUrl.kt"
HELP_SCREEN = ROOT / "mobile" / "composeApp" / "src" / "commonMain" / "kotlin" / "dev" / "ccpocket" / "app" / "ui" / "HelpLearning.kt"
PROVISION = ROOT / "scripts" / "provision-openclaw-support.sh"
SUPPORT_AGENT = ROOT / "support" / "openclaw" / "support-workspace" / "AGENTS.md"


class SupportExperienceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.html = SUPPORT_HTML.read_text(encoding="utf-8")
        cls.js = SUPPORT_JS.read_text(encoding="utf-8")
        cls.css = SUPPORT_CSS.read_text(encoding="utf-8")
        cls.open_url = OPEN_URL.read_text(encoding="utf-8")
        cls.help_screen = HELP_SCREEN.read_text(encoding="utf-8")
        cls.provision = PROVISION.read_text(encoding="utf-8")
        cls.support_agent = SUPPORT_AGENT.read_text(encoding="utf-8")

    def test_public_home_is_task_first_without_legacy_channel_picker(self):
        self.assertIn('id="home-question-form"', self.html)
        self.assertIn('data-help-home', self.html)
        self.assertEqual(5, len(re.findall(r'data-guide="[^"]+"', self.html)))
        for removed in (
            "Give the manual to AI",
            "把手册交给 AI",
            "copy-ai",
            "support-hero",
            "support-grid",
            "data-focus-support",
        ):
            self.assertNotIn(removed, self.html)

    def test_app_direct_url_keeps_context_out_of_the_request_query(self):
        self.assertIn(
            'const val SUPPORT_CHAT_URL = "${SUPPORT_URL}?mode=chat&source=app"',
            self.open_url,
        )
        self.assertIn('"$SUPPORT_CHAT_URL#ctx=', self.open_url)
        self.assertIn("SupportContext", self.open_url)
        self.assertIn("params.get('mode') === 'chat'", self.js)
        self.assertIn("params.get('source') === 'app'", self.js)
        self.assertIn("window.location.hash.match(/^#ctx=", self.js)
        self.assertIn("requestBody.context = appContext", self.js)
        self.assertNotIn("未附带任何会话、路径、模型或日志", self.html)

    def test_native_support_entry_is_one_clickable_row(self):
        body = self.help_screen.split("private fun SmartSupportCard", 1)[1].split(
            "private fun HelpTaskCard", 1
        )[0]
        self.assertIn("openWebUrl(supportChatUrl(supportContext))", body)
        self.assertEqual(1, body.count(".clickable"))
        self.assertNotIn("HelpAction(", body)

    def test_mobile_textareas_do_not_trigger_ios_focus_zoom(self):
        self.assertRegex(
            self.css,
            r"\.question-composer textarea\{[^}]*font-size:16px",
        )
        self.assertRegex(
            self.css,
            r"\.chat-composer textarea\{[^}]*font-size:16px",
        )
        self.assertIn("interactive-widget=resizes-content", self.html)

    def test_mobile_public_status_pill_does_not_truncate_privacy_copy(self):
        self.assertIn("公开 · 无需登录 · 无账号历史", self.html)
        self.assertNotIn("不保存历史", self.html)
        self.assertRegex(
            self.css,
            r"\.public-pill\{[^}]*overflow:visible[^}]*white-space:normal",
        )
        self.assertNotIn(
            "max-width:144px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap",
            self.css,
        )

    def test_chat_waiting_and_failure_preserve_the_question(self):
        self.assertIn(
            "Searching the verified manual · complex questions may take about 1 minute",
            self.js,
        )
        self.assertIn("正在检索已核验手册 · 复杂问题可能需要约 1 分钟", self.js)
        self.assertIn("input.value = question", self.js)
        self.assertIn("retry.addEventListener('click'", self.js)
        self.assertIn("matchGuides(question, 2)", self.js)
        self.assertIn("@media (prefers-reduced-motion:reduce)", self.css)

    def test_public_submit_uses_history_for_native_browser_back(self):
        self.assertIn("window.history.pushState({ ccpSupportView: 'chat' }", self.js)
        self.assertIn("window.addEventListener('popstate'", self.js)
        self.assertIn("window.location.hash === '#chat'", self.js)

    def test_anonymous_abuse_protection_keeps_pass_out_of_persistent_storage(self):
        self.assertIn('id="support-security"', self.html)
        self.assertIn("turnstileToken", self.js)
        self.assertIn("supportPass", self.js)
        self.assertIn("human_verification_required", self.js)
        self.assertIn("daily_budget_exhausted", self.js)
        self.assertNotRegex(self.js, r"(?:localStorage|sessionStorage)\.(?:setItem|getItem)\([^\n]*supportPass")
        self.assertNotRegex(self.js, r"URLSearchParams\([^\n]*supportPass")
        self.assertIn("客服运行记录最多保留 30 天", self.html)
        self.assertIn("匿名对话不会写入共享知识队列", self.html)
        self.assertIn("isTrustedSupportUrl", self.js)
        self.assertIn("parsed.hostname === 'heypandax.github.io'", self.js)
        self.assertIn("parsed.hostname === 'github.com'", self.js)

    def test_public_agent_has_no_shared_writable_mount_and_bounded_sandbox(self):
        support_branch = self.provision.split('if role == "support":', 1)[1].split(
            'elif role == "reviewer":', 1
        )[0]
        self.assertIn('binds = [f"{repo}:/repo:ro"]', support_branch)
        self.assertNotIn('/queue:rw', support_branch)
        for contract in (
            '"scope": "session"',
            '"readOnlyRoot": True',
            '"pidsLimit": 64',
            '"memory": "256m"',
            '"cpus": 0.5',
            '"maxTokens": 2048',
            'contextTokens", "value": 24000',
            '"loopDetection": {"enabled": true}',
        ):
            self.assertIn(contract, self.provision)
        self.assertIn("Anonymous support traffic must never", self.support_agent)
        self.assertNotIn("support-kb.py capture", self.support_agent)


if __name__ == "__main__":
    unittest.main()
