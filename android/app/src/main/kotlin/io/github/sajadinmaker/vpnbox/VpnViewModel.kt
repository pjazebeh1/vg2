
package io.github.sajadinmaker.vpnbox

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sajadinmaker.vpnbox.data.AppUiSettings
import io.github.sajadinmaker.vpnbox.data.CacheStore
import io.github.sajadinmaker.vpnbox.data.Server
import io.github.sajadinmaker.vpnbox.data.ServerCache
import io.github.sajadinmaker.vpnbox.data.SessionIntent
import io.github.sajadinmaker.vpnbox.data.SessionStore
import io.github.sajadinmaker.vpnbox.data.UserError
import io.github.sajadinmaker.vpnbox.data.VpnGate
import io.github.sajadinmaker.vpnbox.data.VpnGateParser
import io.github.sajadinmaker.vpnbox.data.backoffSeconds
import io.github.sajadinmaker.vpnbox.data.checkPingSettings
import io.github.sajadinmaker.vpnbox.data.observeSettings
import io.github.sajadinmaker.vpnbox.data.selectServers
import io.github.sajadinmaker.vpnbox.data.validateArgs
import io.github.sajadinmaker.vpnbox.data.setDynamicColor
import io.github.sajadinmaker.vpnbox.data.setThemeMode
import io.github.sajadinmaker.vpnbox.data.ThemeMode
import io.github.sajadinmaker.vpnbox.engine.EngineBus
import io.github.sajadinmaker.vpnbox.engine.EngineEvent
import io.github.sajadinmaker.vpnbox.engine.OpenVpn3Engine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Screen { HOME, TERMINAL, SETTINGS, ABOUT }
enum class WatchPhase { CONNECT, WATCH, KILL, RECOVERED, FAILED }

data class CountryPick(val cc: String, val country: String, val servers: Int)

data class HomeUi(
    val connected: Boolean = false,
    val servers: Int = 0,
    val countries: Int = 0,
    val cacheAgeMin: Long? = null,
    val geoLabel: String = "",
    val message: String = "",
    val lastCountry: String? = null,
    val lastCountryLabel: String? = null,
    val lastCity: String? = null,
)

data class TerminalLine(val text: String, val tone: Tone) {
    enum class Tone { PLAIN, OK, WARN, ERR, ACCENT }
}

data class TerminalUi(
    val phase: WatchPhase = WatchPhase.CONNECT,
    val lines: List<TerminalLine> = emptyList(),
    val cycle: Int = 0,
    val cycles: Int = 3,
)

class VpnViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = OpenVpn3Engine(app)
    private val filesDir: File =
        app.getExternalFilesDir(null) ?: app.filesDir

    private val cacheStore = CacheStore(File(filesDir, "servers.json"))
    private val sessionStore = SessionStore(File(filesDir, "session.json"))

    val screen = MutableStateFlow(Screen.HOME)
    val home = MutableStateFlow(HomeUi())
    val terminal = MutableStateFlow(TerminalUi())
    val countries = MutableStateFlow<List<CountryPick>>(emptyList())
    val settings = MutableStateFlow(AppUiSettings())

    init {
        viewModelScope.launch {
            getApplication<Application>()
                .observeSettings()
                .collect { settings.value = it }
        }
    }

    private var cache: ServerCache? = null
    private var intent: SessionIntent? = null
    private var connectJob: Job? = null
    private var watchJob: Job? = null
    private var hasHandshake = false
    private var downPending = false
    private val cycleMax = 3

    // Smart Connect
    // No server-count limit: inspect every ranked server.
    private val smartConnectHandshakeTimeout = 30_000L
    private var handshakeWaiter: CompletableDeferred<Boolean>? = null

    private var started = false

    fun start() {
        if (started) return
        started = true

        viewModelScope.launch {
            engine.events.collect { consumeEngineEvent(it) }
        }

        cache = cacheStore.load()
        intent = sessionStore.load()

        projectCacheToHome()
        refreshGeoLabel()
        restoreRunningTunnel()
    }

    private fun restoreRunningTunnel() {
        if (!EngineBus.tunnelUp.value) return

        val saved = intent
        if (saved != null && !saved.stopped) {
            hasHandshake = true
            home.update { it.copy(connected = true) }
            screen.value = Screen.TERMINAL

            terminal.value = TerminalUi(
                phase = WatchPhase.WATCH,
                lines = listOf(
                    TerminalLine(
                        "Tunnel already active — continuing the session",
                        TerminalLine.Tone.OK
                    )
                )
            )

            startWatchLoop(saved.country, saved.city, saved.pingExit)
        }
    }

    private fun projectCacheToHome() {
        val servers = cache?.servers.orEmpty()

        home.update { h ->
            val cc = intent?.country
            val label = cc?.let { want ->
                servers.firstOrNull { it.cc == want }?.country
            } ?: cc

            h.copy(
                connected = h.connected,
                servers = servers.size,
                countries = servers.map { it.cc }.distinct().size,
                cacheAgeMin = cache?.let {
                    (System.currentTimeMillis() - it.updated) / 60_000L
                },
                lastCountry = cc,
                lastCity = intent?.city,
                lastCountryLabel = label
            )
        }
    }

    private fun refreshGeoLabel() {
        viewModelScope.launch(Dispatchers.IO) {
            bindUntilVpnReady()
            val g = VpnGate.fetchGeo() ?: return@launch

            val where = listOf(g["city"], g["regionName"], g["country"])
                .filter { !it.isNullOrBlank() }
                .joinToString(", ")

            home.update {
                it.copy(geoLabel = "${g["query"]}  ·  $where")
            }
        }
    }

    private fun bindToVpnNetworkIfAny(): Boolean {
        return runCatching {
            val cm = getApplication<Application>()
                .getSystemService(ConnectivityManager::class.java)
                ?: return false

            val vpn = cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } ?: return false

            cm.bindProcessToNetwork(vpn)
        }.getOrDefault(false)
    }

    private suspend fun bindUntilVpnReady(): Boolean {
        repeat(10) {
            if (bindToVpnNetworkIfAny()) return true
            delay(500)
        }
        return bindToVpnNetworkIfAny()
    }

    private fun unbindFromNetwork() {
        runCatching {
            val cm = getApplication<Application>()
                .getSystemService(ConnectivityManager::class.java)
                ?: return
            cm.bindProcessToNetwork(null)
        }
    }

    // ---- Refresh ------------------------------------------------------------

    fun refresh() {
        connectJob?.cancel()

        connectJob = viewModelScope.launch(Dispatchers.IO) {
            home.update { it.copy(message = "Refreshing server list…") }

            try {
                val text = VpnGate.fetchListText()
                val servers = VpnGateParser.parse(text)
                VpnGate.enrichCity(servers)

                val fresh = ServerCache(System.currentTimeMillis(), servers)
                cacheStore.save(fresh)
                cache = fresh

                projectCacheToHome()
                rebuildCountries()

                val responsive = servers.count { it.ping in 1L..<900L }

                home.update {
                    it.copy(
                        message = "Updated · ${servers.size} servers · " +
                            "${servers.map { s -> s.cc }.distinct().size} countries · " +
                            "$responsive responding now"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                home.update {
                    it.copy(message = "Refresh failed — ${e.message ?: "network unavailable"}")
                }
            }
        }
    }

    fun rebuildCountries() {
        val servers = cache?.servers.orEmpty()

        countries.value = servers
            .groupBy { it.cc }
            .map { (cc, rows) -> CountryPick(cc, rows.first().country, rows.size) }
            .sortedWith(compareBy({ -it.servers }, { it.cc }))
    }

    // ---- Smart Connect -------------------------------------------------------

    fun connect(country: String?, city: String?) {
        if (screen.value == Screen.TERMINAL && connectJob?.isActive == true) return

        screen.value = Screen.TERMINAL
        terminal.value = TerminalUi(phase = WatchPhase.CONNECT)

        intent = null
        downPending = false
        connectJob?.cancel()

        connectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val ping = 10
                val exit = checkPingSettings(ping, 60)

                validateArgs(
                    skip = 0,
                    index = null,
                    maxTries = Int.MAX_VALUE,
                    ping = ping,
                    pingExit = exit
                )

                var c = cache ?: cacheStore.load()

                if (c == null ||
                    System.currentTimeMillis() - c.updated > 30 * 60_000L
                ) {
                    line(
                        "Server list is missing or outdated — downloading a fresh copy…",
                        TerminalLine.Tone.WARN
                    )

                    val text = VpnGate.fetchListText()
                    val servers = VpnGateParser.parse(text)
                    VpnGate.enrichCity(servers)

                    c = ServerCache(System.currentTimeMillis(), servers)
                    cacheStore.save(c)
                    cache = c
                    rebuildCountries()
                }

                var servers0 = c?.servers ?: emptyList()

                if (servers0.isEmpty()) {
                    line(
                        "Server list is unavailable",
                        TerminalLine.Tone.ERR
                    )
                    terminal.update { it.copy(phase = WatchPhase.FAILED) }
                    return@launch
                }

                val (selected, why) = selectServers(servers0, country, city)

                if (selected.isEmpty()) {
                    line(
                        why.ifBlank { "No matching servers — try a different country." },
                        TerminalLine.Tone.ERR
                    )
                    terminal.update { it.copy(phase = WatchPhase.FAILED) }
                    return@launch
                }

                line("Checking server reachability...", TerminalLine.Tone.PLAIN)

                var checked = ServerHealthChecker.rankForConnect(selected)
                var reachable = checked.count { it.tcpReachable == true }
                val tcpCount = checked.count {
                    it.server.proto.equals("tcp", true)
                }

                line(
                    "Precheck: $reachable/$tcpCount TCP endpoints reachable",
                    TerminalLine.Tone.PLAIN
                )

                // Keep trying indefinitely until a real handshake succeeds.
                var round = 0

                while (true) {
                    round++

                    val ranked = checked.map { it.server }

                    line(
                        "Starting server scan · round $round · ${ranked.size} servers",
                        TerminalLine.Tone.ACCENT
                    )

                    for ((i, r) in ranked.withIndex()) {
                        attemptLine(i, ranked.size, r)

                        line(
                            "Waiting for OpenVPN handshake (max 30 s)…",
                            TerminalLine.Tone.PLAIN
                        )

                        val handshake = CompletableDeferred<Boolean>()
                        handshakeWaiter = handshake
                        hasHandshake = false

                        val started = engine.start(
                            r,
                            keepalive = Pair(ping, exit)
                        ).isSuccess

                        val confirmed = if (started) {
                            withTimeoutOrNull(smartConnectHandshakeTimeout) {
                                handshake.await()
                            } == true
                        } else {
                            false
                        }

                        handshakeWaiter = null

                        if (confirmed) {
                            succeed(r, country, city, exit)
                            startWatchLoop(country, city, exit)
                            return@launch
                        }

                        line(
                            "Handshake failed or timed out after 30 s — next server",
                            TerminalLine.Tone.WARN
                        )

                        runCatching { engine.stop() }
                        delay(150)
                    }

                    // Every server in the current list has been attempted.
                    line(
                        "All ${ranked.size} servers failed — refreshing list and retrying",
                        TerminalLine.Tone.WARN
                    )

                    runCatching { engine.stop() }
                    delay(500)

                    try {
                        val text = VpnGate.fetchListText()
                        servers0 = VpnGateParser.parse(text)
                        VpnGate.enrichCity(servers0)

                        val fresh = ServerCache(
                            System.currentTimeMillis(),
                            servers0
                        )

                        cacheStore.save(fresh)
                        cache = fresh
                        projectCacheToHome()
                        rebuildCountries()

                        val (newSelected, _) =
                            selectServers(servers0, country, city)

                        if (newSelected.isNotEmpty()) {
                            checked =
                                ServerHealthChecker.rankForConnect(newSelected)

                            reachable =
                                checked.count { it.tcpReachable == true }

                            line(
                                "New list: ${checked.size} servers · " +
                                    "$reachable TCP reachable",
                                TerminalLine.Tone.PLAIN
                            )
                        } else {
                            line(
                                "No servers found in refreshed list — retrying",
                                TerminalLine.Tone.WARN
                            )
                            delay(1000)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        line(
                            "Server refresh failed: ${e.message ?: "network error"}",
                            TerminalLine.Tone.ERR
                        )
                        delay(2000)
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: UserError) {
                line("× ${e.message}", TerminalLine.Tone.ERR)
                terminal.update { it.copy(phase = WatchPhase.FAILED) }
            } catch (e: Exception) {
                line(
                    "× Could not connect: ${e.message ?: "network error"}",
                    TerminalLine.Tone.ERR
                )
                terminal.update { it.copy(phase = WatchPhase.FAILED) }
            }
        }
    }

    private suspend fun attemptLine(i: Int, total: Int, r: Server) {
        terminal.update {
            it.copy(
                lines = it.lines + TerminalLine(
                    "Attempt ${i + 1}/$total · ${r.country} · ${r.ip} · " +
                        "${r.proto.uppercase()}/${r.port.ifBlank { "?" }}" +
                        (if (r.ping > 0) " · ${r.ping} ms" else ""),
                    TerminalLine.Tone.ACCENT
                )
            )
        }
    }

    private suspend fun succeed(
        r: Server,
        country: String?,
        city: String?,
        exit: Int
    ) {
        hasHandshake = true

        val fresh = SessionIntent(
            country = country ?: r.country,
            city = city,
            proto = r.proto,
            ping = 10,
            pingExit = exit
        )

        intent = fresh
        sessionStore.save(fresh)

        home.update {
            it.copy(
                connected = true,
                lastCountry = fresh.country,
                lastCountryLabel = r.country,
                lastCity = fresh.city
            )
        }

        line(
            "Connected · ${r.country}" +
                (if (r.city.isNotBlank()) " · ${r.city}" else ""),
            TerminalLine.Tone.OK
        )

        bindUntilVpnReady()

        VpnGate.fetchGeo()?.let { geo ->
            val where = listOf(geo["city"], geo["regionName"], geo["country"])
                .filter { !it.isNullOrBlank() }
                .joinToString(", ")

            line("Exit IP: ${geo["query"]}", TerminalLine.Tone.PLAIN)
            line(
                "Location: $where · ISP ${geo["isp"]?.ifBlank { "?" }}",
                TerminalLine.Tone.PLAIN
            )

            val geoCc = geo["countryCode"]?.uppercase()

            if (!geoCc.isNullOrBlank() && geoCc != r.cc.uppercase()) {
                line(
                    "Warning: exit IP country ($geoCc) differs from the " +
                        "selected server (${r.cc.uppercase()}) — full-tunnel " +
                        "routing may not be active",
                    TerminalLine.Tone.WARN
                )
            }
        }

        line("You can disconnect from the home screen", TerminalLine.Tone.PLAIN)
        terminal.update { it.copy(phase = WatchPhase.WATCH) }
        refreshGeoLabel()
    }

    private fun line(text: String, tone: TerminalLine.Tone) {
        terminal.update {
            it.copy(lines = it.lines + TerminalLine(text, tone))
        }
    }

    // ---- Watch / Reconnect --------------------------------------------------

    private fun startWatchLoop(country: String?, city: String?, exit: Int) {
        watchJob?.cancel()

        watchJob = viewModelScope.launch {
            var failures = 0

            while (true) {
                delay(1500)

                if (!downPending) {
                    if (sessionStore.load()?.stopped == true) return@launch
                    continue
                }

                downPending = false
                line("Tunnel down", TerminalLine.Tone.WARN)
                terminal.update { it.copy(phase = WatchPhase.KILL) }

                val pending = intent ?: return@launch

                if (pending.stopped) {
                    line("Disconnect was requested — staying offline", TerminalLine.Tone.PLAIN)
                    hasHandshake = false
                    home.update { it.copy(connected = false) }
                    return@launch
                }

                line(
                    "Reconnecting (attempt ${failures + 1} of $cycleMax)",
                    TerminalLine.Tone.WARN
                )

                val (sel, _) = selectServers(cache?.servers.orEmpty(), country, city)
                var recovered: Server? = null

                for (r in sel.take(3)) {
                    unbindFromNetwork()
                    attemptLine(0, 3, r)

                    if (engine.start(r, keepalive = Pair(10, exit)).isSuccess) {
                        recovered = r
                        break
                    }
                }

                val rec = recovered

                if (rec == null) {
                    failures++

                    if (failures >= cycleMax) {
                        line(
                            "Reconnection failed after $failures attempts. " +
                                "The tunnel is down — refresh the list and try again",
                            TerminalLine.Tone.ERR
                        )
                        terminal.update { it.copy(phase = WatchPhase.FAILED) }
                        hasHandshake = false
                        home.update { it.copy(connected = false) }
                        return@launch
                    }

                    val wait = backoffSeconds(failures)
                    line(
                        "Reconnection attempt $failures of $cycleMax failed. Retrying in $wait s",
                        TerminalLine.Tone.ERR
                    )

                    val until = System.currentTimeMillis() + wait * 1000

                    while (System.currentTimeMillis() < until) {
                        delay(1000)
                        if (sessionStore.load()?.stopped == true) return@launch
                    }
                } else {
                    val saved = intent ?: return@launch
                    val revived = saved.copy(stopped = false)

                    intent = revived
                    sessionStore.save(revived)
                    home.update { it.copy(connected = true) }

                    line("Reconnected after 4 s of downtime", TerminalLine.Tone.OK)
                    terminal.update { it.copy(phase = WatchPhase.RECOVERED) }
                    failures = 0
                }
            }
        }
    }

    fun killTunnel() {
        unbindFromNetwork()
        downPending = true

        viewModelScope.launch(Dispatchers.IO) {
            engine.stop()
        }
    }

    fun dismissAndGoHome() {
        screen.value = Screen.HOME
    }

    // ---- Navigation ---------------------------------------------------------

    fun goSettings() {
        screen.value = Screen.SETTINGS
    }

    fun goAbout() {
        screen.value = Screen.ABOUT
    }

    fun onSystemBack() {
        when (screen.value) {
            Screen.TERMINAL -> dismissAndGoHome()
            else -> screen.value = Screen.HOME
        }
    }

    // ---- Settings -----------------------------------------------------------

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().setThemeMode(mode)
            }.onFailure {
                android.util.Log.w("vpnbox", "could not persist theme mode", it)
            }
        }
    }

    fun setDynamicColor(on: Boolean) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().setDynamicColor(on)
            }.onFailure {
                android.util.Log.w("vpnbox", "could not persist dynamic color", it)
            }
        }
    }

    // ---- Engine Events ------------------------------------------------------

    private fun consumeEngineEvent(e: EngineEvent) {
        when (e) {
            is EngineEvent.Log -> {
                val tone = when {
                    e.line.startsWith("×") -> TerminalLine.Tone.ERR
                    VpnGate.OK_MARKER in e.line -> TerminalLine.Tone.OK
                    else -> TerminalLine.Tone.PLAIN
                }

                terminal.update {
                    it.copy(lines = it.lines + TerminalLine(e.line, tone))
                }

                if (tone == TerminalLine.Tone.ERR) {
                    terminal.update { it.copy(phase = WatchPhase.KILL) }
                }
            }

            is EngineEvent.HandshakeFailed -> {
                for (l in e.excerpt) {
                    line("× $l", TerminalLine.Tone.ERR)
                }
                handshakeWaiter?.complete(false)
            }

            EngineEvent.HandshakeOk -> {
                hasHandshake = true
                handshakeWaiter?.complete(true)
            }

            is EngineEvent.Down -> {
                unbindFromNetwork()
                downPending = true
            }
        }
    }

    // ---- Disconnect ---------------------------------------------------------

    fun consentDeclined() {
        home.update {
            it.copy(
                connected = false,
                message = "VPN permission was declined — allow it to connect"
            )
        }
    }

    fun disconnect() {
        watchJob?.cancel()
        connectJob?.cancel()
        handshakeWaiter?.complete(false)
        handshakeWaiter = null

        engine.stop()
        unbindFromNetwork()
        hasHandshake = false

        val saved = intent

        if (saved != null) {
            sessionStore.stop(saved)
            intent = saved.copy(
                stopped = true,
                stoppedAt = System.currentTimeMillis()
            )
        }

        home.update {
            it.copy(connected = false, message = "Disconnected")
        }

        screen.value = Screen.HOME
        rebuildCountries()
    }
}
