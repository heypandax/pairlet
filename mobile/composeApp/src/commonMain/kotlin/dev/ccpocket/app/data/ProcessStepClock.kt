package dev.ccpocket.app.data

import dev.ccpocket.app.epochMillis

/**
 * When THIS device first saw each process step — the live line's clock (Tool Process Live v1). The wire carries
 * no start time, so the clock counts from first sight, as the sub-agent card's does. A tool call is registered
 * by the transcript the moment its START arrives, whether or not its live line is on screen; a thinking block
 * the first time a line shows it. Kept outside composition so list recycling can't reset a clock. UI thread only.
 */
object ProcessStepClock {
    private val firstSeen = HashMap<String, Long>()

    /** [key]'s first sighting, recording it now if this is the first. */
    fun startOf(key: String): Long {
        if (firstSeen.size > 4096 && key !in firstSeen) firstSeen.clear() // a very long session: start over
        return firstSeen.getOrPut(key) { epochMillis() }
    }

    /** [key]'s first sighting if one was recorded — for tests. */
    internal fun seen(key: String): Long? = firstSeen[key]
}

/** The clock key of a tool call, by its own id — the same call whatever row identity a list gives it. */
fun stepClockKeyOf(toolUseId: String): String = "t:$toolUseId"
