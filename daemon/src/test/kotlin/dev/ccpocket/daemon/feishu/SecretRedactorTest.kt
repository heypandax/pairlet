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
    fun a_prefixed_env_var_name_is_caught_too() {
        // The shapes a project's own .env is ACTUALLY made of. These all slipped through while the key pattern
        // was anchored with a leading \b: that boundary does not exist between `_` and a letter, so
        // `AWS_SECRET_ACCESS_KEY=…` never matched — and .env is the very file this scrub exists for.
        for ((line, secret) in listOf(
            "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI0K7MDENGbPxRfiCYEXAMPLEKEY" to "wJalrXUtnFEMI0K7MDENGbPxRfiCYEXAMPLEKEY",
            "GITHUB_TOKEN=0123456789abcdefghij" to "0123456789abcdefghij",
            "FEISHU_APP_SECRET=abcdefghijklmnop" to "abcdefghijklmnop",
            "STRIPE_SECRET_KEY=rk_live_0123456789" to "rk_live_0123456789",
            "SSHPASS=hunter2hunter2" to "hunter2hunter2",
            """  "accessToken": "0123456789abcdef"""" to "0123456789abcdef",
        )) {
            assertTrue(didRedact(line), "should redact: $line")
            assertFalse(secret in redactedText(line), "the value must be gone: ${redactedText(line)}")
        }
        // a credential embedded in a URL has no secret-ish key name at all to key off
        val dsn = "DATABASE_URL=postgres://appuser:s3cr3tpass@db.internal:5432/app"
        assertTrue(didRedact(dsn))
        assertFalse("s3cr3tpass" in redactedText(dsn), redactedText(dsn))
        val bare = "连的是 postgres://appuser:s3cr3tpass@db.internal:5432/app"
        assertFalse("s3cr3tpass" in redactedText(bare), redactedText(bare))
        assertTrue("db.internal" in redactedText(bare), "the host is not the secret: ${redactedText(bare)}")
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
