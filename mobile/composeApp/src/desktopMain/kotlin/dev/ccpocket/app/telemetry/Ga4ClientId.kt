package dev.ccpocket.app.telemetry

import java.util.UUID

/** GA4's validation endpoint requires two decimal numbers separated by a dot.
 * Keep the existing random installation seed and map its full 128 bits without regenerating identity.
 * Invalid stored values fail closed; they must not become another installation on each launch.
 */
internal fun ga4ClientId(stored: String): String? {
    if (Regex("[0-9]{1,20}\\.[0-9]{1,20}").matches(stored)) return stored
    if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(stored)) return null
    val uuid = UUID.fromString(stored)
    return java.lang.Long.toUnsignedString(uuid.mostSignificantBits) + "." +
        java.lang.Long.toUnsignedString(uuid.leastSignificantBits)
}
