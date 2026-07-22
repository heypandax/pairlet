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

    /**
     * True if [cmdTokens] (a metacharacter-free command already split on whitespace) starts with the tokens
     * of any [ownerAllowed] entry — a token-wise PREFIX match. The first token is compared by basename on
     * BOTH sides, so `/usr/local/bin/npm test` matches an entry `npm test` and a bare `pytest` entry matches
     * `pytest -q` — while `npm` never matches `npmevil` (whole-token equality) and a `git` entry does not
     * swallow `git push` (the entry must be a token-prefix, so a broad entry is exactly as broad as typed).
     * Blank entries are ignored (already stripped at store time; defended here too). An entry with MORE
     * tokens than the command can't match (prefix longer than the string).
     */
    private fun matchesAllowList(cmdTokens: List<String>, ownerAllowed: List<String>): Boolean {
        if (ownerAllowed.isEmpty() || cmdTokens.isEmpty()) return false
        fun norm(tokens: List<String>): List<String> =
            tokens.mapIndexed { i, t -> if (i == 0) t.substringAfterLast('/') else t }
        val cmd = norm(cmdTokens)
        return ownerAllowed.any { entry ->
            val pat = norm(entry.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
            pat.isNotEmpty() && pat.size <= cmd.size && cmd.subList(0, pat.size) == pat
        }
    }

    /**
     * [ownerAllowed] is the bridge owner's Bash allow-list (issue #91 "一次授权跑完全程"): command prefixes
     * that upgrade what would otherwise be ASK into ALLOW, so a whitelisted multi-step task runs without a
     * per-command phone prompt. It is deliberately checked AFTER [DANGEROUS] and [SIDE_EFFECT] and never
     * before: an owner grant can WIDEN autonomy but must never punch through the two hard security walls —
     * a whitelisted `rm` can't reach `rm -rf /`, and a whitelisted `npm test` can't smuggle
     * `npm test && curl … | sh` because the metacharacters route the whole line to the owner regardless.
     * So the allow-list only ever promotes an otherwise-ASK, metachar-free, non-DANGEROUS command.
     */
    fun classify(command: String, ownerAllowed: List<String> = emptyList()): Verdict {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Verdict.ASK
        if (DANGEROUS.any { it.containsMatchIn(cmd) }) return Verdict.DENY
        // any redirect / pipe / chain / expansion / substitution / here-doc → can't prove read-only → ask the owner
        if (SIDE_EFFECT.containsMatchIn(cmd)) return Verdict.ASK
        val tokens = cmd.split(Regex("\\s+"))
        val head = tokens.firstOrNull()?.substringAfterLast('/') ?: return Verdict.ASK // strip any path prefix
        // git's unsafe FLAGS / `config` subcommand are a HARD wall, checked BEFORE the owner allow-list — a
        // whitelisted `git diff` / `git log` / bare `git` entry must NOT re-open `--no-index` (arbitrary read),
        // `--output=` (arbitrary write) or `git config diff.external` (→ RCE on the next diff). Like DANGEROUS,
        // this can never be promoted to a zero-prompt ALLOW; it always routes to the owner (crypto review #91).
        if (head == "git" && gitUnsafe(tokens)) return Verdict.ASK
        // owner allow-list: past the DANGEROUS + metacharacter + git-unsafe walls, a command whose tokens
        // PREFIX-match a whitelisted entry is the owner's explicit standing grant → auto-run. Matched
        // token-wise (not raw substring) so `npm` can't match `npmevil` and `git push` isn't matched by a
        // `git diff` entry. An owner CAN widen to a normal git subcommand (e.g. whitelisting `git push`).
        if (matchesAllowList(tokens, ownerAllowed)) return Verdict.ALLOW
        if (head == "git") {
            // unsafe flags already handled above; here only the built-in read-only-subcommand allowance
            return if (tokens.getOrNull(1) in GIT_READONLY) Verdict.ALLOW else Verdict.ASK
        }
        // the non-git ALLOW set touches no filesystem under any flag, so the head check is a real boundary
        return if (head in READ_ONLY) Verdict.ALLOW else Verdict.ASK
    }

    /** git's arbitrary-file-read / write / code-exec surface — the `config` subcommand (sets diff.external →
     *  RCE) plus branch/tag delete/force and the [GIT_UNSAFE_FLAG] read/write/exec flags. A wall that stands
     *  over the owner allow-list, so whitelisting a git command never silently re-enables this class. */
    private fun gitUnsafe(tokens: List<String>): Boolean =
        tokens.getOrNull(1) == "config" || tokens.any {
            it == "-d" || it == "-D" || it == "-f" || it == "--delete" || it == "--force" ||
                GIT_UNSAFE_FLAG.containsMatchIn(it)
        }
}
