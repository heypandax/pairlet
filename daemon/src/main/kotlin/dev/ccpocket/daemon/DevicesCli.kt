package dev.ccpocket.daemon

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.YesNoPrompt
import dev.ccpocket.daemon.control.LocalControlClient
import dev.ccpocket.daemon.control.LocalOwnerDevice
import dev.ccpocket.daemon.control.LocalOwnerDeviceRevokeReq
import dev.ccpocket.daemon.control.LocalOwnerDeviceRevokeRes
import dev.ccpocket.daemon.control.LocalOwnerDevicesRes
import dev.ccpocket.protocol.PocketJson
import kotlinx.coroutines.runBlocking

/**
 * `pairlet devices` (pairing security phase 0) — the owner's list of the FULL-POWER devices paired to this
 * computer, each with its fingerprint, and the way to revoke one. Pairing currently trusts the relay, so this
 * is how an owner checks that every row is a device they actually own (compare with the App's "This device's
 * fingerprint") and removes one that isn't.
 *
 * Like `pairlet agent`, it only ever talks to the ALREADY-RUNNING daemon over the token-authenticated local
 * control API — it never reads or writes devices.json itself, and never starts a daemon.
 */
private class DevicesCmd : LocalCmd("devices", "list the phones and apps with full access to this computer, with fingerprints; revoke one") {
    override val invokeWithoutSubcommand = true

    override fun run() {
        if (currentContext.invokedSubcommand == null) runBlocking { listDevices(devicesClient(pairPort, json), json) }
    }
}

private class DevicesListCmd : LocalCmd("list", "list the paired full-access devices and their fingerprints") {
    override fun run() = runBlocking { listDevices(devicesClient(pairPort, json), json) }
}

private class DevicesRevokeCmd : LocalCmd("revoke", "revoke one paired device: it is disconnected at once and must pair again") {
    private val target by argument("device", help = "device id, or a unique prefix of its id or of its fingerprint")
    private val yes by option("--yes", "-y", help = "don't ask for confirmation").flag()

    override fun run() = runBlocking {
        val client = devicesClient(pairPort, json)
        val list = client.get("/devices", LocalOwnerDevicesRes.serializer())
        val device = when (val m = DeviceMatch.resolve(target, list.items)) {
            is DeviceMatch.One -> m.device
            DeviceMatch.None -> throw CliktError("no paired device matches \"$target\" — see: pairlet devices")
            is DeviceMatch.Ambiguous -> throw CliktError(
                "\"$target\" matches ${m.candidates.size} devices — give more of the id or fingerprint:\n" +
                    m.candidates.joinToString("\n") { "  ${shortId(it.deviceId)}  ${it.fingerprint}" },
            )
        }
        if (!yes) {
            echo("  device:       ${shortId(device.deviceId)}  (${device.deviceId})", err = true)
            echo("  fingerprint:  ${device.fingerprint}", err = true)
            val ok = YesNoPrompt(
                "Revoke it? It is disconnected now and has to pair again to come back",
                terminal, default = false,
            ).ask()
            if (ok != true) {
                echo("not revoked${if (ok == null) " (no answer — pass --yes to skip the question)" else ""}", err = true)
                throw ProgramResult(1)
            }
        }
        val res = client.post(
            "/devices/revoke", LocalOwnerDeviceRevokeReq.serializer(), LocalOwnerDeviceRevokeReq(device.deviceId),
            LocalOwnerDeviceRevokeRes.serializer(),
        )
        if (json) {
            echo(PocketJson.encodeToString(LocalOwnerDeviceRevokeRes.serializer(), res))
        } else {
            echo("✓ revoked ${shortId(res.deviceId)} (fingerprint ${res.fingerprint})")
            echo("  It is disconnected and removed here; the relay is told to drop its credential too.")
        }
    }
}

/** Resolving what the owner typed against the list just fetched — exact id, else a unique prefix. */
internal sealed interface DeviceMatch {
    class One(val device: LocalOwnerDevice) : DeviceMatch
    data object None : DeviceMatch
    class Ambiguous(val candidates: List<LocalOwnerDevice>) : DeviceMatch

