package dev.ccpocket.daemon.disk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.bufferedWriter
import kotlin.io.path.fileSize
import kotlin.random.Random

/**
 * A synthetic Claude Code transcript shaped like the long session of the 2026-10-05 open-latency analysis:
 * most bytes are things the replay never shows (thinking, the root `toolUseResult` copies, queue-operation
 * and attachment records), a few tool results return a screenshot — its line near 1 MB because the CLI
 * stores the picture TWICE (`message.content[].tool_result` and `toolUseResult.file`) — and the odd user
 * turn is a large paste. Visible rows are sized like that session's (prompts ~3.6 KB, replies ~2 KB, tool
 * inputs past the 1000-char preview cap). Deterministic per seed; never reads a real transcript.
 */
internal object LargeTranscriptFixture {

    /** A 1440x900 screenshot-like PNG: UI panels with dense "glyph" runs plus a photo-like panel, so it
     *  weighs (~0.5 MB of base64) and thumbnails like a real screenshot rather than a flat fill. */
    fun screenshotPng(seed: Int, w: Int = 1440, h: Int = 900): ByteArray {
        val rnd = Random(seed)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = Color(0x1E, 0x1F, 0x24); g.fillRect(0, 0, w, h)
            g.color = Color(0x2A, 0x2C, 0x33); g.fillRect(0, 0, 260, h)
            g.color = Color(0x26, 0x4F, 0x9E); g.fillRect(0, 0, w, 44)
            var y = 60
            while (y < h - 16) {
                var x = if (rnd.nextInt(5) == 0) 20 else 280
                val end = w - rnd.nextInt(40, 400)
                while (x < end) {
                    val word = rnd.nextInt(3, 11) * 7
                    g.color = Color(0x90 + rnd.nextInt(0x60), 0x90 + rnd.nextInt(0x60), 0x90 + rnd.nextInt(0x60))
                    for (c in 0 until word step 7) g.fillRect(x + c, y + rnd.nextInt(0, 3), 5, 9 + rnd.nextInt(0, 4))
                    x += word + 7
                }
                y += 18 + rnd.nextInt(0, 8)
            }
        } finally {
            g.dispose()
        }
        // a photo-like panel (an embedded preview / chart): a gradient with fine grain
        val px = w - 480; val py = 80
        for (yy in 0 until 180) for (xx in 0 until 440) {
            val grain = rnd.nextInt(-18, 18)
            val r = (xx * 255 / 440 + grain).coerceIn(0, 255)
            val gg = (yy * 255 / 180 + grain).coerceIn(0, 255)
            val b = ((xx + yy) % 256 + grain).coerceIn(0, 255)
            img.setRGB(px + xx, py + yy, (r shl 16) or (gg shl 8) or b)
        }
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    private val words = listOf(
        "the", "daemon", "replay", "window", "frame", "relay", "phone", "session", "budget", "cursor", "page",
        "thumbnail", "transcript", "row", "tool", "result", "image", "encode", "budget", "seam", "test", "fix",
        "函数", "日志", "会话", "历史", "分页", "预算", "首屏", "缩略图", "客户端", "中转",
    )

    private fun prose(rnd: Random, chars: Int): String {
        val sb = StringBuilder(chars + 32)
        while (sb.length < chars) {
            sb.append(words[rnd.nextInt(words.size)])
            sb.append(if (rnd.nextInt(14) == 0) "\n" else " ")
        }
        return sb.toString()
    }

