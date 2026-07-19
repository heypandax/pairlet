package dev.ccpocket.app.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.FileDialog
import java.io.File
import javax.swing.JFileChooser

/**
 * Native "Open Folder…" directory chooser (issue #163) — the IDE affordance for people who don't
 * remember a full path, standing beside ⌘N's typed-path popover rather than replacing it.
 *
 * BLOCKS until the user picks or cancels — call on `Dispatchers.IO`, same contract as [pickAttachments].
 * Returns an absolute path, or null on cancel.
 *
 * Two implementations because only macOS can be asked for a truly native directory panel:
 *  - macOS: AWT [FileDialog] flipped into directory mode by `apple.awt.fileDialogForDirectories`,
 *    which is the real Finder panel.
 *  - elsewhere (the shipped Windows MSI, Linux): Swing [JFileChooser] in DIRECTORIES_ONLY. Not native
 *    chrome — AWT's FileDialog silently degrades to picking FILES off macOS, which would be worse than
 *    a non-native panel that at least selects the right kind of thing.
 */
fun pickDirectory(owner: Frame? = null): String? =
    if (System.getProperty("os.name").lowercase().contains("mac")) pickDirectoryMac(owner)
    else pickDirectorySwing()

/**
 * `apple.awt.fileDialogForDirectories` is a GLOBAL JVM property read by the AWT toolkit at dialog open,
 * not a per-dialog flag — so it MUST be restored. Leaking it true turns the next [pickAttachments] call
 * (the chat attachment button) into a directory chooser, and the user silently loses the ability to
 * attach files, with nothing on screen explaining why.
 */
private fun pickDirectoryMac(owner: Frame?): String? {
    val key = "apple.awt.fileDialogForDirectories"
    val prior = System.getProperty(key)
    return try {
        System.setProperty(key, "true")
        val dialog = FileDialog(owner, "Open Folder", FileDialog.LOAD)
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        File(dir, name).absolutePath
    } finally {
        if (prior == null) System.clearProperty(key) else System.setProperty(key, prior)
    }
}

/**
 * THE "Open Folder…" action — ⌘O, the sidebar row and the ⌘K palette all call this one function, so the
 * remote-machine fallback and the off-UI-thread rule can't drift apart across three copies.
 *
 * The dialog runs on [Dispatchers.IO] because it blocks: on the Compose UI thread it would freeze the
 * whole window until the user dismisses it (the same reason `ChatPane` launches [pickAttachments] that
 * way). A remote active machine never opens a panel at all — a local chooser can only browse local disk,
 * so it degrades to the typed-path popover, which works against any daemon.
 */
fun openFolderAction(scope: CoroutineScope, model: DesktopModel) {
    if (!model.activeIsThisMachine) {
        model.openNewSession("~/")
        return
    }
    scope.launch {
        val dir = withContext(Dispatchers.IO) { pickDirectory() }
        if (dir != null) model.openFolderPath(dir)
    }
}

private fun pickDirectorySwing(): String? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Open Folder"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else null
}
