package dev.ccpocket.app.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Bundle-identity macOS notifications (issue #99). Posts through `NSUserNotificationCenter` over the
 * objc runtime (JNA), so the banner carries THIS process's bundle identity — the packaged app's name
 * and .icns — and clicking it activates cc-pocket. The previous channel (`osascript -e 'display
 * notification'`) attributed every banner to Script Editor (attribution follows the POSTING process,
 * and that process was osascript), so the icon was the scroll and a click opened Script Editor.
 *
 * Why the deprecated `NSUserNotificationCenter` and not `UNUserNotificationCenter`:
 *  - UN's `requestAuthorization` demands an ObjC *block* completion handler — hand-assembling block
 *    literals through JNA is fragile ABI territory; NS needs no authorization round-trip and no blocks.
 *  - UN throws `NSInternalInconsistencyException` (fatal across the JNI boundary) in a process without
 *    a bundle proxy — e.g. a bare `gradle :run` JVM. NS just returns a nil center, which [boot] detects
 *    so callers can fall back.
 *  - NS still delivers on current macOS. If Apple ever removes the class, [deliver] returns false and
 *    DesktopNotify's osascript fallback takes over — degraded attribution, never a lost signal.
 *
 * Attribution follows the process's bundle: the jpackage .app (`dev.ccpocket.app`). A dev `gradle :run`
 * resolves to the JDK's own bundle instead — the banner then says "java"; cosmetic, dev-only. The JNA
 * native dispatch lib loads fine under the shipped hardened runtime because the Compose plugin's default
 * entitlements include `com.apple.security.cs.disable-library-validation` (verified on the installed app).
 *
 * Threading: [deliver] may be called from any thread — the actual `deliverNotification:` is hopped to
 * the AppKit main thread via `performSelectorOnMainThread`. The click delegate fires ON the AppKit main
 * thread; [onActivate] consumers must hop before touching UI (Main.kt uses `EventQueue.invokeLater`).
 */
internal object MacNotifier {

    /** Click seam: receives the sessionId stashed in the clicked banner's userInfo (null when the payload
     *  carried none). Invoked on the AppKit main thread. */
    @Volatile
    var onActivate: ((sessionId: String?) -> Unit)? = null

    private const val SESSION_KEY = "ccpSessionId"
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    /** The objc runtime plus the exact message-send shapes we use. The `objc_msgSend` overloads bind the
     *  same variadic symbol with FIXED signatures — the required calling pattern on arm64 (each overload
     *  is the JNA equivalent of C's `((id(*)(id,SEL,…))objc_msgSend)(…)` cast). */
    @Suppress("FunctionName")
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(recv: Pointer, sel: Pointer): Pointer?
        fun objc_msgSend(recv: Pointer, sel: Pointer, a: Pointer?): Pointer?
        fun objc_msgSend(recv: Pointer, sel: Pointer, a: Pointer?, b: Pointer?): Pointer?
        fun objc_msgSend(recv: Pointer, sel: Pointer, a: Pointer?, b: Pointer?, waitDone: Int): Pointer?
        fun objc_msgSend(recv: Pointer, sel: Pointer, utf8: ByteArray): Pointer?
        fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Long): Pointer?
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, sel: Pointer, imp: Callback, types: String): Boolean
    }

    // single-method JNA callbacks — the objc side holds only raw trampoline pointers, so these objects
    // must stay strongly referenced for the process lifetime (a GC'd callback = a dangling IMP)
    private fun interface DidActivate : Callback {
        fun callback(self: Pointer?, cmd: Pointer?, center: Pointer?, notif: Pointer?)
    }
    private fun interface ShouldPresent : Callback {
        fun callback(self: Pointer?, cmd: Pointer?, center: Pointer?, notif: Pointer?): Byte
    }

    private var didActivateCb: DidActivate? = null
    private var shouldPresentCb: ShouldPresent? = null

    /** Everything resolved by [boot]; null = channel unavailable (non-mac / nil center / load failure). */
    private class Rt(
        val objc: ObjC,
        val center: Pointer,
        val clsNotification: Pointer,
        val clsString: Pointer,
        val clsDictionary: Pointer,
        val clsPool: Pointer,
        val selAlloc: Pointer,
        val selInit: Pointer,
        val selDrain: Pointer,
        val selStringWithUtf8: Pointer,
        val selDictWithObjForKey: Pointer,
        val selSetTitle: Pointer,
        val selSetInfoText: Pointer,
        val selSetUserInfo: Pointer,
        val selDeliver: Pointer,
        val selPerformMain: Pointer,
    )

    private val runtime: Rt? by lazy { if (!mac) null else runCatching { boot() }.getOrNull() }

    private fun boot(): Rt? {
        val objc = Native.load("objc", ObjC::class.java)
        // NSUserNotification* live in Foundation — load it explicitly so boot order doesn't depend on AWT
        runCatching { NativeLibrary.getInstance("Foundation") }
        fun sel(s: String) = objc.sel_registerName(s)
        val clsCenter = objc.objc_getClass("NSUserNotificationCenter") ?: return null
        // nil center = the process has no usable bundle (bare JVM) — report unavailable, don't crash
        val center = objc.objc_msgSend(clsCenter, sel("defaultUserNotificationCenter")) ?: return null
        val rt = Rt(
            objc = objc,
            center = center,
            clsNotification = objc.objc_getClass("NSUserNotification") ?: return null,
            clsString = objc.objc_getClass("NSString") ?: return null,
            clsDictionary = objc.objc_getClass("NSDictionary") ?: return null,
            clsPool = objc.objc_getClass("NSAutoreleasePool") ?: return null,
            selAlloc = sel("alloc"),
            selInit = sel("init"),
            selDrain = sel("drain"),
            selStringWithUtf8 = sel("stringWithUTF8String:"),
            selDictWithObjForKey = sel("dictionaryWithObject:forKey:"),
            selSetTitle = sel("setTitle:"),
            selSetInfoText = sel("setInformativeText:"),
            selSetUserInfo = sel("setUserInfo:"),
            selDeliver = sel("deliverNotification:"),
            selPerformMain = sel("performSelectorOnMainThread:withObject:waitUntilDone:"),
        )
        installDelegate(rt)
        return rt
    }

    /** Builds a one-off NSObject subclass implementing the two delegate calls we care about and hangs it
     *  on the center. Click → [onActivate] with the userInfo sessionId; shouldPresent → YES so a banner
     *  still shows if the app is technically frontmost while its window is unfocused. Delegate failure is
     *  non-fatal: delivery still works, clicks then merely activate the app (the OS default). */
    private fun installDelegate(rt: Rt) = runCatching {
        val objc = rt.objc
        fun sel(s: String) = objc.sel_registerName(s)
        val nsObject = objc.objc_getClass("NSObject") ?: return@runCatching
        val cls = objc.objc_allocateClassPair(nsObject, "CCPocketNotifyDelegate", 0) ?: return@runCatching
        val selUserInfo = sel("userInfo")
        val selObjectForKey = sel("objectForKey:")
        val selUtf8 = sel("UTF8String")
        val activate = DidActivate { _, _, _, notif ->
            // AppKit main thread — never let an exception unwind back into objc
            runCatching {
                val sid = notif
                    ?.let { objc.objc_msgSend(it, selUserInfo) }
                    ?.let { objc.objc_msgSend(it, selObjectForKey, nsString(rt, SESSION_KEY)) }
                    ?.let { objc.objc_msgSend(it, selUtf8) }
                    ?.getString(0, "UTF-8")
                onActivate?.invoke(sid)
            }
        }
        val present = ShouldPresent { _, _, _, _ -> 1 }
        didActivateCb = activate
        shouldPresentCb = present
        objc.class_addMethod(cls, sel("userNotificationCenter:didActivateNotification:"), activate, "v@:@@")
        objc.class_addMethod(cls, sel("userNotificationCenter:shouldPresentNotification:"), present, "B@:@@")
        objc.objc_registerClassPair(cls)
        val delegate = objc.objc_msgSend(cls, sel("new")) ?: return@runCatching // rc1, lives forever (center holds it weak)
        objc.objc_msgSend(rt.center, sel("setDelegate:"), delegate)
    }.let { }

    private fun nsString(rt: Rt, s: String): Pointer? =
        rt.objc.objc_msgSend(rt.clsString, rt.selStringWithUtf8, s.toByteArray(Charsets.UTF_8) + byteArrayOf(0))

    /** Post a banner as THIS bundle; [sessionId] rides in userInfo for the click→jump seam.
     *  Returns false when the channel is unavailable or the post failed → caller falls back. */
    fun deliver(title: String, body: String, sessionId: String?): Boolean {
        val rt = runtime ?: return false
        return runCatching {
            val o = rt.objc
            // our own autorelease pool: JVM threads have none, and the factory-made NSString/NSDictionary
            // temporaries would otherwise leak with an "autoreleased with no pool in place" console line
            val pool = o.objc_msgSend(o.objc_msgSend(rt.clsPool, rt.selAlloc)!!, rt.selInit)!!
            try {
                val notif = o.objc_msgSend(o.objc_msgSend(rt.clsNotification, rt.selAlloc)!!, rt.selInit)!!
                o.objc_msgSend(notif, rt.selSetTitle, nsString(rt, title))
                o.objc_msgSend(notif, rt.selSetInfoText, nsString(rt, body))
                if (sessionId != null) {
                    val dict = o.objc_msgSend(rt.clsDictionary, rt.selDictWithObjForKey, nsString(rt, sessionId), nsString(rt, SESSION_KEY))
                    o.objc_msgSend(notif, rt.selSetUserInfo, dict)
                }
                // hop to the AppKit main thread; performSelector retains notif until performed. The one
                // alloc/init'd notification is never explicitly released — the center keeps delivered
                // notifications anyway, and a handful of banners per session is noise, not a leak.
                o.objc_msgSend(rt.center, rt.selPerformMain, rt.selDeliver, notif, 0)
            } finally {
                o.objc_msgSend(pool, rt.selDrain)
            }
            true
        }.getOrDefault(false)
    }
}