    /** Write a transcript of about [targetBytes] to [dir] with [images] screenshots, the last one in the tail. */
    fun write(dir: Path, targetBytes: Long, seed: Int, images: Int): Path {
        val rnd = Random(seed)
        val file = dir.resolve("fixture-$seed.jsonl")
        val pngs = (0 until 3).map { Base64.getEncoder().encodeToString(screenshotPng(seed * 31 + it)) }
        var parent: String? = null
        var n = 0L
        var tool = 0
        var written = 0L
        // the body spreads `images - 1` screenshots evenly; the tail adds the last one with ~35 visible rows after
        // it — inside the old 100-row first window, as in the analysed session (its screenshot sat ~39 rows up)
        val tailBytes = 2L * pngs.maxOf { it.length } + 400_000
        val bodyBytes = targetBytes - tailBytes
        val shotsAt = ArrayDeque((1 until images).map { bodyBytes * it / images })
        var visibleSinceTailShot = -1
        file.bufferedWriter().use { out ->
            fun emit(rec: JsonObject) {
                val uuid = "u-$seed-${n++}"
                val line = buildJsonObject {
                    put("uuid", uuid)
                    parent?.let { put("parentUuid", it) }
                    put("sessionId", "fixture-$seed")
                    put("timestamp", "2026-10-05T00:00:00.000Z")
                    rec.forEach { (k, v) -> put(k, v) }
                }.toString()
                out.write(line); out.write("\n")
                written += line.length + 1 // chars, not bytes: close enough to size the loop
                parent = uuid
            }
            fun visible() { if (visibleSinceTailShot >= 0) visibleSinceTailShot++ }
            fun assistant(block: JsonObject) = emit(buildJsonObject {
                put("type", "assistant")
                putJsonObject("message") { put("role", "assistant"); put("model", "fixture"); putJsonArray("content") { add(block) } }
            })
            fun noise() {
                when (rnd.nextInt(3)) {
                    0 -> emit(buildJsonObject { put("type", "queue-operation"); put("operation", "enqueue"); put("content", prose(rnd, rnd.nextInt(1_500, 4_000))) })
                    1 -> emit(buildJsonObject { put("type", "attachment"); putJsonObject("attachment") { put("type", "file"); put("content", prose(rnd, rnd.nextInt(1_000, 5_000))) } })
                    else -> emit(buildJsonObject { put("type", "last-prompt"); put("lastPrompt", prose(rnd, 120)) })
                }
            }
            fun shot(id: String) {
                val data = pngs[tool % pngs.size]
                assistant(buildJsonObject {
                    put("type", "tool_use"); put("id", id); put("name", "Read")
                    putJsonObject("input") { put("file_path", "/work/shots/shot-$tool.png") }
                })
                emit(buildJsonObject {
                    put("type", "user")
                    putJsonObject("message") {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "tool_result"); put("tool_use_id", id)
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") { put("type", "base64"); put("media_type", "image/png"); put("data", data) }
                                    })
                                })
                            })
                        }
                    }
                    // the CLI's second copy of the same picture — the replay must not send it
                    putJsonObject("toolUseResult") {
                        put("type", "image")
                        putJsonObject("file") { put("base64", data); put("type", "image/png") }
                    }
                })
            }
            fun step(id: String) {
                val output = prose(rnd, if (rnd.nextInt(8) == 0) rnd.nextInt(8_000, 25_000) else rnd.nextInt(200, 3_000))
                assistant(buildJsonObject {
                    put("type", "tool_use"); put("id", id); put("name", "Bash")
                    putJsonObject("input") { put("command", prose(rnd, rnd.nextInt(200, 2_500))); put("description", "step") }
                })
                emit(buildJsonObject {
                    put("type", "user")
                    putJsonObject("message") {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "tool_result"); put("tool_use_id", id); put("content", output) })
                        }
                    }
                    putJsonObject("toolUseResult") { put("stdout", output); put("stderr", ""); put("interrupted", false) }
                })
            }
            // one turn: a prompt (the odd one a big paste), 1-4 tool steps behind thinking, 1-2 replies
            fun turn(tailShot: Boolean) {
                val paste = rnd.nextInt(30) == 0
                emit(buildJsonObject {
                    put("type", "user")
                    putJsonObject("message") { put("role", "user"); put("content", prose(rnd, if (paste) rnd.nextInt(20_000, 60_000) else rnd.nextInt(200, 7_000))) }
                })
                visible()
                repeat(rnd.nextInt(1, 3)) { noise() }
                repeat(rnd.nextInt(1, 5)) { i ->
                    assistant(buildJsonObject { put("type", "thinking"); put("thinking", prose(rnd, rnd.nextInt(800, 6_000))); put("signature", "s") })
                    val id = "toolu_${seed}_${tool++}"
                    if (tailShot && i == 0) {
                        shot(id); visibleSinceTailShot = 0
                    } else if (shotsAt.isNotEmpty() && written >= shotsAt.first()) {
                        shotsAt.removeFirst(); shot(id)
                    } else {
                        step(id); visible()
                    }
                    if (rnd.nextInt(3) == 0) noise()
                }
                repeat(rnd.nextInt(1, 3)) {
                    assistant(buildJsonObject { put("type", "text"); put("text", prose(rnd, rnd.nextInt(300, 3_800))) })
                    visible()
                }
                noise()
            }
            while (written < bodyBytes) turn(tailShot = false)
            turn(tailShot = true)
            while (visibleSinceTailShot < 35) turn(tailShot = false)
        }
        check(file.fileSize() >= targetBytes * 95 / 100) { "fixture came out at ${file.fileSize()} B" }
        return file
    }

    fun tempDir(): Path = Files.createTempDirectory("ccp-first-window")
}
