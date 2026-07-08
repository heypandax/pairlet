package dev.ccpocket.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

actual val exportIsSaveDialog: Boolean = false

private var appContext: Context? = null

/** Called from MainActivity.onCreate (same seam as initSecureStore/initUrlOpener). */
fun initFileExport(context: Context) { appContext = context.applicationContext }

/** Materialize the bytes under cacheDir/exports and mint a FileProvider uri other apps may read. */
private fun exportUri(ctx: Context, name: String, bytes: ByteArray): Uri? = runCatching {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, File(name).name) // defensive: a plain name is expected, never a path
    file.writeBytes(bytes)
    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
}.getOrNull()

/** ACTION_SEND chooser: save to Downloads via Files, or hand to WeChat/Feishu/Drive (issue #67). */
actual fun shareFile(name: String, bytes: ByteArray, mediaType: String?) {
    val ctx = appContext ?: return
    val uri = exportUri(ctx, name, bytes) ?: return
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mediaType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** ACTION_VIEW = whatever office/pdf viewer the device has (issue #79); false when none can. */
actual fun previewFile(name: String, bytes: ByteArray, mediaType: String?): Boolean {
    val ctx = appContext ?: return false
    val uri = exportUri(ctx, name, bytes) ?: return false
    return runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mediaType ?: "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }.getOrDefault(false)
}
