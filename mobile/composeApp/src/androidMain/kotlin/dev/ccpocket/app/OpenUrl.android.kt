package dev.ccpocket.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

private var appContext: Context? = null

/** Called from MainActivity.onCreate (same seam as initSecureStore/initVoice). */
fun initUrlOpener(context: Context) { appContext = context.applicationContext }

/** Custom Tab via the raw extra (no androidx.browser dependency): browsers that support Custom Tabs
 *  render an in-app-style sheet; anything else just opens as a normal ACTION_VIEW. */
actual fun openWebUrl(url: String) {
    val ctx = appContext ?: return
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // launched from application context
            },
        )
    }
}
