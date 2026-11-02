package io.github.sajadinmaker.vpnbox

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The screens the app can show, in navigation order. */
enum class Screen { HOME, TERMINAL, SETTINGS, ABOUT }

/** The chips row: connect → watch → kill → recovered (mirrors the reel beats). */
enum class WatchPhase { CONNECT, WATCH, KILL, RECOVERED, FAILED }

data class CountryPick(val cc: String, val country: String, val servers: Int)

data class HomeUi(
    val connected: Boolean = false,
    val servers: Int = 0,
    val countries: Int = 0,
    val cacheAgeMin: Long? = null,
    val geoLabel: String = "",
    val message: String = "",
    /** The last session's server choice — feeds the quick-reconnect row.
     *  [lastCountry] carries the cc that selectServers matches on;
     *  [lastCountryLabel] is its human-readable display name. */
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
    private val filesDir: File = app.getExternalFilesDir(null) ?: app.filesDir

    private val cacheStore = CacheStore(File(filesDir, "servers.json"))
    private val sessionStore = SessionStore(File(filesDir, "session.json"))

    val screen = MutableStateFlow(Screen.HOME)
    val home = MutableStateFlow(HomeUi())
    val terminal = MutableStateFlow(TerminalUi())
    val countries = MutableStateFlow<List<CountryPick>>(emptyList())
    val settings = MutableStateFlow(AppUiSettings())

    init {
        // settings read is fault-tolerant (observeSettings falls back to
        // defaults on a damaged store); persistence failures are logged
        viewModelScope.launch {
            getApplication<Application>().observeSettings().collect { parsed ->
                settings.value = parsed
            }
        }
    }

    private var cache: ServerCache? = null
    private var intent: SessionIntent? = null
    private var connectJob: Job? = null
    private var watchJob: Job? = null
    private var hasHandshake = false
    private var downPending = false
    private val cycleMax = 3

    private var started = false

    fun start() {
        // idempotent: Activity configuration changes (rotation, dark-mode
        // toggle) recreate the composition but not this ViewModel — starting
        // twice would attach a second event collector and duplicate every
        // engine event in the terminal log
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

    /**
     * Relaunch projection: the engine service keeps running when the UI
     * process dies; on relaunch we re-derive truth from the engine itself
     * (like the CLI, which always asks the system for the daemon's state).
     */
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
                        TerminalLine.Tone.OK,
                    ),
                ),
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
                lastCountryLabel = label,
            )
        }
    }

    private fun refreshGeoLabel() {
        viewModelScope.launch(Dispatchers.IO) {
            bindUntilVpnReady()             // probe the actual egress, not the
            val g = VpnGate.fetchGeo() ?: return@launch

            val where = listOf(g["city"], g["regionName"], g["country"])
                .filter { !it.isNullOrBlank() }.joinToString(", ")
            home.update {
                it.copy(geoLabel = "${g["query"]}  ·  $where")
            }
        }
    }

    /**
     * While a tunnel is up, the exit-IP probe must measure the tunnel's
     * egress — routing through the VPN network, not whatever the system
     * considers the device default. (Matches the automatic default on most
     * devices; definitive on network stacks that keep app sockets on the
     * underlying network.)
     *
     * Returns true when the process actually got bound: right after the
     * handshake the VPN network may still be CONNECTING and reject the bind
     * — callers that must not measure the wrong egress use
     * [bindUntilVpnReady] to retry.
     */
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

    /** Bind for the exit-IP probe, retrying briefly while the VPN network is
     * still coming up (an un-bound probe measures the underlying network). */
    private suspend fun bindUntilVpnReady(): Boolean {
        repeat(10) {
            if (bindToVpnNetworkIfAny()) return true
            delay(500)
        }
        return bindToVpnNetworkIfAny()
    }

    /** Reset the probe binding to the system default. */
    private fun unbindFromNetwork() {
        runCatching {
            val cm = getApplication<Application>()
                .getSystemService(ConnectivityManager::class.java) ?: return
            cm.bindProcessToNetwork(null)
        }
    }

    // ---- refresh ------------------------------------------------------------

    fun refresh() {
        connectJob?.cancel()
        // blocking network I/O — keep it off the main thread
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
                    it.copy(message = "Updated · ${servers.size} servers · " +
                        "${servers.map { s -> s.cc }.distinct().size} countries · " +
                        "$responsive responding now")
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

    // ---- connect ------------------------------------------------------------

    fun connect(country: String?, city: String?) {
        // double-tap guard: never stack a second connect while one is on screen
        if (screen.value == Screen.TERMINAL && connectJob?.isActive == true) return
        screen.value = Screen.TERMINAL
        terminal.value = TerminalUi(phase = WatchPhase.CONNECT)
        intent = null
        downPending = false   // a stale Down from the previous session must not
        connectJob?.cancel()  // arm this attempt's watchdog
        // the whole connect path (fetch, parse, enrich, engine delays) is
        // blocking/network work — off the main thread, like launch() in the CLI
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val ping = 10
                val exit = checkPingSettings(ping, 60)
                validateArgs(skip = 0, index = null, maxTries = 3,
                             ping = ping, pingExit = exit)
                var c = cache ?: cacheStore.load()
                if (c == null ||
                    System.currentTimeMillis() - c.updated > 30 * 60_000L) {
                    if (c != null) {
                        line("Server list is missing or outdated — downloading a fresh copy…",
                             TerminalLine.Tone.WARN)
                    }
                    val text = VpnGate.fetchListText()
                    val servers = VpnGateParser.parse(text)
                    VpnGate.enrichCity(servers)
                    c = ServerCache(System.currentTimeMillis(), servers)
                    cacheStore.save(c)
                    cache = c
                    rebuildCountries()
                }
                val servers0 = c?.servers ?: emptyList()
                if (servers0.isEmpty()) {
                    line("Refresh completed, but the server list is unavailable",
                         TerminalLine.Tone.ERR)
                    terminal.update { it.copy(phase = WatchPhase.FAILED) }
                    return@launch
                }
                val (sel, why) = selectServers(servers0, country, city)
                if (sel.isEmpty()) {
                    line(why.ifBlank { "No matching servers — try a different country." },
                         TerminalLine.Tone.ERR)
                    terminal.update { it.copy(phase = WatchPhase.FAILED) }
                    return@launch
                }
                val triesN = minOf(sel.size, 3)
                for ((i, r) in sel.take(triesN).withIndex()) {
                    attemptLine(i, triesN, r)
                    if (engine.start(r, keepalive = Pair(ping, exit)).isSuccess) {
                        succeed(r, country, city, exit)
                        startWatchLoop(country, city, exit)
                        return@launch
                    }
                }
                line("All attempts failed. The server list may be stale — " +
                     "refresh the list and try again", TerminalLine.Tone.ERR)
                terminal.update { it.copy(phase = WatchPhase.FAILED) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserError) {
                line("× ${e.message}", TerminalLine.Tone.ERR)
                terminal.update { it.copy(phase = WatchPhase.FAILED) }
            } catch (e: Exception) {
                line("× Could not connect: ${e.message ?: "network error"}",
                     TerminalLine.Tone.ERR)
                terminal.update { it.copy(phase = WatchPhase.FAILED) }
            }
        }
    }

    private suspend fun attemptLine(i: Int, total: Int, r: Server) {        terminal.update {
            it.copy(lines = it.lines + TerminalLine(
                "Attempt ${i + 1}/$total · ${r.country} · ${r.ip} · " +
                    "${r.proto.uppercase()}/${r.port.ifBlank { "?" }}" +
                    (if (r.ping > 0) " · ${r.ping} ms" else ""),
                TerminalLine.Tone.ACCENT))
        }
    }

    private suspend fun succeed(
        r: Server,
        country: String?,
        city: String?,
        exit: Int,
    ) {
        hasHandshake = true
        val fresh = SessionIntent(
            country = country ?: r.country, city = city,
            proto = r.proto, ping = 10, pingExit = exit,
        )
        intent = fresh
        sessionStore.save(fresh)
        home.update {
            it.copy(
                connected = true,
                lastCountry = fresh.country,
                lastCountryLabel = r.country,
                lastCity = fresh.city,
            )
        }
        line("Connected · ${r.country}" +
             (if (r.city.isNotBlank()) " · ${r.city}" else ""),
             TerminalLine.Tone.OK)
        bindUntilVpnReady()
        VpnGate.fetchGeo()?.let { geo ->
            val where = listOf(geo["city"], geo["regionName"], geo["country"])
                .filter { !it.isNullOrBlank() }.joinToString(", ")
            line("Exit IP: ${geo["query"]}", TerminalLine.Tone.PLAIN)
            line("Location: $where · ISP ${geo["isp"]?.ifBlank { "?" }}",
                 TerminalLine.Tone.PLAIN)
            // CLI parity: the exit must be in the country the user picked —
            // any mismatch means full-tunnel routing is not active
            val geoCc = geo["countryCode"]?.uppercase()
            if (!geoCc.isNullOrBlank() && geoCc != r.cc.uppercase()) {
                line("Warning: exit IP country ($geoCc) differs from the " +
                     "selected server (${r.cc.uppercase()}) — full-tunnel " +
                     "routing may not be active", TerminalLine.Tone.WARN)
            }
        }
        line("You can disconnect from the home screen", TerminalLine.Tone.PLAIN)
        terminal.update { it.copy(phase = WatchPhase.WATCH) }
        refreshGeoLabel()
    }

    private fun line(text: String, tone: TerminalLine.Tone) {
        terminal.update { it.copy(lines = it.lines + TerminalLine(text, tone)) }
    }

    // ---- watch --------------------------------------------------------------

    private fun startWatchLoop(country: String?, city: String?, exit: Int) {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            var failures = 0
            while (true) {
                delay(1500)
                if (!downPending) {
                    if (sessionStore.load()?.stopped == true) {
                        return@launch          // disconnect while we were watching
                    }
                    continue                   // tunnel still up — keep watching
                }
                downPending = false
                line("Tunnel down", TerminalLine.Tone.WARN)
                terminal.update { it.copy(phase = WatchPhase.KILL) }
                val pending = intent ?: return@launch
                if (pending.stopped) {        // user disconnect beat the watchdog
                    line("Disconnect was requested — staying offline",
                         TerminalLine.Tone.PLAIN)
                    hasHandshake = false
                    home.update { it.copy(connected = false) }
                    return@launch
                }
                line("Reconnecting " +
                     "(attempt ${failures + 1} of $cycleMax)", TerminalLine.Tone.WARN)
                val (sel, _) = selectServers(cache?.servers.orEmpty(), country, city)
                var recovered: Server? = null
                for (r in sel.take(3)) {
                    // the killed network may still be the process binding —
                    // the core's transport sockets would die with ENETUNREACH
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
                        line("Reconnection failed after $failures attempts. " +
                             "The tunnel is down — refresh the list and try again",
                             TerminalLine.Tone.ERR)
                        terminal.update { it.copy(phase = WatchPhase.FAILED) }
                        hasHandshake = false
                        home.update { it.copy(connected = false) }
                        return@launch
                    }
                    val wait = backoffSeconds(failures)
                    line("Reconnection attempt $failures of $cycleMax failed. " +
                         "Retrying in $wait s", TerminalLine.Tone.ERR)
                    val until = System.currentTimeMillis() + wait * 1000
                    while (System.currentTimeMillis() < until) {
                        delay(1000)
                        if (sessionStore.load()?.stopped == true) return@launch
                    }
                } else {
                    // reviving a session needs a non-null intent; the guard
                    // above (`pending = intent ?: return@launch`) guarantees it
                    val saved = intent
                    if (saved == null) return@launch
                    val revived = saved.copy(stopped = false)
                    intent = revived
                    sessionStore.save(revived)
                    home.update { it.copy(connected = true) }
                    line("Reconnected " +
                         "after 4 s of downtime", TerminalLine.Tone.OK)
                    terminal.update { it.copy(phase = WatchPhase.RECOVERED) }
                    failures = 0
                }
            }
        }
    }

    /** "Kill tunnel" chip — emulate the CLI's one-shot teardown: the core is
     * asked to exit; its Down event arms the watchdog exactly like a real
     * drop would. */
    fun killTunnel() {
        unbindFromNetwork()   // don't leave the process on the dying network
        downPending = true
        viewModelScope.launch(Dispatchers.IO) { engine.stop() }
    }

    fun dismissAndGoHome() {
        screen.value = Screen.HOME
    }

    // ---- navigation ---------------------------------------------------------

    fun goSettings() {
        screen.value = Screen.SETTINGS
    }

    fun goAbout() {
        screen.value = Screen.ABOUT
    }

    /** System back: terminal returns to home, deeper screens go back. */
    fun onSystemBack() {
        when (screen.value) {
            Screen.TERMINAL -> dismissAndGoHome()
            else -> screen.value = Screen.HOME
        }
    }

    // ---- settings -----------------------------------------------------------

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            runCatching { getApplication<Application>().setThemeMode(mode) }
                .onFailure { android.util.Log.w("vpnbox", "could not persist theme mode", it) }
        }
    }

    fun setDynamicColor(on: Boolean) {
        viewModelScope.launch {
            runCatching { getApplication<Application>().setDynamicColor(on) }
                .onFailure { android.util.Log.w("vpnbox", "could not persist dynamic color", it) }
        }
    }

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
                for (l in e.excerpt) line("× $l", TerminalLine.Tone.ERR)
            }
            EngineEvent.HandshakeOk -> Unit  // succeed() renders the OK block
            is EngineEvent.Down -> {
                // the tunnel network is gone — unbind so the core's next
                // transport sockets don't open on a dead network id
                unbindFromNetwork()
                downPending = true           // watchdog consumes it
            }
        }
    }

    // ---- disconnect ---------------------------------------------------------

    /** User pressed "Cancel" on the system VPN consent dialog. */
    fun consentDeclined() {
        home.update {
            it.copy(
                connected = false,
                message = "VPN permission was declined — allow it to connect",
            )
        }
    }

    fun disconnect() {
        watchJob?.cancel()
        connectJob?.cancel()
        engine.stop()
        unbindFromNetwork()   // the tunnel network is gone; don't stay bound
        hasHandshake = false
        // intent first: the teardown must never race the watchdog into
        // resurrecting the tunnel the user just asked to stop
        val saved = intent
        if (saved != null) {
            sessionStore.stop(saved)
            intent = saved.copy(stopped = true, stoppedAt = System.currentTimeMillis())
        }
        home.update {
            it.copy(
                connected = false,
                message = "Disconnected",
            )
        }
        screen.value = Screen.HOME
        rebuildCountries()
    }
}
