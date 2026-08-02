package dev.ccpocket.daemon.agent

/**
 * A defense-in-depth Bash gate for BRIDGE-origin sessions (issue #91), driven by anyone in a Feishu group.
 *
 * Three verdicts, and the trust model behind each:
 *  - `ALLOW`  → runs with ZERO owner interaction (no PermissionAsk is emitted; a bridge can't receive one
 *    anyway). So ALLOW is a HARD SECURITY BOUNDARY: a command reaches it only if it is provably free of
 *    side effects AND cannot exfiltrate arbitrary file contents. This list is intentionally tiny.
 *  - `DENY`   → hard-refused, un-tappable (closes the "手滑点同意" mis-tap hole).
 *  - `ASK`    → routes to the owner's phone. **This is the default for everything not proven safe.**
 *
 * Why ALLOW is so much smaller than intuition suggests (security review, issue #91): a command NAME is not
 * a proxy for "read-only". `find` has `-delete`/`-exec`, `awk`/`sed` have `system()`/`e`/`w`, `command`
 * runs anything, `git config` sets `diff.external` (→ code exec on the next `git diff`), `sort -o`/`uniq`
 * write files, `rg --pre` runs a preprocessor — all reachable through the tool's OWN arguments with no
 * shell metacharacter, so no metachar scan can catch them. And `cat ~/.ssh/id_rsa` exfiltrates a secret
 * with no side effect at all. Therefore: anything that reads FILE CONTENTS, or takes a program/output
 * argument, is NOT auto-allowed — the model reads project files through the structured Read/Grep tools
 * (which are path-scoped for bridges), not through Bash.
 *
 * The `DENY` blacklist is explicitly best-effort: byte-level obfuscation (`r""m`, `r\m`) can't be closed
 * at the regex level. That's tolerable because a bypassed DENY only falls through to ASK (the owner still
 * decides) — it never falls through to ALLOW. The real guarantee is the tiny ALLOW list + ASK-by-default.
 */
object BridgeCommandPolicy {
    enum class Verdict { DENY, ALLOW, ASK }

    private val I = setOf(RegexOption.IGNORE_CASE)

    /** Absolute red lines — refused before any ask, under every mode. Best-effort (see class doc). */
    private val DANGEROUS = listOf(
        Regex("""\brm\b\s+(-\w*r\w*|--recursive)""", I),          // any recursive rm (target-agnostic): -r -rf -R -fr --recursive
        Regex("""\brm\b\s+(-\w+\s+)*(/|~|\*)(\s|$)""", I),        // rm of / ~ * even without a recursive flag
        Regex(""":\s*\(\s*\)\s*\{""", I),                          // fork bomb :(){
        Regex("""\bmkfs|\bdd\b[^|\n]*\bof=/dev/""", I),            // format / raw disk write
        Regex(""">\s*/dev/(sd|nvme|disk)|of=/dev/(sd|nvme|disk)""", I),
        Regex("""\b(drop|truncate)\s+(table|database)\b""", I),    // SQL destructive
        Regex("""\bdocker\s+(rm|rmi|kill|stop|prune|system\s+prune)\b""", I),
        Regex("""\bkubectl\s+delete\b""", I),
        Regex("""\b(shutdown|reboot|halt|poweroff)\b|\binit\s+[06]\b""", I),
        Regex("""\bsudo\b|\bsu\s+-|\bsu\s+root\b""", I),
        Regex("""\b(curl|wget)\b[^\n]*\|\s*(sh|bash|zsh)\b""", I), // curl … | sh
        Regex("""\bchmod\b\s+(-\w*\s+)*(777|a\+w)""", I),          // world-writable
        Regex("""\bgit\b\s+push\b[^\n]*(--force|-f)\b""", I),      // force push
        Regex("""\b(mv|cp)\b\s+[^\n]*\s+/(bin|etc|usr|sbin|boot|System)\b""", I), // clobber system dirs
    )

