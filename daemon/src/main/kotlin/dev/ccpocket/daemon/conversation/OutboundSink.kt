package dev.ccpocket.daemon.conversation

import dev.ccpocket.protocol.Frame

/** Where a conversation emits frames toward the connected client. Decouples from the transport.
 *  Kept a bare SAM (no member properties): a `fun interface` with a property member makes the
 *  compiler emit an illegal `<get-key>` accessor into every `OutboundSink { … }` lambda class, which
 *  the JVM rejects at load time. Identity lives on [KeyedSink] + the [key] extension instead. */
fun interface OutboundSink {
    suspend fun emit(frame: Frame)
}

/**
 * Ties a stable [key] to a transport lambda. Sinks sharing a key REPLACE each other in a conversation's
 * fan-out set — the relay mints a fresh lambda per inbound frame, so instance identity alone would stack
 * duplicates. Callers dedup with [sinkKey] (a plain/LAN sink has no key → falls back to instance identity).
 *
 * NOTE: the identity is deliberately NOT a `val key` on [OutboundSink]. A property member (or even an
 * extension property) on this functional interface makes the Kotlin compiler emit an illegal `<get-key>`
 * accessor into every `OutboundSink { … }` SAM lambda, which the JVM rejects at class-load time.
 */
class KeyedSink(val key: Any, private val delegate: OutboundSink, val watching: Boolean = true) : OutboundSink {
    override suspend fun emit(frame: Frame) = delegate.emit(frame)
}

/** Does this sink represent a real client that can SEE frames it receives (and thus answer an ask)?
 *  A [KeyedSink] can opt out (the scheduler's headless fire sink is a black hole — issue #137/C1);
 *  a plain sink is assumed to be a live client. Drives the ask-push "someone is watching" decision. */
fun OutboundSink.isWatching(): Boolean = (this as? KeyedSink)?.watching ?: true

/** Fan-out identity of a sink: a [KeyedSink]'s key, else the sink instance itself. */
fun sinkKey(sink: OutboundSink): Any = (sink as? KeyedSink)?.key ?: sink