    companion object {
        fun resolve(input: String, devices: List<LocalOwnerDevice>): DeviceMatch {
            val raw = input.trim().removeSuffix("…")
            if (raw.isEmpty()) return None
            devices.firstOrNull { it.deviceId == raw }?.let { return One(it) }
            // a fingerprint is compared without its dashes/spaces and case, so "AB12 cd34" finds "ab12-cd34-…"
            val fp = raw.lowercase().filterNot { it == '-' || it.isWhitespace() }
            val hits = devices.filter { d ->
                d.deviceId.startsWith(raw) ||
                    (fp.isNotEmpty() && fp.all { it in "0123456789abcdef" } && d.fingerprint.replace("-", "").startsWith(fp))
            }
            return when (hits.size) {
                0 -> None
                1 -> One(hits.single())
                else -> Ambiguous(hits)
            }
        }
    }
}

private fun devicesClient(pairPort: Int, json: Boolean) = LocalControlClient(
    pairPort, daemonStartHintText(), jsonErrors = json,
    routeMissingHint = "the running daemon is older than this CLI and has no `devices` support — restart it so it runs the same version",
)

private fun shortId(deviceId: String) = "${deviceId.take(8)}…"

private suspend fun LocalCmd.listDevices(client: LocalControlClient, json: Boolean) {
    val res = client.get("/devices", LocalOwnerDevicesRes.serializer())
    if (json) {
        echo(PocketJson.encodeToString(LocalOwnerDevicesRes.serializer(), res))
        return
    }
    echo("")
    echo("  This computer's fingerprint:  ${res.computerFingerprint}")
    echo("")
    if (res.items.isEmpty()) {
        echo("  No devices paired with full access. Pair one with: pairlet pair")
        echo("")
        echoRevocationsPending(res.revocationsPending)
        return
    }
    echo("  DEVICE      FINGERPRINT               PAIRED          LAST HANDSHAKE")
    val now = System.currentTimeMillis()
    res.items.forEach { d ->
        val paired = d.pairedAt?.let { ago(now - it) } ?: "—"
        val seen = when {
            d.lastHandshakeAt != null -> "${ago(now - d.lastHandshakeAt)} (${d.lastHandshakeVia ?: "?"})"
            d.firstContactPending -> "not yet connected"
            else -> "none since the daemon started"
        }
        echo("  ${shortId(d.deviceId).padEnd(10)}  ${d.fingerprint.padEnd(24)}  ${paired.padEnd(14)}  $seen")
    }
    echo("")
    echo("  Each of your phones / desktop Apps shows its own value under \"This device's fingerprint\"")
    echo("  (Settings → Support & about → About; desktop App: Settings → About), and this computer's under")
    echo("  \"Computer fingerprint\". Compare all five groups. Revoke any row that isn't one of yours:")
    echo("      pairlet devices revoke <device id or fingerprint prefix>")
    echo("  (Bridges are listed by `pairlet bridges`; remote-execution links by `pairlet agent grant list`.)")
    echo("")
    echoRevocationsPending(res.revocationsPending)
}

/** Revoked devices are no longer listed (they can't connect), but until the relay confirms the revoke it isn't
 *  completely done — say so, in one line, at the end. */
private fun LocalCmd.echoRevocationsPending(n: Int) {
    if (n <= 0) return
    val (what, they) = if (n == 1) "1 revoked device is" to "it" else "$n revoked devices are" to "they"
    echo("  $what still waiting for the relay to confirm the revoke — $they can no longer connect here,")
    echo("  and the daemon asks the relay again each time it connects.")
    echo("")
}

private fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}

/** Assembled here so the whole command lives in one file (like [agentCommand]). */
internal fun devicesCommand(): com.github.ajalt.clikt.core.CliktCommand = DevicesCmd().subcommands(DevicesListCmd(), DevicesRevokeCmd())
