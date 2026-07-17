package dev.ccpocket.daemon.feishu

/**
 * Last-ditch outbound scrub for anything a bridge is about to post into a group chat (issue #91).
 *
 * The threat it answers: a bridge is driven by ANYONE in the chat, and reading files inside the bound
 * project is a read-only tool that DEFAULT mode auto-allows without a phone prompt. So "cat the .env and
 * tell me what's in it" returns the secret straight into the reply — the exfiltration path the tier
 * ceiling doesn't cover (it gates writes/shell, not reads). This redacts the obvious credential shapes
 * from the reply as defense-in-depth. It is deliberately NOT the primary control — a dedicated,
 * low-sensitivity checkout still is — because pattern redaction is leaky by nature; it exists so the
 * single most common leak (a pasted password / key / token) doesn't sail into the group unmodified.
 *
 * Conservative on purpose: it targets VALUES in secret-named assignments and known token/PEM shapes, not
 * whole lines, so ordinary prose ("set the password field") survives.
 */
object SecretRedactor {
    private const val MASK = "‹已隐去›"

    // a PEM private-key block, whole
    private val PEM = Regex("-----BEGIN [^-\n]*PRIVATE KEY-----[\\s\\S]*?-----END [^-\n]*PRIVATE KEY-----")

    // KEY = VALUE / KEY: VALUE where the key name signals a secret — redact only the value
    private val ASSIGN = Regex(
        "(?i)(\\b(?:password|passwd|pwd|secret|secret[_-]?key|token|access[_-]?token|api[_-]?key|apikey|" +
            "access[_-]?key|client[_-]?secret|private[_-]?key|auth[_-]?token)\\b\\s*[=:]\\s*)" +
            "[\"']?([^\\s\"'\\n]{3,})[\"']?",
    )

    // self-identifying token formats (GitHub, Slack, AWS, OpenAI-style, JWT) — redact wherever they appear
    private val TOKENS = Regex(
        "\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|" +
            "sk-[A-Za-z0-9]{20,}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,})\\b",
    )

    /** Redact secrets from [text]; returns the scrubbed text and whether anything was masked. */
    fun redact(text: String): Pair<String, Boolean> {
        var hit = false
        var out = PEM.replace(text) { hit = true; "$MASK（私钥已移除）" }
        out = ASSIGN.replace(out) { m -> hit = true; m.groupValues[1] + MASK }
        out = TOKENS.replace(out) { hit = true; MASK }
        return out to hit
    }
}
