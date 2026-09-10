package dev.ccpocket.observability.sentry

import dev.ccpocket.observability.DiagnosticCounter
import dev.ccpocket.observability.DiagnosticCounters
import io.sentry.Hint
import io.sentry.SentryEnvelope
import io.sentry.hints.SubmissionResult
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import io.sentry.util.HintUtils
import java.util.concurrent.atomic.AtomicBoolean

/** Observe the SDK's final envelope result, including failures with no HTTP response code.
 * This counter uses envelope submissions, not error/log items or acknowledged HTTP requests.
 * Payloads and existing SDK hints are left intact; no retries, persistence or recursive uploads.
 */
internal class CountedSentryTransport(
    private val delegate: ITransport,
    private val counters: DiagnosticCounters?,
) : ITransport {
    override fun send(envelope: SentryEnvelope, hint: Hint) {
        // This isolated client supplies no typed hint. Never strip a future SDK hint's marker interfaces.
        if (counters == null || HintUtils.getSentrySdkHint(hint) != null) {
            delegate.send(envelope, hint)
            return
        }
        val complete = AtomicBoolean()
        val success = AtomicBoolean()
        val result = object : SubmissionResult {
            override fun setResult(value: Boolean) {
                if (complete.compareAndSet(false, true)) {
                    success.set(value)
                    if (!value) counters.increment(DiagnosticCounter.TRANSPORT_FAILED)
                }
            }
            override fun isSuccess(): Boolean = success.get()
        }
        HintUtils.setTypeCheckHint(hint, result)
        try { delegate.send(envelope, hint) }
        catch (failure: Exception) { result.setResult(false); throw failure }
    }

    override fun flush(timeoutMillis: Long) = delegate.flush(timeoutMillis)
    override fun isHealthy(): Boolean = delegate.isHealthy
    override fun getRateLimiter(): RateLimiter? = delegate.rateLimiter
    override fun close() = delegate.close()
    override fun close(isRestarting: Boolean) = delegate.close(isRestarting)
}
