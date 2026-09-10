package dev.ccpocket.observability

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.*

private val budgetFileLock = DiagnosticLock()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun diagnosticBudgetStore(component: Component, environment: Environment, directory: String?, counters: Boolean): DiagnosticBudgetStore {
    val root = directory ?: NSHomeDirectory() + "/Library/Application Support/cc-pocket/" + if (counters) "diagnostic-counters" else "diagnostic-budgets"
    val path = "$root/${component.name.lowercase()}-${environment.name.lowercase()}.json"
    return DiagnosticBudgetStore { transform ->
        budgetFileLock.withLock {
            runCatching {
                val manager = NSFileManager.defaultManager
                check(manager.createDirectoryAtPath(root, true, null, null))
                val raw = if (!manager.fileExistsAtPath(path)) null else {
                    val size = (manager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue
                        ?: error("budget_read_failed")
                    if (size > 4096) "invalid" else
                        NSString.create(contentsOfFile = path, encoding = NSUTF8StringEncoding, error = null)?.toString()
                            ?: error("budget_read_failed")
                }
                val next = transform(raw)
                check(next.encodeToByteArray().size <= 4096)
                (next as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            }.getOrDefault(false)
        }
    }
}
