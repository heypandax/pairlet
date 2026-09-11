package dev.ccpocket.observability

private val nativeSymbol = Regex("(?:^|\\s)kfun:(dev\\.ccpocket\\.[A-Za-z0-9_.$<>#-]+)")
private val nativeLocation = Regex(" \\(([^\\r\\n]+?):([0-9]+)(?::[0-9]+)?\\)\\s*$")

/** Kotlin/Native may append source information. Keep only its basename, never the raw frame. */
internal fun safeNativeFrame(raw: String): SafeStackFrame? {
    if (raw.length > 4096 || '\n' in raw || '\r' in raw) return null
    val symbol = nativeSymbol.find(raw)?.groupValues?.get(1)?.replace('#', '.') ?: return null
    val location = nativeLocation.find(raw)
    val file = location?.groupValues?.get(1)?.substringAfterLast('/')?.substringAfterLast('\\')
    val line = location?.groupValues?.get(2)?.toIntOrNull()
    return SafeSymbols.frame(symbol, file, line)
}
