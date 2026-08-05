package dev.ccpocket.daemon.review

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * The persistence primitive the ReviewRequest stores share (REVIEW-REQUEST.md §5.1: the daemon owns
 * the durable state, and a crash mid-write must never be the reason a colleague's request disappears).
 *
 * Three properties the existing single-`writeText` stores don't have, because this data is different:
 *  - ATOMIC: write a sibling temp file, fsync-free `ATOMIC_MOVE` over the target. A torn write can
 *    therefore never be observed — a reader sees either the old file or the new one;
 *  - 0600 BEFORE the bytes land: the temp file is created and chmod-ed empty, so a brief window where
 *    a colleague's brief/result sits at the umask default cannot exist;
 *  - CORRUPTION FAILS CLOSED WITHOUT DESTROYING EVIDENCE: an undecodable file is moved aside to
 *    `<name>.corrupt` and the store starts EMPTY. Starting empty over a file we could not read would
 *    silently overwrite whatever was actually in there on the next persist; keeping the bytes means a
 *    human can still recover the requests, and the loud log says to.
 */
internal object ReviewFiles {

    private val log = logger("ReviewFiles")

    /** `~/.cc-pocket/<name>` — beside identity.json, like every other daemon store. */
    fun path(name: String): File = File(Identity.defaultPath().parentFile, name)

    /** Decode [file] with [decode]; null when it is absent. A file that exists but cannot be decoded is
     *  preserved as `<file>.corrupt` and reported as null (the caller starts from its own empty state). */
    fun <T> read(file: File, decode: (String) -> T): T? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        if (text != null) {
            runCatching { return decode(text) }
        }
        val base = File(file.parentFile, "${file.name}.corrupt")
        val quarantine = if (!base.exists()) base else File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}")
        runCatching { Files.move(file.toPath(), quarantine.toPath()) }.getOrElse {
            log.warn("${file.name} could not be read or quarantined — refusing to open an empty writable store")
            throw IllegalStateException("could not quarantine unreadable ${file.name}", it)
        }
        log.warn("${file.name} could not be read — kept as ${quarantine.name}, starting from an empty store")
        return null
    }

    /** Replace [file] with [text] atomically, owner-readable only. Returns false if anything failed —
     *  the caller keeps its in-memory state, which is the honest outcome (nothing was half-written). */
    fun write(file: File, text: String): Boolean = runCatching {
        val parent = file.absoluteFile.parentFile
        Files.createDirectories(parent.toPath())
        // A unique sibling avoids two same-user processes deleting or overwriting each other's temp
        // file. The final replace is still atomic; the stores serialize writes within one process.
        val tmp = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp").toFile()
        // Enforce 0600 when the filesystem exposes POSIX permissions (Windows ACLs inherit the profile
        // directory) — set on the EMPTY file so
        // the secret/brief never exists at the umask default, not even for a moment
        ownerOnly(tmp)
        try {
            tmp.writeText(text)
            runCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                // some filesystems (a few network mounts) refuse ATOMIC_MOVE — still better than a torn write
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
        ownerOnly(file)
        true
    }.getOrElse {
        log.warn("could not persist ${file.name}: ${it.message}")
        false
    }

    private fun ownerOnly(file: File) {
        if (Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }
}
