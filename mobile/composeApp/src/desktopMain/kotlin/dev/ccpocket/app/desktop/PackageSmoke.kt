package dev.ccpocket.app.desktop

import dev.ccpocket.app.pairing.encode
import dev.ccpocket.protocol.CollaboratorInvite
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal const val PACKAGE_SMOKE_OK = "CCP_PACKAGE_SMOKE_OK"

/**
 * Offline packaged-image contract for the two class-initializer failures reported on Windows v1.9.0.
 * This deliberately performs no update request and mints no real relay ticket.
 */
internal fun runPackageSmoke(successMarker: String? = null) {
    check(ModuleLayer.boot().findModule("java.net.http").isPresent) {
        "packaged runtime is missing java.net.http"
    }
    // Initializing the Kotlin object constructs its JDK HttpClient. Class loading alone is the exact
    // boundary that used to throw `Could not initialize class ...ReleaseClient` before any network I/O.
    Class.forName(
        "dev.ccpocket.protocol.update.ReleaseClient",
        true,
        Thread.currentThread().contextClassLoader,
    )

    val invite = CollaboratorInvite(
        relay = "wss://package-smoke.invalid",
        accountId = "smoke-account",
        daemonPub = "smoke-public-key",
        ticket = "smoke-ticket",
        ownerLabel = "Package smoke",
    )
    val matrix = qrMatrixOrFailure { invite.encode() }.getOrThrow()
    check(matrix.size > 20 && (0 until matrix.size).any { x ->
        (0 until matrix.size).any { y -> matrix[x, y] }
    }) { "collaborator invite produced an empty QR matrix" }

    // Windows jpackage uses a GUI-subsystem launcher: it may detach from the calling shell and does not
    // guarantee stdout. The marker lets CI poll for proof that the packaged JVM reached the final line.
    successMarker?.let { Files.writeString(Path.of(it), "$PACKAGE_SMOKE_OK\n", StandardCharsets.UTF_8) }
    println(PACKAGE_SMOKE_OK)
}
