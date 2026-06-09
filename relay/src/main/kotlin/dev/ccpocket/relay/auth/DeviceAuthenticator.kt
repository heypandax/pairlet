package dev.ccpocket.relay.auth

import dev.ccpocket.protocol.DeviceHello
import dev.ccpocket.relay.store.RelayStore
import java.security.MessageDigest

/**
 * Authenticates a device by a bearer credential issued at pairing. The relay stores only
 * sha256(secret); the comparison is constant-time and a revoked device is rejected.
 */
class DeviceAuthenticator(
    private val store: RelayStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data class Ok(val accountId: String) : Result
        data class Err(val code: String) : Result
    }

    suspend fun verify(hello: DeviceHello): Result {
        val d = store.getDevice(hello.deviceId) ?: return Result.Err("unknown_device")
        if (d.revoked) return Result.Err("revoked")
        val secret = runCatching { Codec.b64uDec(hello.secret) }.getOrNull() ?: return Result.Err("bad_credential")
        // MessageDigest.isEqual is constant-time in JDK 17 — avoids leaking the hash via timing.
        if (!MessageDigest.isEqual(Codec.sha256(secret), d.credentialHash)) return Result.Err("bad_credential")
        store.touchDevice(hello.deviceId, clock())
        return Result.Ok(d.accountId)
    }
}