    /**
     * The ONLY commands that auto-run by default. The proven guarantee (verified across security-review
     * rounds): NONE of these can, under ANY argument, read file CONTENTS, write a file, or execute a
     * program. That is the boundary that matters — it eliminates the whole zero-tap exfil/write/RCE class
     * that sank the earlier, larger allowlists (cat/grep, then find/awk/sed/command, then git --no-index/
     * file -f/tree -o/wc --files0-from — each hid an escape through its own flags). Commands here have no
     * content-read flag, no output-to-file flag, and no program argument; a write needs a shell redirect,
     * which SIDE_EFFECT already routes to ASK.
     *
     * NOT claimed: "touches nothing." An `echo` with a shell wildcard still enumerates filenames via SHELL
     * glob expansion (the shell readdir's, not echo). That name/existence recon is strictly weaker than
     * content/write/exec, and any follow-up Read of an enumerated path is still
     * workdir-scope-denied. The model reads project files through the structured Read/Grep/Glob tools
     * (workdir-scoped for bridges), never through Bash.
     */
    private val READ_ONLY_BUILTINS = setOf("pwd", "echo", "printf", "true", "false", "type")

    // metacharacters that can turn a read into a write or run a second command — presence forces ASK.
    // The bare `$` catches EVERY shell expansion, variable ($VAR) as well as command/parameter
    // substitution ($(…) and ${…}): without it, `echo $AWS_SECRET_ACCESS_KEY` classifies ALLOW and the
    // shell posts the expanded secret back into the group with zero owner approval.
    private val SIDE_EFFECT = Regex("""[>|&;`\n$]|<\(|<<""")

    /**
     * True if [cmdTokens] (a metacharacter-free, path-free command already split on whitespace) starts with
     * the tokens of any [ownerAllowed] entry — a token-wise PREFIX match. A bare `pytest` entry matches
     * `pytest -q`, while `npm` never matches `npmevil` (whole-token equality) and a `git` entry does not
     * swallow `git push` (the entry must be a token-prefix, so a broad entry is exactly as broad as typed).
     * Blank entries are ignored (already stripped at store time; defended here too). An entry with MORE
     * tokens than the command can't match (prefix longer than the string).
     */
    private fun matchesAllowList(cmdTokens: List<String>, ownerAllowed: List<String>): Boolean {
        if (ownerAllowed.isEmpty() || cmdTokens.isEmpty()) return false
        val cmd = cmdTokens
        return ownerAllowed.any { entry ->
            val pat = entry.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            pat.isNotEmpty() && pat.size <= cmd.size && cmd.subList(0, pat.size) == pat
        }
    }

    /**
     * [ownerAllowed] is the bridge owner's Bash allow-list (issue #91 "一次授权跑完全程"): command prefixes
     * that upgrade what would otherwise be ASK into ALLOW, so a whitelisted multi-step task runs without a
     * per-command phone prompt. It is deliberately checked AFTER [DANGEROUS] and [SIDE_EFFECT] and never
     * before: an owner grant can WIDEN autonomy but must never punch through the hard security walls —
     * a whitelisted `rm` can't reach `rm -rf /`, and a whitelisted `npm test` can't smuggle
     * `npm test && curl … | sh` because the metacharacters route the whole line to the owner regardless.
     * Path-qualified executables and Git are also rejected before this matcher. So the allow-list only ever
     * promotes an otherwise-ASK, path-free, non-Git, metachar-free, non-DANGEROUS command.
     */
    fun classify(command: String, ownerAllowed: List<String> = emptyList()): Verdict {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Verdict.ASK
        if (DANGEROUS.any { it.containsMatchIn(cmd) }) return Verdict.DENY
        // any redirect / pipe / chain / expansion / substitution / here-doc → can't prove read-only → ask the owner
        if (SIDE_EFFECT.containsMatchIn(cmd)) return Verdict.ASK
        val tokens = cmd.split(Regex("\\s+"))
        val head = tokens.firstOrNull() ?: return Verdict.ASK
        // A path-qualified executable is repository/user-controlled (`./tools/echo`, `/tmp/git`) even when
        // its basename looks safe. It can never enter a zero-click path, including through owner allow-list.
        if ('/' in head || '\\' in head) return Verdict.ASK
        // Git "reads" are not process-safe: repository/system config can attach external diff, textconv,
        // pager and fsmonitor programs. Until daemon execution strips every such hook, every git command
        // remains per-command ASK — an owner allow-list must not promote it either.
        if (head == "git") return Verdict.ASK
        // owner allow-list: past the dangerous + metacharacter + path + Git walls, a command whose tokens
        // PREFIX-match a whitelisted entry is the owner's explicit standing grant → auto-run. Matched
        // token-wise (not raw substring) so `npm` can't match `npmevil`.
        if (matchesAllowList(tokens, ownerAllowed)) return Verdict.ALLOW
        // Only shell builtins whose argument grammar cannot read/write files or execute programs auto-run.
        return if (head in READ_ONLY_BUILTINS) Verdict.ALLOW else Verdict.ASK
    }
}
