package dev.ccpocket.daemon.bridge

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.BridgeCredential
import dev.ccpocket.protocol.BridgeRunnerSpec
import dev.ccpocket.protocol.BridgeRunnerState
import dev.ccpocket.protocol.PocketJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** A managed runner's persisted config. [spec].env holds IM app secrets — this file is 0600.
 *  [bridgeSpec] is set for IN-PROCESS (built-in) bridges only: they never touch the registry (no ticket,
 *  no deviceId), so their workdirs/tier/caps live here — the engine builds its BridgeGuard from this. */
@Serializable
private data class RunnerEntry(val name: String, val spec: BridgeRunnerSpec, val bridgeSpec: BridgeSpec? = null)

/**
 * Daemon-managed adapter processes (issue #91 follow-up).
 *
 * Why the daemon runs these at all: an adapter is a long-lived process that must survive logout, reboot
 * and its own crashes. Making the owner arrange that by hand (nohup / launchd / systemd) was the step
 * that kept this feature effectively terminal-only. The daemon already solves exactly this problem for
 * itself, so it solves it for the adapter too.
 *
 * Each runner gets its own state directory `~/.cc-pocket/runners/<name>/`, which is the process's cwd:
 *  - the daemon writes `bridge-credential.json` there at MINT time (the only moment the plaintext ticket
 *    exists — see CreateBridge.runner), so the owner never handles it;
 *  - the adapter's own artifacts (`.pocket-device.json`, `.pocket-routes.json`) land there too, next to
 *    the credential rather than inside a source checkout;
 *  - the ENTRYPOINT still lives in the checkout ([BridgeRunnerSpec.scriptPath]), and Python puts the
 *    script's own directory on sys.path — so `import pocket_client` resolves from the checkout while cwd
 *    stays here. That split is what lets state live outside the repo without touching the adapter.
 */
