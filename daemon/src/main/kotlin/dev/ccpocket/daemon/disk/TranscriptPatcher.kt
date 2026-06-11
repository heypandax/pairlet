package dev.ccpocket.daemon.disk

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * claude tags headless (`-p`) transcripts with `"entrypoint":"sdk-cli"`, and since 2.1.90 the
 * desktop `claude --resume` picker hides those sessions. Rewriting the tag to the interactive
 * `"cli"` makes phone-born sessions show up in a terminal picker. Only safe once the writing
 * claude process has exited — replacing the file under a live process drops its appends.
 */
object TranscriptPatcher {
    private const val SDK_TAG = "\"entrypoint\":\"sdk-cli\""
    private const val CLI_TAG = "\"entrypoint\":\"cli\""

    // inside JSON string values quotes are escaped (\"), so the raw tag only ever matches a
    // real top-level key:value pair — prompt text mentioning sdk-cli can't false-positive
    /** Rewrite [file] in place (atomic replace, 0600 like claude's own). True if changed; never throws. */
    fun unhide(file: Path): Boolean {
        if (!Files.exists(file)) return false
        val tmp = file.resolveSibling("${file.fileName}.pocket-tmp")
        return try {
            var changed = false
            Files.newBufferedReader(file).use { r ->
                Files.newBufferedWriter(tmp).use { w ->
                    generateSequence(r::readLine).forEach { line ->
                        val out = if (line.contains(SDK_TAG)) {
                            changed = true
                            line.replace(SDK_TAG, CLI_TAG)
                        } else {
                            line
                        }
                        w.write(out)
                        w.newLine()
                    }
                }
            }
            if (changed) {
                runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            changed
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }
}
