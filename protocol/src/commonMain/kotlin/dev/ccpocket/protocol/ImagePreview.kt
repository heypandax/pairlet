package dev.ccpocket.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lean history, part 2: pictures travel as small previews and the full version is fetched when someone
 * actually opens one (docs/design/SLOW-LINK-RESILIENCE.md §6).
 *
 * Why: a chat tile is ~92 dp tall, but the daemon's wire thumbnail is sized for the full-screen viewer
 * (1024 px, 100–160 KB of base64 apiece). On a lossy link two such pictures were 78 % of a history window
 * that then never arrived. A connection that declares [ClientCaps.supportsImagePreviews] gets each image as a
 * tile-sized preview carrying [ImageData.ref]; the viewer asks for the real one with [FetchImage].
 */
object ImageRefs {
    /** Characters of the base64url digest kept: 22 chars = 132 bits, far past any accidental collision. */
    const val LENGTH = 22

    /** Shortest/longest ref a daemon will look up — anything else is not something it ever issued. */
    private val shape = Regex("^[A-Za-z0-9_-]{$LENGTH}$")

    private val hasher by lazy { CryptographyProvider.Default.get(SHA256).hasher() }

    /**
     * The identity of one picture: the leading [LENGTH] base64url characters of the SHA-256 of its BYTES (the
     * decoded image, not its base64 text, so line wrapping or padding cannot change it). The daemon stamps it
     * on a preview; a client computes the same value over an image it already holds to tell "this preview is
     * the photo I just sent" without fetching.
     */
    fun of(bytes: ByteArray): String {
        val digest = hasher.hashBlocking(bytes)
        val out = StringBuilder(LENGTH)
        var i = 0
        // base64url, unpadded, emitted three bytes at a time — only the first LENGTH characters are needed
        while (out.length < LENGTH && i < digest.size) {
            val b0 = digest[i].toInt() and 0xff
            val b1 = if (i + 1 < digest.size) digest[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < digest.size) digest[i + 2].toInt() and 0xff else 0
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            out.append(ALPHABET[((b1 and 0x0f) shl 2) or (b2 shr 6)])
            out.append(ALPHABET[b2 and 0x3f])
            i += 3
        }
        return out.substring(0, LENGTH)
    }

    /** Is [ref] shaped like something [of] produces? A daemon refuses anything else before any lookup. */
    fun isValid(ref: String): Boolean = shape.matches(ref)

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
}

/**
 * client -> daemon: send the full version of the picture a preview stands for.
 *
 * [ref] is the preview's [ImageData.ref]. [seq] and [index] say where the client saw it — the history row's
 * transcript cursor ([HistoryMessage.seq]) and the picture's position on that row — so a daemon that no
 * longer holds the picture in memory (it restarted, or the entry aged out) can read it back off the
 * transcript. Both are optional: a picture that arrived on a live tool result has no cursor yet, and is
 * served from memory or not at all. [requestId] is echoed so a client can tell replies apart.
 *
 * Answered to the requesting connection only, with exactly one [ImageContent]. Subject to the same access
 * rules as [FetchHistoryPage]: a connection can only ask about a conversation it may read.
 */
@Serializable
@SerialName("pocket/image.fetch")
data class FetchImage(
    val convoId: String,
    val ref: String,
    val seq: Long? = null,
    val index: Int = 0,
    val requestId: String? = null,
) : ToDaemon

/**
 * daemon -> client: [FetchImage]'s single reply. [image] is the full picture ([ImageData.ref] unset — it is
 * not a preview), or null with [error] one of the fixed codes below when the daemon cannot produce it. A
 * client keeps showing the preview in that case; nothing about the conversation changed.
 */
@Serializable
@SerialName("pocket/image.content")
data class ImageContent(
    val convoId: String,
    val ref: String,
    val image: ImageData? = null,
    val error: String? = null,
    val requestId: String? = null,
) : ToPhone {
    companion object {
        /** The conversation is not open on this daemon (closed, reaped, or never this connection's). */
        const val ERROR_NO_CONVERSATION = "no_conversation"
        /** The picture is neither in memory nor recoverable from the transcript at the given cursor. */
        const val ERROR_UNAVAILABLE = "unavailable"
        /** [FetchImage.ref] is not a ref this protocol issues. */
        const val ERROR_BAD_REF = "bad_ref"
    }
}
