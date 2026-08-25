package dev.ccpocket.daemon.disk

/**
 * Recognizes working directories that live under the machine's throwaway temp space (issue #290).
 *
 * Why: tooling that drives an agent CLI programmatically (the reported case: a dsh plugin spawning one
 * OpenCode session per image under `%LOCALAPPDATA%\Temp\modlens-work-*`) leaves dozens of one-shot cwds
 * in the transcript stores. Each becomes a project row and drowns the real projects. Membership here is
 * decided purely from the path — no plugin/tool blocklists — and the caller decides what to do with it
 * (the directory list hides such rows unless something live keeps them relevant).
 */
internal object TempDirs {

    private val systemRoots: List<String> by lazy { roots() }

    /** The temp roots for THIS machine, normalized. Parameters are test seams; production uses defaults. */
    internal fun roots(
        javaTmp: String? = System.getProperty("java.io.tmpdir"),
        envTemp: String? = System.getenv("TEMP"),
        envTmp: String? = System.getenv("TMP"),
    ): List<String> = buildList {
        listOfNotNull(javaTmp, envTemp, envTmp).forEach(::add)
        // classic Unix + the macOS per-user temp tree (java.io.tmpdir points at ONE subdir of it —
        // the broad roots catch a cwd created under another confstr invocation)
        add("/tmp"); add("/private/tmp"); add("/var/folders"); add("/private/var/folders")
    }.mapNotNull(::norm).distinct()

    fun isUnderSystemTemp(path: String, roots: List<String> = systemRoots): Boolean {
        val p = norm(path) ?: return false
        // roots are re-normalized so injected test/caller values in any spelling behave like defaults
        return roots.mapNotNull(::norm).any { r -> p == r || p.startsWith("$r/") }
    }

    /** Slash-normalized, trailing separators dropped; Windows-shaped paths (drive letter) fold case —
     *  NTFS is case-insensitive while Unix filesystems are not, so `/TMP` must stay distinct from `/tmp`. */
    private fun norm(path: String): String? {
        val s = path.replace('\\', '/').trimEnd('/')
        if (s.isBlank()) return null
        val windowsShaped = s.length >= 2 && s[1] == ':' && s[0].isLetter()
        return if (windowsShaped) s.lowercase() else s
    }
}
