package dev.ccpocket.app.lock

/** Desktop has no biometrics and its root (Main.kt) never mounts the gate — a stub keeps commonMain happy. */
actual fun createBiometrics(): Biometrics = object : Biometrics {
    override fun kind() = BiometryKind.NONE
    override fun canAuthenticate() = false
    override suspend fun authenticate(reason: String, allowCredential: Boolean) = AuthResult.Unavailable
}
