package dev.ccpocket.daemon

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * A stand-in "running daemon" for [InstanceLockTest], run in its OWN JVM (Java file locks are per process, so
 * an in-process holder could not show what a second process sees): takes the instance lock on `args[0]`,
 * optionally listens on the pair port `args[1]` the way a started daemon does, prints READY (or BUSY when the
 * lock was taken), then idles until killed or its stdin closes.
 */
fun main(args: Array<String>) {
    val lock = InstanceLock.tryAcquire(File(args[0]))
    if (lock == null) {
        println("BUSY")
        return
    }
    val pair = args.getOrNull(1)?.toInt()?.let { ServerSocket(it, 50, InetAddress.getByName("127.0.0.1")) }
    println("READY")
    System.out.flush()
    System.`in`.read() // EOF = the test is done with us
    pair?.close()
    lock.close()
}
