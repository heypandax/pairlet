package dev.ccpocket.daemon.util

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drains one output pipe of a child process on a thread of its own, keeping at most [cap] chars and
 * discarding the rest (a chatty command must neither blow the relay frame nor block on a full pipe).
 *
 * Why not an `async` inside the caller's `withContext`: a structured child is awaited by its scope no
 * matter what, and a blocking `read` cannot be cancelled. When the process exits but something it started
 * — a hook's background job, an ssh ControlMaster, `npm run dev &` — still holds the write end, EOF never
 * comes and the whole call never returns, `withTimeoutOrNull` around the await notwithstanding. Here the
 * reader is NOT tied to the caller: [text] waits a bounded time and then answers with what it has, while
 * the reader keeps draining in the background (so the lingering process never blocks on a full pipe) and
 * ends by itself at EOF.
 *
 * A read error (the JDK closes a destroyed process's streams on Unix) ends the drain quietly with what was
 * read so far; it is never rethrown into the caller.
 */
internal class ChildOutput(stream: InputStream, private val cap: Int) {
    private val sb = StringBuilder()
    private val done = CountDownLatch(1)

    init {
        readers.execute {
            try {
                stream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(sb) {
                            val room = cap - sb.length
                            if (room > 0) sb.append(buf, 0, minOf(n, room))
                        }
                    }
                }
            } catch (_: IOException) {
                // stream closed under us: keep what we have
            } finally {
                done.countDown()
            }
        }
    }

    /** Wait until EOF or [waitMs], whichever is first, then return what has been read. Never longer. */
    fun text(waitMs: Long): String {
        if (waitMs > 0) done.await(waitMs, TimeUnit.MILLISECONDS)
        return synchronized(sb) { sb.toString() }
    }

    companion object {
        /** Cached daemon threads: idle ones are reused across the many short git calls, and one parked on a
         *  pipe a lingering grandchild holds never keeps the JVM alive. */
        private val readers: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "child-output").apply { isDaemon = true }
        }

        /** [ChildOutput.text] for a pair of pipes sharing ONE deadline, so the worst case is [waitMs], not twice. */
        fun both(out: ChildOutput, err: ChildOutput, waitMs: Long): Pair<String, String> {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
            val o = out.text(waitMs)
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            return o to err.text(left)
        }
    }
}
