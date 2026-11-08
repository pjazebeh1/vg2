
package io.github.sajadinmaker.vpnbox.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** VPN Gate source: fetch + parse. */
object VpnGate {

    val APP: String = "vpnbox"
    val APP_VERSION: String = "1.0.0-beta5-android"
    val UA: String = "$APP/$APP_VERSION (android; free public VPN client)"

    // GitHub mirror: standard VPN Gate CSV
    const val GITHUB_SOURCE =
        "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv"

    // GitHub-only server list
    const val DEFAULT_SOURCE = GITHUB_SOURCE
    const val MIRROR_SOURCE = GITHUB_SOURCE

    const val HOSTNAME_HEADER = "#HostName"

    const val PING_DEFAULT = 10
    const val PING_EXIT_DEFAULT = 60
    const val OK_MARKER = "Initialization Sequence Completed"

    val STRIP_PREFIXES = listOf(
        "win-sys", "route-method", "route-delay", "dhcp-renew",
        "dhcp-release", "show-net-up", "ip-win32", "register-dns",
        "tun-ipv6",
    )

    val EXTRA_CONFIG =
        "### vpnbox additions (below are ours; keep them last)\n" +
        "script-security 2\n" +
        "data-ciphers AES-256-GCM:AES-128-GCM:AES-192-GCM:AES-256-CBC:AES-128-CBC:CHACHA20-POLY1305\n" +
        "connect-retry 2  8\n" +
        "connect-retry-max 3\n" +
        "pull-filter ignore \"bypass-dhcp\"\n" +
        "<auth-user-pass>\n" +
        "vpn\n" +
        "vpn\n" +
        "</auth-user-pass>\n" +
        "redirect-gateway def1\n" +
        "block-ipv6\n"

    val FAIL_MARKERS = listOf(
        "Options error", "AUTH_FAILED", "Cannot resolve host",
        "Connection refused", "Connection timed out",
        "Connection reset by peer", "No route to host",
        "Network is unreachable", "Error opening configuration file",
        "Failed to establish", "TUNSETIFF failed",
        "All connections have been", "Exiting due to fatal error",
    )

    class VpnGateError(msg: String) : RuntimeException(msg)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Return GitHub server list URL only.
     * Other server-list sources are disabled.
     */
    fun sourceUrls(vpngateApi: String? = null): List<String> =
        listOf(GITHUB_SOURCE)

    fun httpGet(
        url: String,
        timeoutMs: Int = 45_000,
        data: ByteArray? = null,
        contentType: String? = null,
    ): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (data == null) "GET" else "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)

            if (data != null) {
                doOutput = true
                setRequestProperty(
                    "Content-Type",
                    contentType ?: "application/json"
                )
            }
        }

        try {
            if (data != null) {
                conn.outputStream.use { it.write(data) }
            }

            val code = conn.responseCode

            if (code !in 200..299) {
                throw VpnGateError("HTTP error $code")
            }

            return conn.inputStream.use { it.readBytes() }

        } catch (e: VpnGateError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VpnGateError(
                "network error: ${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Fetch server list from GitHub only.
     */
    fun fetchListText(urls: List<String> = sourceUrls()): String {
        var lastErr: Exception? = null

        // Always use GitHub source, ignoring other URLs.
        for (url in sourceUrls()) {
            try {
                val text = httpGet(url).toString(Charsets.UTF_8)

                if (HOSTNAME_HEADER in text) {
                    return text
                }

                android.util.Log.e(
                    "vpnbox",
                    "Invalid VPN Gate response from $url"
                )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("vpnbox", "fetch $url failed", e)
                lastErr = e
            }
        }

        throw VpnGateError(
            "could not reach GitHub VPN server list " +
                "(${lastErr?.javaClass?.simpleName}: ${lastErr?.message}). " +
                "Check your connection and try again."
        )
    }

    /** Public-IP lookup, best-effort. */
    fun fetchGeo(): Map<String, String>? {
        return try {
            val body = httpGet(
                "http://ip-api.com/json?fields=status,country,countryCode," +
                    "regionName,city,isp,query",
                timeoutMs = 8_000,
            ).toString(Charsets.UTF_8)

            val obj = json.parseToJsonElement(body).jsonObject

            if (obj["status"]?.jsonPrimitive?.content != "success") {
                return null
            }

            val out = mutableMapOf<String, String>()

            for (k in listOf(
                "query", "country", "countryCode",
                "regionName", "city", "isp"
            )) {
                obj[k]?.let {
                    out[k] = it.jsonPrimitive.content
                }
            }

            out

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort IP geolocation enrichment. */
    fun enrichCity(servers: List<Server>, top: Int = 300): Int {
        val targets = servers.sortedByDescending { it.score }.take(top)

        if (targets.isEmpty()) return 0

        var ok = 0

        for (ci in targets.indices step 100) {
            val chunk = targets.slice(
                ci until minOf(ci + 100, targets.size)
            )

            if (chunk.size > 100) break

            try {
                val payload = JsonArray(
                    chunk.map {
                        kotlinx.serialization.json.JsonPrimitive(it.ip)
                    }
                ).toString().toByteArray()

                val body = httpGet(
                    "http://ip-api.com/batch?fields=status,country,countryCode," +
                        "regionName,city,isp,lat,lon,query",
                    timeoutMs = 20_000,
                    data = payload,
                ).toString(Charsets.UTF_8)

                val byIp = json.parseToJsonElement(body)
                    .jsonArray
                    .filterIsInstance<JsonObject>()
                    .associateBy {
                        it["query"]?.jsonPrimitive?.content ?: ""
                    }

                for (t in chunk) {
                    val g = byIp[t.ip] ?: continue

                    if (g["status"]?.jsonPrimitive?.content != "success") {
                        continue
                    }

                    val city = g["city"]?.jsonPrimitive?.content ?: ""

                    t.city = city
                    t.isp = g["isp"]?.jsonPrimitive?.content ?: ""
                    t.region = g["regionName"]?.jsonPrimitive?.content ?: ""
                    t.lat = g["lat"]?.jsonPrimitive?.doubleOrNull
                    t.lon = g["lon"]?.jsonPrimitive?.doubleOrNull

                    if (city.isNotEmpty()) ok++
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
        }

        return ok
    }
}
