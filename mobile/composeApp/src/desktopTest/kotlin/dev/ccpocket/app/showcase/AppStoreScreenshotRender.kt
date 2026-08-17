package dev.ccpocket.app.showcase

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import kotlin.test.Test

/**
 * Composes App Store marketing frames around screenshots captured from the real Compose UI.
 *
 * The phone pixels come from [ShowcaseRender]; this class only adds the master canvas, copy,
 * and framing. generate-assets.sh resizes the master to the active App Store device slot. It is
 * opt-in so normal tests never write marketing assets:
 *
 *   APPSTORE_SCREENSHOT_OUT=/abs/fastlane/screenshots \
 *   APPSTORE_SITE_BUILD=/abs/marketing/site/build \
 *   APPSTORE_FLEET_BUILD=/abs/marketing/appstore/build \
 *     ./gradlew :mobile:composeApp:desktopTest \
 *       --tests dev.ccpocket.app.showcase.AppStoreScreenshotRender
 */
@OptIn(ExperimentalComposeUiApi::class)
class AppStoreScreenshotRender {
    private data class Copy(
        val badge: String,
        val title: String,
        val subtitle: String,
    )

    private data class Shot(
        val id: String,
        val frame: (String, File, File) -> File,
        val en: Copy,
        val zh: Copy,
    )

    private fun shots() = listOf(
        Shot(
            id = "agents",
            frame = { lang, _, fleet -> File(fleet, "fleet-$lang/fleet/f00001.png") },
            en = Copy(
                "6 AGENT BACKENDS",
                "Your coding agents,\nin your pocket",
                "Claude Code, Codex, DeepSeek, OpenCode, Kimi Code, and ZCode",
            ),
            zh = Copy(
                "6 个 AGENT 后端",
                "把 AI 编程 Agent\n装进口袋",
                "Claude Code、Codex、DeepSeek、OpenCode、Kimi Code 与 ZCode",
            ),
        ),
        Shot(
            id = "sessions",
            frame = { lang, site, _ -> File(site, "loop-$lang/f00020.png") },
            en = Copy(
                "ALL YOUR TASKS",
                "Every session,\none place",
                "Projects, live status, and recent work across your computers",
            ),
            zh = Copy(
                "所有任务",
                "所有会话，\n一处掌握",
                "跨电脑查看项目、运行状态和最近任务",
            ),
        ),
        Shot(
            id = "watch",
            frame = { lang, site, _ -> File(site, "loop-$lang/f00095.png") },
            en = Copy(
                "WATCH",
                "See work unfold\nin real time",
                "Follow thinking, tool calls, commands, and results as they happen",
            ),
            zh = Copy(
                "实时查看",
                "实时看它思考、\n调用工具和执行",
                "输出、命令和结果持续流式更新",
            ),
        ),
        Shot(
            id = "approve",
            frame = { lang, site, _ -> File(site, "loop-$lang/f00135.png") },
            en = Copy(
                "APPROVE",
                "Sensitive actions\nstay in your hands",
                "Allow or deny supported permission requests from anywhere",
            ),
            zh = Copy(
                "权限审批",
                "敏感操作，\n始终由你决定",
                "离开工位，也能批准或拒绝受支持的权限请求",
            ),
        ),
        Shot(
            id = "continue",
            frame = { lang, site, _ -> File(site, "loop-$lang/f00215.png") },
            en = Copy(
                "CONTINUE",
                "Pick up the same task\nwithout starting over",
                "Send the next instruction and keep the existing context moving",
            ),
            zh = Copy(
                "继续任务",
                "继续原任务，\n不必从头再来",
                "发送下一条指令，让已有上下文继续推进",
            ),
        ),
        Shot(
            id = "inspect",
            frame = { lang, site, _ -> File(site, "loop-$lang/f00305.png") },
            en = Copy(
                "INSPECT",
                "Review what\nactually changed",
                "Open changed files and read line-level diffs before you trust the result",
            ),
            zh = Copy(
                "检查改动",
                "改了什么，\n验完再相信",
                "打开 Changed files，逐行查看受支持后端提供的 diff",
            ),
        ),
    )

    @Test
    fun render() {
        val out = System.getenv("APPSTORE_SCREENSHOT_OUT")?.let(::File) ?: return
        val siteBuild = File(requireNotNull(System.getenv("APPSTORE_SITE_BUILD")))
        val fleetBuild = File(requireNotNull(System.getenv("APPSTORE_FLEET_BUILD")))

        listOf("en" to "en-US", "zh" to "zh-Hans").forEach { (lang, locale) ->
            val localeOut = File(out, locale).apply { mkdirs() }
            shots().forEachIndexed { index, shot ->
                val source = shot.frame(lang, siteBuild, fleetBuild)
                require(source.isFile) { "missing real-UI source frame: $source" }
                val bitmap = SkiaImage.makeFromEncoded(source.readBytes()).toComposeImageBitmap()
                val copy = if (lang == "zh") shot.zh else shot.en
                val scene = ImageComposeScene(1290, 2796, Density(1f)) {
                    StoreFrame(copy, bitmap)
                }
                try {
                    val png = scene.render(0).encodeToData(EncodedImageFormat.PNG)
                        ?: error("could not encode ${shot.id}")
                    File(localeOut, "%02d-%s.png".format(index + 1, shot.id))
                        .writeBytes(png.bytes)
                } finally {
                    scene.close()
                }
            }
        }
        println("appstore screenshots: ${out.absolutePath}")
    }

    @Composable
    private fun StoreFrame(copy: Copy, screenshot: androidx.compose.ui.graphics.ImageBitmap) {
        val bg = Brush.verticalGradient(
            listOf(Color(0xFF21130F), Color(0xFF0E0F11), Color(0xFF0E0F11)),
            startY = 0f,
            endY = 1350f,
        )
        Box(Modifier.fillMaxSize().background(bg).padding(horizontal = 78.dp, vertical = 72.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "CC POCKET",
                        color = Color(0xFFF3F1EE),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp,
                    )
                    Text(
                        copy.badge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0x33E47D55))
                            .border(2.dp, Color(0x99E47D55), RoundedCornerShape(999.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        color = Color(0xFFF0916D),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
                Spacer(Modifier.height(54.dp))
                Text(
                    copy.title,
                    modifier = Modifier.fillMaxWidth().height(212.dp),
                    color = Color(0xFFF7F5F2),
                    fontSize = 84.sp,
                    lineHeight = 94.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    copy.subtitle,
                    modifier = Modifier.fillMaxWidth().height(104.dp),
                    color = Color(0xFFB8BBC1),
                    fontSize = 34.sp,
                    lineHeight = 43.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(38.dp))
                Box(
                    Modifier
                        .width(942.dp)
                        .height(1996.dp)
                        .clip(RoundedCornerShape(70.dp))
                        .background(Color(0xFF08090A))
                        .border(4.dp, Color(0xFF35383D), RoundedCornerShape(70.dp))
                        .padding(18.dp),
                ) {
                    Image(
                        screenshot,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(52.dp)),
                        contentScale = ContentScale.FillBounds,
                    )
                }
            }
        }
    }
}
