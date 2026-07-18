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
     * The ONLY non-git commands that auto-run. The proven guarantee (verified across three security-review
     * rounds): NONE of these can, under ANY argument, read file CONTENTS, write a file, or execute a
     * program. That is the boundary that matters — it eliminates the whole zero-tap exfil/write/RCE class
     * that sank the earlier, larger allowlists (cat/grep, then find/awk/sed/command, then git --no-index/
     * file -f/tree -o/wc --files0-from — each hid an escape through its own flags). Commands here have no
     * content-read flag, no output-to-file flag, and no program argument; a write needs a shell redirect,
     * which SIDE_EFFECT already routes to ASK.
     *
     * NOT claimed: "touches nothing." An `echo` with a shell wildcard still enumerates filenames via SHELL
     * glob expansion (the shell readdir's, not echo), and which/whereis disclose tool paths — name/existence
     * recon,
     * strictly weaker than content/write/exec, and any follow-up Read of an enumerated path is still
     * workdir-scope-denied. The model reads project files through the structured Read/Grep/Glob tools
     * (workdir-scoped for bridges), never through Bash.
     */
    private val READ_ONLY = setOf(
        "pwd", "echo", "printf", "true", "false",
        "whoami", "hostname", "uname", "id", "groups",     // process / system identity — no filesystem
        "which", "type", "whereis",                        // PATH lookup (NOT `command`, which execs)
    )

    /**
     * git read subcommands are the ONE filesystem-touching family kept in ALLOW, because they are
     * high-value and high-frequency for a coding bot (history / status / diff) and their output is
     * project code that SecretRedactor scrubs. But git carries content-exfil (`--no-index` diffs any two
     * paths) and file-write (`--output=`) flags, and `config` sets `diff.external` → RCE — so the git
     * branch below excludes those subcommands AND [GIT_UNSAFE_FLAG]. It's a pragmatic exception, not a
     * proof of safety; everything else is proved safe by touching no filesystem at all.
     */
    private val GIT_READONLY = setOf(
        "log", "show", "status", "diff", "blame", "rev-parse", "describe",
        "ls-files", "ls-tree", "shortlog", "reflog", "rev-list",
    )

    // git flags that turn a read subcommand into an arbitrary-file read/write or code-exec — force ASK
    private val GIT_UNSAFE_FLAG = Regex("""(^|=|\b)(--no-index|--output|--ext-diff|--exec|-O)\b|--output=""")

    // metacharacters that can turn a read into a write or run a second command — presence forces ASK.
    // The bare `$` catches EVERY shell expansion, variable ($VAR) as well as command/parameter
    // substitution ($(…) and ${…}): without it, `echo $AWS_SECRET_ACCESS_KEY` classifies ALLOW and the
    // shell posts the expanded secret back into the group with zero owner approval.
    private val SIDE_EFFECT = Regex("""[>|&;`\n$]|<\(|<<""")

    fun classify(command: String): Verdict {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Verdict.ASK
        if (DANGEROUS.any { it.containsMatchIn(cmd) }) return Verdict.DENY
        // any redirect / pipe / chain / expansion / substitution / here-doc → can't prove read-only → ask the owner
        if (SIDE_EFFECT.containsMatchIn(cmd)) return Verdict.ASK
        val tokens = cmd.split(Regex("\\s+"))
        val head = tokens.firstOrNull()?.substringAfterLast('/') ?: return Verdict.ASK // strip any path prefix
        if (head == "git") {
            val sub = tokens.getOrNull(1)
            // a git write hides behind a read subcommand via flags: branch/tag -d, diff --no-index/--output → ask
            val unsafeFlag = tokens.any {
                it == "-d" || it == "-D" || it == "-f" || it == "--delete" || it == "--force" ||
                    GIT_UNSAFE_FLAG.containsMatchIn(it)
            }
            return if (sub in GIT_READONLY && !unsafeFlag) Verdict.ALLOW else Verdict.ASK
        }
        // the non-git ALLOW set touches no filesystem under any flag, so the head check is a real boundary
        return if (head in READ_ONLY) Verdict.ALLOW else Verdict.ASK
    }
}
