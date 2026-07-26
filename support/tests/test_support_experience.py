import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SUPPORT_HTML = ROOT / "site" / "support" / "index.html"
SUPPORT_JS = ROOT / "site" / "support" / "support.js"
SUPPORT_CSS = ROOT / "site" / "support" / "support.css"
OPEN_URL = ROOT / "mobile" / "composeApp" / "src" / "commonMain" / "kotlin" / "dev" / "ccpocket" / "app" / "OpenUrl.kt"
HELP_SCREEN = ROOT / "mobile" / "composeApp" / "src" / "commonMain" / "kotlin" / "dev" / "ccpocket" / "app" / "ui" / "HelpLearning.kt"


class SupportExperienceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.html = SUPPORT_HTML.read_text(encoding="utf-8")
        cls.js = SUPPORT_JS.read_text(encoding="utf-8")
        cls.css = SUPPORT_CSS.read_text(encoding="utf-8")
        cls.open_url = OPEN_URL.read_text(encoding="utf-8")
        cls.help_screen = HELP_SCREEN.read_text(encoding="utf-8")

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


if __name__ == "__main__":
    unittest.main()
