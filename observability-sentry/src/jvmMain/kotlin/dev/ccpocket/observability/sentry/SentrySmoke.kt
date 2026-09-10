package dev.ccpocket.observability.sentry

import dev.ccpocket.observability.*

/** No user files, identities, services, or process-wide telemetry settings are accessed. */
fun main(args: Array<String>) {
    val component = smokeComponent(args.singleOrNull())
    check(System.getProperty("ccpocket.test") != "true") { "Cloud smoke probes cannot run inside the unit test runner" }
    val dsn = SentryRuntime.configuredDsn(component)
    check(SentryDiagnosticSink.validDsn(dsn)) { "A valid HTTPS DSN for the selected component is required" }
    val sink = SentryDiagnosticSink.createConfigured(dsn!!, component) { options ->
        // Only synthetic records reach this standalone probe, so SDK transport diagnostics
        // can expose server rejection reasons without ever forwarding application logs.
        if (System.getenv("CCPOCKET_SMOKE_DEBUG") == "1") {
            options.isDebug = true
            options.setLogger(io.sentry.SystemOutLogger())
        }
    }
    sink.use {
        val reporter = DiagnosticReporter(component, Environment.STAGING, "pairlet-diagnostic-smoke@1", sink)
        val (errorId, logId) = emitSmoke(reporter)
        // A one-shot probe can wait longer than an application's lifecycle callback.
        // Keep the transport gate open while both error and batched log requests drain.
        repeat(5) { sink.flush() }
        println("Synthetic staging records queued: error=$errorId log=$logId component=${component.name.lowercase()}")
        println("Queue drops=${sink.droppedCount()} rate_limited=${sink.isRateLimited()}")
        println("Verify both IDs in Sentry; local queue acceptance is not a cloud delivery receipt.")
    }
}

internal fun smokeComponent(value: String?): Component = when (value) {
    "daemon" -> Component.DAEMON
    "relay" -> Component.RELAY
    "desktop" -> Component.DESKTOP
    else -> error("Choose daemon, relay, or desktop; a JVM probe cannot validate mobile platform SDKs")
}

internal fun emitSmoke(reporter: DiagnosticReporter): Pair<String, String> {
    val errorId = reporter.report(ErrorPath.DIAGNOSTICS, Stage.VERIFY, ErrorCode.SMOKE_TEST,
        SentrySmokeException()) ?: error("Synthetic error was not accepted by the diagnostic queue")
    val logId = reporter.report(ErrorPath.DIAGNOSTICS, Stage.VERIFY, ErrorCode.SMOKE_TEST)
        ?: error("Synthetic log was not accepted by the diagnostic queue")
    return errorId to logId
}

private class SentrySmokeException : RuntimeException()
