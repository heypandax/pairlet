package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.e2e.E2ECrypto

/**
 * REAL daemon identity keys for tests.
 *
 * Generated, not hand-written, because the invite decoder now parses what it is given
 * ([validDaemonPub]): a fixture like `"AAAA…"` decodes to 32 bytes, which is not a short P-256 key but
 * a different object entirely, and a suite built on those would prove the establishment path works on
 * inputs production must refuse. Generating once per JVM keeps every test cheap.
 */
internal object TestKeys {
    /** A peer daemon's static public key, base64url — the shape a real `CollaboratorInvite` carries. */
    val DAEMON_PUB: String = b64(E2ECrypto.generateKeyPair().publicRaw)

    /** A DIFFERENT real key, for "two peers" fixtures. */
    val OTHER_DAEMON_PUB: String = b64(E2ECrypto.generateKeyPair().publicRaw)

    /** A fresh one, when a test needs its own. */
    fun freshDaemonPub(): String = b64(E2ECrypto.generateKeyPair().publicRaw)
}