class BridgeRunners(
    private val rootDir: File = File(System.getProperty("user.home"), ".cc-pocket/runners"),
    private val store: File = File(System.getProperty("user.home"), ".cc-pocket/runners.json"),
) {
    private val log = logger("BridgeRunners")
    private val entries = ConcurrentHashMap<String, RunnerEntry>()
    private val procs = ConcurrentHashMap<String, Process>()
    // in-process engines (the BUILT-IN adapters): a managed runner with a blank scriptPath, hosted inside
    // the daemon and rebuilt from its RunnerEntry on start. Kept kind-AGNOSTIC — the daemon's run path
    // registers a factory PER IM kind ([registerEngine]); a kind with no registered factory simply has no
    // built-in adapter (a bare test registers none, so it can't accidentally start one). This is what keeps
    // generic runner infrastructure from depending on any one concrete IM (issue #91 design review).
    private val engineFactories = ConcurrentHashMap<String, InProcessEngineFactory>()
    private val engines = ConcurrentHashMap<String, InProcessBridgeEngine>()
    private val logs = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val lastError = ConcurrentHashMap<String, String>()
    private val startedAt = ConcurrentHashMap<String, Long>()
    // Every entries mutation and its runners.json replacement is one transaction. Readers may observe the
    // old entry until the atomic rename completes, but can never observe a candidate that exists only in
    // memory and would disappear (or resurrect broader disk authority) after a daemon restart.
    private val persistLock = Any()
    // Owner control frames are dispatched concurrently. Every lifecycle transition for one runner name
    // therefore shares one coroutine mutex: reconfigure/detach cannot expose the old entry to a racing start,
    // and a start cannot recreate an old-authority engine between revoke and durable replacement.
    private val lifecycleLocks = ConcurrentHashMap<String, Mutex>()
    // the exit code must be remembered SEPARATELY: [pump] drops the dead Process from `procs`, so by the
    // time the owner's page asks, there is no Process left to read exitValue() off
    private val exitCodes = ConcurrentHashMap<String, Int>()

    init {
        runCatching {
            if (store.exists()) {
                PocketJson.decodeFromString<Map<String, RunnerEntry>>(store.readText())
                    .forEach { (k, v) -> entries[k] = v }
            }
        }.onFailure { log.warn("runners.json unreadable (${it.message}) — starting with none") }
    }

    /** The state dir that is a runner's cwd. Created 0700: it holds the bridge's credential. */
    fun dirFor(name: String): File = File(rootDir, sanitize(name)).apply {
        mkdirs()
        runCatching { Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwx------")) }
    }

    /** True if [name] is managed — used to decide whether a revoke must also stop a process. */
    fun isManaged(name: String): Boolean = entries.containsKey(name)

    /**
     * Register [name] as managed and drop its [credential] into the runner's dir (0600). Called at mint
     * time, the one moment the plaintext ticket exists. Does NOT start the process — the caller starts it
     * once the credential has landed.
     */
    suspend fun attach(name: String, spec: BridgeRunnerSpec, credential: BridgeCredential) =
        withLifecycle(name) {
            val dir = dirFor(name)
            val credFile = File(dir, "bridge-credential.json")
            writeOwnerOnly(credFile, PocketJson.encodeToString(credential))
            try {
                commitEntry(name, RunnerEntry(name, spec))
            } catch (t: Throwable) {
                // The credential has no managed owner if its runner entry did not become durable.
                runCatching { credFile.delete() }
                throw t
            }
        }

    /** Register an IN-PROCESS (built-in) bridge: no credential, no process — [bridgeSpec] is the whole
     *  authority definition the engine's guard enforces. */
    suspend fun attachInProcess(name: String, spec: BridgeRunnerSpec, bridgeSpec: BridgeSpec) =
        withLifecycle(name) {
            dirFor(name) // the routes table lives here
            commitEntry(name, RunnerEntry(name, spec, bridgeSpec))
        }

    /** True if [name] is a managed IN-PROCESS bridge (its authority lives here, not in the registry). */
    fun isInProcess(name: String): Boolean = entries[name]?.bridgeSpec != null

    /** The in-process bridges' specs — BridgeService.list() merges these with the registry's rows. */
    fun inProcessSpecs(): List<BridgeSpec> = entries.values.mapNotNull { it.bridgeSpec }

    /** Register a BUILT-IN engine factory for an IM [kind] (e.g. RUNNER_KIND_FEISHU). The daemon's run
     *  path calls this once at startup; a kind with no registered factory has no built-in adapter. */
    fun registerEngine(kind: String, factory: InProcessEngineFactory) { engineFactories[kind] = factory }

    /** True if a built-in engine is registered for [kind] — BridgeService gates create() on this rather
     *  than hard-coding one specific IM. */
    fun hasBuiltIn(kind: String): Boolean = engineFactories.containsKey(kind)

    /** Live convo count for an in-process bridge — its "online/active" pulse on the management pages. */
    suspend fun inProcessActive(name: String, liveCount: suspend (Collection<String>) -> Int): Int =
        engines[name]?.let { liveCount(it.ownedConvoIds()) } ?: 0

    /**
     * Update a managed runner's spec (set the admin id, rotate a secret, move the checkout).
     *
     * [mergeEnv] overlays [spec]'s NON-BLANK env values onto the stored env — the edit path, where the
     * owner types only what changes and the app secret (never echoed back) must survive untouched.
     * Deleting a key needs a full replace (mergeEnv=false); the edit form has no delete affordance yet.
     */
    suspend fun reconfigure(
        name: String,
        spec: BridgeRunnerSpec,
        mergeEnv: Boolean = false,
        newWorkdirs: List<String>? = null,
        newAllowedCommands: List<String>? = null,
        restartIfRunning: Boolean = false,
    ): String? = withLifecycle(name) {
        val cur = entries[name] ?: return@withLifecycle "\"$name\" has no managed adapter — bridges created without one " +
            "can't gain it later (the daemon no longer holds their credential); revoke and re-create it instead"
        val wasRunning = if (cur.bridgeSpec != null) engines[name]?.running == true else procs[name]?.isAlive == true
        val merged = if (!mergeEnv) spec else spec.copy(
            env = cur.spec.env + spec.env.filterValues { it.isNotBlank() },
            // blank scriptPath on a merge means "keep" too — an in-process bridge must not silently
            // become "script not found", nor an external one lose its entrypoint
            scriptPath = spec.scriptPath.ifBlank { cur.spec.scriptPath },
            interpreter = spec.interpreter ?: cur.spec.interpreter,
        )
        // an owner may edit the PROJECT allow-list (its authority). Only an in-process bridge keeps its spec
        // here; [newWorkdirs] is already validated + canonicalized by BridgeService. Replace just the
        // workdirs, preserving tier / rate limits. The engine is rebuilt below, so it re-guards on the new set.
        // the Bash command allow-list (issue #91) is likewise the bridge's authority — same in-process-only,
        // already-normalized, null=unchanged rules as [newWorkdirs]. Applied on top of the workdirs edit.
        val newSpec = cur.bridgeSpec?.let { base ->
            var s = base
            if (newWorkdirs != null) s = s.copy(workdirs = newWorkdirs)
            if (newAllowedCommands != null) s = s.copy(allowedCommands = newAllowedCommands)
            s
        }
        // An in-process engine binds its env at construction, and its handlers/conversations can still carry
        // authority from that old config. Its single teardown contract first quiesces ingress, drains every
        // admitted handler, THEN closes origin conversations. Only a successful teardown permits removal and
        // replacement; stop/restart continuity remains correct for an unchanged config.
        val oldEngine = cur.bridgeSpec?.let { engines[name] }
        if (oldEngine != null) {
            revokeEngine(name, oldEngine)?.let { return@withLifecycle it }
        }
        try {
            // Persist the complete candidate map first. Only after the atomic replacement succeeds does
            // in-memory truth advance, and only then may BridgeService restart/build from this config.
            commitEntry(name, cur.copy(spec = merged, bridgeSpec = newSpec))
        } catch (e: Exception) {
            val details = e.message ?: e::class.simpleName ?: "unknown store failure"
            log.warn("reconfigure of bridge \"$name\" was not persisted ($details)")
            return@withLifecycle "couldn't persist the runner configuration: $details"
        }
        if (restartIfRunning && wasRunning) {
            // External processes keep serving the old env until durability succeeds; only now stop them.
            // In-process engines were already fully revoked above. startUnlocked reads the new entry.
            if (cur.bridgeSpec == null) stopUnlocked(name)
            return@withLifecycle startUnlocked(name)
        }
        null
    }

    /** Teardown failures remain visible to the owner and, critically, keep the old entry/engine mapped so
     *  no replacement can be built after an incomplete revoke. Coroutine cancellation is control flow and
     *  must not be converted into a runner-status string. */
    private suspend fun revokeEngine(name: String, engine: InProcessBridgeEngine): String? = try {
        engine.revokeAndShutdown()
        engines.remove(name, engine)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val details = e.message ?: e::class.simpleName ?: "unknown teardown failure"
        log.warn("in-process bridge \"$name\" revoke failed ($details)")
        "couldn't safely revoke the built-in adapter: $details"
    }

    suspend fun detach(name: String): String? = withLifecycle(name) {
        if (!entries.containsKey(name)) return@withLifecycle "\"$name\" has no managed adapter"
        val engine = engines[name]
        if (engine != null) {
            revokeEngine(name, engine)?.let { return@withLifecycle it }
        } else {
            stopUnlocked(name) // external process, or an in-process entry that was never started
        }
        try {
            commitRemoval(name)
        } catch (e: Exception) {
            val details = e.message ?: e::class.simpleName ?: "unknown store failure"
            log.warn("detach of bridge \"$name\" was not persisted ($details)")
            return@withLifecycle "couldn't persist the runner removal: $details"
        }
        // the credential dies with the runner: leaving a spent ticket + device key on disk for a bridge
        // nobody manages is a credential with no owner
        runCatching { dirFor(name).deleteRecursively() }
        null
    }

    suspend fun start(name: String): String? = withLifecycle(name) { startUnlocked(name) }

    private fun startUnlocked(name: String): String? {
        val entry = entries[name] ?: return "\"$name\" has no managed adapter"
        entry.bridgeSpec?.let { bs -> return startInProcess(name, entry, bs) }
        if (procs[name]?.isAlive == true) return null // already up; start is idempotent
        val spec = entry.spec
        val script = File(spec.scriptPath)
        if (!script.isFile) return "adapter script not found: ${spec.scriptPath}"
        val interpreter = spec.interpreter?.takeIf { it.isNotBlank() } ?: resolvePython()
            ?: return "no python3 found — install it or set the interpreter explicitly"
        if (!File(interpreter).canExecute()) return "interpreter is not executable: $interpreter"

        val dir = dirFor(name)
        val pb = ProcessBuilder(interpreter, script.absolutePath)
            .directory(dir)
            .redirectErrorStream(true)
        pb.environment().apply {
            putAll(spec.env)
            // point the adapter at the credential the daemon dropped here; its own artifacts land in cwd
            put("POCKET_CREDENTIAL", File(dir, "bridge-credential.json").absolutePath)
            put("POCKET_ROUTES", File(dir, ".pocket-routes.json").absolutePath)
            // unbuffered, or the log tail stays empty until the process dies and the owner sees nothing
            put("PYTHONUNBUFFERED", "1")
        }
        return runCatching {
            val p = pb.start()
            procs[name] = p
            startedAt[name] = System.currentTimeMillis()
            lastError.remove(name)
            exitCodes.remove(name)   // this run's outcome isn't known yet; don't show the last one's
            logs.remove(name)        // ditto the tail — stale output next to a fresh pid reads as current
            pump(name, p)
            log.info("bridge runner \"$name\" started (pid ${p.pid()})")
            null
        }.getOrElse { "couldn't start the adapter: ${it.message}" }
    }

    private fun startInProcess(name: String, entry: RunnerEntry, bridgeSpec: BridgeSpec): String? {
        engines[name]?.let { if (it.running) return null } // idempotent
        val factory = engineFactories[entry.spec.kind]
            ?: return "this daemon build has no built-in \"${entry.spec.kind}\" adapter" // unknown kind / bare test
        val engine = engines.getOrPut(name) {
            factory.create(name, bridgeSpec, entry.spec.env, dirFor(name)) { line ->
                val ring = logs.getOrPut(name) { ArrayDeque() }
                synchronized(ring) {
                    ring.addLast(line.take(MAX_LOG_LINE_CHARS))
                    while (ring.size > MAX_LOG_LINES) ring.removeFirst()
                }
            }
        }
        val err = engine.start()
        if (err == null) {
            startedAt[name] = System.currentTimeMillis()
            lastError.remove(name)
            log.info("in-process bridge \"$name\" started")
        } else {
            lastError[name] = err
        }
        return err
    }

    suspend fun stop(name: String): String? = withLifecycle(name) { stopUnlocked(name) }

    private fun stopUnlocked(name: String): String? {
        engines[name]?.let { it.stop(); startedAt.remove(name); return null }
        val p = procs.remove(name) ?: return null
        startedAt.remove(name)
        runCatching {
            p.destroy()
            // give it a moment to close its relay link cleanly, then insist
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        log.info("bridge runner \"$name\" stopped")
        return null
    }

    suspend fun restart(name: String): String? = withLifecycle(name) {
        stopUnlocked(name)
        startUnlocked(name)
    }

    /** Start every managed runner marked autostart — called once the daemon's relay link is up. */
    suspend fun startAutostarted() {
        entries.values.filter { it.spec.autostart }.forEach { e ->
            start(e.name)?.let { err ->
                lastError[e.name] = err
                log.warn("autostart of \"${e.name}\" failed: $err")
            }
        }
    }

    suspend fun stopAll() {
        val names = (entries.keys + engines.keys + procs.keys).distinct()
        for (name in names) withLifecycle(name) {
            val engine = engines[name]
            if (engine != null) {
                engine.revokeAndShutdown()
                engines.remove(name, engine)
                startedAt.remove(name)
            } else {
                stopUnlocked(name)
            }
        }
    }

    private suspend fun <T> withLifecycle(name: String, action: suspend () -> T): T {
        val mutex = lifecycleLocks.computeIfAbsent(name) { Mutex() }
        mutex.lock()
        try {
            return action()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * The state for [name], or null if it isn't managed. Never carries env VALUES — only key names.
     *
     * [logLines] bounds the tail. The listing passes a SMALL number because it embeds one state per
     * bridge: the per-line cap alone still lets N bridges × 200 lines add up, and the ceiling that matters
     * is the relay's frame cap, which is per-FRAME, not per-runner. The full tail rides the single-runner
     * [dev.ccpocket.protocol.BridgeRunnerStatus] instead, where there is exactly one of them.
     */
    fun state(name: String, logLines: Int = MAX_LOG_LINES): BridgeRunnerState? {
        val e = entries[name] ?: return null
        val engine = if (e.bridgeSpec != null) engines[name] else null
        val p = procs[name]
        val alive = if (e.bridgeSpec != null) engine?.running == true else p?.isAlive == true
        return BridgeRunnerState(
            kind = e.spec.kind,
            // "built-in" instead of a path: the pages show this verbatim, and for an in-process bridge a
            // filesystem path would be a lie — there is no script
            scriptPath = if (e.bridgeSpec != null) "built-in" else e.spec.scriptPath,
            interpreter = e.spec.interpreter,
            autostart = e.spec.autostart,
            running = alive,
            pid = if (e.bridgeSpec == null && alive) p?.pid()?.toInt() else null,
            startedAt = startedAt[name],
            exitCode = if (alive || e.bridgeSpec != null) null else exitCodes[name],
            lastError = lastError[name] ?: engine?.lastError,
            envKeys = e.spec.env.keys.sorted(),
            logTail = logs[name]?.let { ring -> synchronized(ring) { ring.toList() } }?.takeLast(logLines).orEmpty(),
            ownerBypass = e.spec.env["FEISHU_OWNER_BYPASS"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on"),
            noApproval = e.spec.env["FEISHU_NO_APPROVAL"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on"),
        )
    }

    /** Drain the process's merged stdout/stderr into a bounded ring — the owner's only window into a
     *  misconfigured adapter without opening a terminal. */
    private fun pump(name: String, p: Process) {
        thread(isDaemon = true, name = "runner-$name") {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    val ring = logs.getOrPut(name) { ArrayDeque() }
                    synchronized(ring) {
                        // truncate at INGEST: an adapter that prints one multi-megabyte line (a dumped
                        // payload, a huge traceback) would otherwise sit in memory and, worse, ride
                        // BridgeListing past the relay's 4MiB frame cap — which doesn't fail the fetch, it
                        // kills the connection, and would do so again on every reopen.
                        ring.addLast(line.take(MAX_LOG_LINE_CHARS))
                        while (ring.size > MAX_LOG_LINES) ring.removeFirst()
                    }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (procs[name] === p) { // not a stop() we asked for
                procs.remove(name)
                startedAt.remove(name)
                exitCodes[name] = code
                if (code != 0) {
                    lastError[name] = "adapter exited with code $code"
                    log.warn("bridge runner \"$name\" exited with $code")
                }
            }
        }
    }

    /** Commit one entry without exposing a memory-only candidate. A failed write leaves [entries] exactly
     *  as it was, so the live daemon and a freshly restarted daemon agree on the old durable truth. */
    private fun commitEntry(name: String, entry: RunnerEntry) = synchronized(persistLock) {
        val candidate = entries.toMap() + (name to entry)
        persist(candidate)
        entries[name] = entry
    }

    /** Durable removal with the same disk-first ordering as [commitEntry]. */
    private fun commitRemoval(name: String) = synchronized(persistLock) {
        val candidate = entries.toMap() - name
        persist(candidate)
        entries.remove(name)
    }

    /** Serialize and atomically replace the owner-only runner store. Failures are intentionally thrown:
     *  logging-and-continuing would report a successful authority edit that vanishes on daemon restart. */
    private fun persist(candidate: Map<String, RunnerEntry>) {
        writeOwnerOnlyAtomically(store, PocketJson.encodeToString(candidate))
    }

    /** Write+fsync a 0600 sibling temp file, then atomically rename it over [f]. The old complete file
     *  survives every failure before the rename; readers never see a truncated JSON document. */
    private fun writeOwnerOnlyAtomically(f: File, text: String) {
        val target = f.absoluteFile.toPath()
        val parent = target.parent ?: error("runner store has no parent: $f")
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, ".${f.name}.", ".tmp")
        try {
            runCatching {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            }
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Create at 0600 BEFORE the secret lands — writing first would expose it at the umask default. */
    private fun writeOwnerOnly(f: File, text: String) {
        f.parentFile?.mkdirs()
        if (!f.exists()) f.createNewFile()
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
        f.writeText(text)
    }

    private fun resolvePython(): String? =
        listOf("/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3")
            .firstOrNull { File(it).canExecute() }
            ?: System.getenv("PATH")?.split(File.pathSeparator)
                ?.map { File(it, "python3") }?.firstOrNull { it.canExecute() }?.absolutePath

    /** A bridge name reaches the filesystem here — keep it to a leaf, never a traversal. */
    private fun sanitize(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
            .ifEmpty { "bridge" }

    companion object {
        private const val MAX_LOG_LINES = 200
        /** Per-line cap at ingest — see [pump]. */
        private const val MAX_LOG_LINE_CHARS = 2_000

        /** How many tail lines a LISTING carries per bridge. Small on purpose: the listing embeds one
         *  runner state per bridge, so this is the term that gets multiplied. */
        const val LIST_LOG_LINES = 12
    }
}
