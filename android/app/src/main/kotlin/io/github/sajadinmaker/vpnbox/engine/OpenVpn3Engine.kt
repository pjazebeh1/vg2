
package io.github.sajadinmaker.vpnbox.engine

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import io.github.sajadinmaker.vpnbox.data.Server
import io.github.sajadinmaker.vpnbox.data.buildConf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull

class OpenVpn3Engine(private val context: Context) : TunnelEngine {

    override val events: Flow<EngineEvent>
        get() = EngineBus.events

    override suspend fun start(
        server: Server,
        keepalive: Pair<Int, Int>
    ): Result<Unit> {
        val profile = buildConf(server, keepalive)
        val label = serverLabel(server)

        EngineBus.handshake.value = null

        val intent = Intent(
            context,
            VpnboxVpnService::class.java
        ).apply {
            action = VpnboxVpnService.ACTION_CONNECT
            putExtra(VpnboxVpnService.EXTRA_PROFILE, profile)
            putExtra(VpnboxVpnService.EXTRA_SERVER_LABEL, label)
        }

        try {
            ContextCompat.startForegroundService(context, intent)

            val settled = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                EngineBus.handshake.filterNotNull().first()
            }

            return when (settled) {
                is EngineEvent.HandshakeOk -> {
                    Result.success(Unit)
                }

                is EngineEvent.HandshakeFailed -> {
                    teardown()
                    Result.failure(
                        VpnGateEngineError(
                            settled.excerpt.joinToString("\n")
                                .ifBlank { "OpenVPN handshake failed" }
                        )
                    )
                }

                else -> {
                    teardown()
                    Result.failure(
                        VpnGateEngineError(
                            "OpenVPN handshake timeout after 8 seconds"
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            teardown()
            throw e
        } catch (e: Exception) {
            teardown()
            Result.failure(e)
        }
    }

    private fun teardown() {
        stop()
    }

    override fun stop() {
        val intent = Intent(
            context,
            VpnboxVpnService::class.java
        ).apply {
            action = VpnboxVpnService.ACTION_DISCONNECT
        }

        runCatching {
            context.startService(intent)
        }
    }

    fun vpnConsentRequired(): Boolean =
        VpnService.prepare(context) != null

    fun vpnConsentIntent(): Intent? =
        VpnService.prepare(context)

    private fun serverLabel(server: Server): String =
        buildString {
            append(server.country)

            if (server.city.isNotBlank()) {
                append(" · ")
                append(server.city)
            }

            append(" (")
            append(server.proto.uppercase())
            append(")")
        }

    class VpnGateEngineError(message: String) : Exception(message)

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 8_000L
    }
}
