package dev.ccpocket.relay.push

import dev.ccpocket.relay.store.RelayStore
import java.io.File

/**
 * Builds a [PushService] from environment configuration. With no APNs/FCM credentials present it
 * returns [LoggingPushService], so local and CI relays run unchanged. Secrets live on the relay host,
 * never in the repo.
 *
 *   APNs: CCPOCKET_APNS_KEY_P8 (path to .p8) · CCPOCKET_APNS_KEY_ID · CCPOCKET_APNS_TEAM_ID
 *         · CCPOCKET_APNS_TOPIC (= iOS bundle id)
 *   FCM:  CCPOCKET_FCM_CREDENTIALS (or GOOGLE_APPLICATION_CREDENTIALS) — path to service-account JSON
 *   Huawei (HarmonyOS Push Kit): CCPOCKET_HUAWEI_APP_ID · CCPOCKET_HUAWEI_CLIENT_ID
 *         · CCPOCKET_HUAWEI_CLIENT_SECRET — AGC 项目设置 → 常规 → 项目凭据
 */
object PushConfig {
    fun load(store: RelayStore, env: (String) -> String? = System::getenv): PushService {
        val senders = HashMap<String, PushSender>()

        val p8 = env("CCPOCKET_APNS_KEY_P8")
        val keyId = env("CCPOCKET_APNS_KEY_ID")
        val teamId = env("CCPOCKET_APNS_TEAM_ID")
        val topic = env("CCPOCKET_APNS_TOPIC")
        if (p8 != null && keyId != null && teamId != null && topic != null) {
            runCatching {
                val der = PushCrypto.pemToDer(File(p8).readText())
                senders["apns"] = ApnsSender(der, keyId, teamId, topic, sandbox = false)
                senders["apns_sandbox"] = ApnsSender(der, keyId, teamId, topic, sandbox = true)
                println("[push] APNs senders ready (topic=$topic)")
            }.onFailure { println("[push] APNs config error: ${it.message}") }
        }

        val fcm = env("CCPOCKET_FCM_CREDENTIALS") ?: env("GOOGLE_APPLICATION_CREDENTIALS")
        if (fcm != null) {
            runCatching {
                senders["fcm"] = FcmSender.fromServiceAccount(File(fcm).readText())
                println("[push] FCM sender ready")
            }.onFailure { println("[push] FCM config error: ${it.message}") }
        }

        val hwAppId = env("CCPOCKET_HUAWEI_APP_ID")
        val hwClientId = env("CCPOCKET_HUAWEI_CLIENT_ID")
        val hwClientSecret = env("CCPOCKET_HUAWEI_CLIENT_SECRET")
        if (hwAppId != null && hwClientId != null && hwClientSecret != null) {
            runCatching {
                senders["huawei"] = HuaweiSender(hwAppId, hwClientId, hwClientSecret)
                println("[push] Huawei sender ready (appId=$hwAppId)")
            }.onFailure { println("[push] Huawei config error: ${it.message}") }
        }

        return if (senders.isEmpty()) {
            println("[push] no APNs/FCM/Huawei credentials configured — push disabled (logging only)")
            LoggingPushService()
        } else {
            StorePushService(store, senders)
        }
    }
}
