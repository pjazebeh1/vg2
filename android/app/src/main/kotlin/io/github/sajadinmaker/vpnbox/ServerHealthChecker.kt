
package io.github.sajadinmaker.vpnbox

import io.github.sajadinmaker.vpnbox.data.Server
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal object ServerHealthChecker {

    data class Candidate(
        val server: Server,
        val tcpReachable: Boolean?
    )

    private const val MAX_CONCURRENT_PROBES = 40
    private const val TCP_TIMEOUT_MS = 950
    private const val MAX_TCP_PROBES = 80

    suspend fun rankForConnect(
        servers: List<Server>
    ): List<Candidate> = coroutineScope {

        if (servers.isEmpty()) {
            return@coroutineScope emptyList()
        }

        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)

        // ابتدا سرورهای TCP با امتیاز بهتر انتخاب می‌شوند
        val tcpCandidates = servers
            .filter { it.proto.equals("tcp", true) }
            .sortedWith(
                compareBy<Server>(
                    { if (it.ping > 0) it.ping else Long.MAX_VALUE },
                    { -it.speed },
                    { -it.score }
                )
            )
            .take(MAX_TCP_PROBES)
            .toSet()

        servers.map { server ->
            async(Dispatchers.IO) {
                val reachable = if (
                    server.proto.equals("tcp", true) &&
                    server in tcpCandidates
                ) {
                    semaphore.withPermit {
                        probeTcp(server)
                    }
                } else {
                    null
                }

                Candidate(
                    server = server,
                    tcpReachable = reachable
                )
            }
        }.awaitAll().sortedWith(
            compareBy<Candidate>(
                {
                    when (it.tcpReachable) {
                        true -> 0
                        null -> 1
                        false -> 2
                    }
                },
                { if (it.server.proto.equals("udp", true)) 0 else 1 },
                { if (it.server.ping in 1L..<900L) 0 else 1 },
                { -it.server.speed },
                {
                    if (it.server.ping > 0)
                        it.server.ping
                    else
                        Long.MAX_VALUE
                },
                { -it.server.score }
            )
        )
    }

    private fun probeTcp(server: Server): Boolean {
        val port = server.port.toIntOrNull() ?: return false

        if (server.ip.isBlank() || port !in 1..65535) {
            return false
        }

        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(server.ip, port),
                    TCP_TIMEOUT_MS
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
