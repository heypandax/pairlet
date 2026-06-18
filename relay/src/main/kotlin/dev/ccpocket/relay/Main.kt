package dev.ccpocket.relay

import dev.ccpocket.relay.push.PushConfig
import dev.ccpocket.relay.store.Db
import dev.ccpocket.relay.store.InMemoryRelayStore
import dev.ccpocket.relay.store.SqliteRelayStore

fun main(args: Array<String>) {
    val a = args.toList()
    fun opt(name: String): String? = a.indexOf(name).let { if (it >= 0 && it + 1 < a.size) a[it + 1] else null }
    val host = opt("--host") ?: "127.0.0.1"
    val port = opt("--port")?.toIntOrNull() ?: 9000
    val inMemory = a.contains("--in-memory")
    val dbPath = opt("--db") ?: "${System.getProperty("user.home")}/.cc-pocket-relay/relay.db"

    val store = if (inMemory) {
        InMemoryRelayStore()
    } else {
        java.io.File(dbPath).parentFile?.mkdirs()
        SqliteRelayStore(Db.open(dbPath))
    }

    val where = if (inMemory) "in-memory" else dbPath
    println("cc-pocket relay — http://$host:$port  (ws: /v1/daemon /v1/device · rest: /v1/pair/redeem · /healthz · store: $where)")
    println("zero-knowledge: forwards opaque end-to-end-encrypted frames; stores only fingerprints, pubkeys, and hashes.")
    val pushService = PushConfig.load(store)
    RelayServer(host, port, store, pushService).run()
}
