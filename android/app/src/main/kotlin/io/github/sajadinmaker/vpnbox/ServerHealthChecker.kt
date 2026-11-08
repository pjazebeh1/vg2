
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

    suspend fun rankForConnect(
        servers: List<Server>
    ): List<Candidate> = coroutineScope {

        val semaphore = Semaphore(24)

        servers.map { server ->
            async(Dispatchers.IO) {
                Candidate(
                    server,
                    if (server.proto.equals("tcp", true)) {
                        semaphore.withPermit {
                            probeTcp(server)
                        }
                    } else null
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
                { if (it.server.proto == "udp") 0 else 1 },
                { if (it.server.ping in 1L..<900L) 0 else 1 },
                { -it.server.speed },
                { if (it.server.ping > 0)
                    it.server.ping else Long.MAX_VALUE },
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
            Socket().use {
                it.connect(
                    InetSocketAddress(server.ip, port),
                    1800
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
