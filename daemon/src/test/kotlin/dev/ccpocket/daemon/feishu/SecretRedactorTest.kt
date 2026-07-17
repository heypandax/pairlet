package dev.ccpocket.daemon.feishu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The outbound secret scrub — the defense against a bridge echoing a read secret into the group. */
class SecretRedactorTest {

    private fun redactedText(s: String) = SecretRedactor.redact(s).first
    private fun didRedact(s: String) = SecretRedactor.redact(s).second

    @Test
    fun the_reported_leak_is_masked_but_the_key_name_survives() {
        // exactly the screenshot: `password=1234` came back into the chat
        val (out, hit) = SecretRedactor.redact("文件里只有这一行：password=1234")
        assertTrue(hit)
        assertFalse("1234" in out, "the value must be gone: $out")
        assertTrue("password" in out, "the field name can stay — it's not the secret: $out")
    }

    @Test
    fun common_secret_shapes_are_caught() {
        for (s in listOf(
            "API_KEY: sk-abcdefghij0123456789ABCDEFGH",
            "client_secret = 9f8c7b6a5d4e3f2a1b0c",
            "export TOKEN=ghp_0123456789abcdefghijABCDEFGHIJ0123",
            "aws: AKIAIOSFODNN7EXAMPLE",
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4",
        )) {
            assertTrue(didRedact(s), "should redact: $s")
        }
        // a PEM private key block, whole
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----"
        assertTrue(didRedact(pem))
        assertFalse("b3BlbnNzaC" in redactedText(pem), "the key body must be gone")
    }

    @Test
    fun ordinary_prose_is_left_alone() {
        // no false positives on normal replies — the field WORD without a value isn't a secret
        for (s in listOf(
            "把 password 字段填成你的登录密码就行",
            "git status 显示有 3 个文件改动",
            "the token bucket algorithm limits requests",
        )) {
            assertFalse(didRedact(s), "should NOT redact: $s → ${redactedText(s)}")
            assertEquals(s, redactedText(s))
        }
    }
}
