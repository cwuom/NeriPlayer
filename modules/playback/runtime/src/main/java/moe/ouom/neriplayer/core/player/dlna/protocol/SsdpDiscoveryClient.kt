package moe.ouom.neriplayer.core.player.dlna.protocol

import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Native Kotlin SSDP M-SEARCH discovery + manual device HTTP description probe.
 * Uses Proxy.NO_PROXY so LAN UPnP HTTP fetches never route through proxy servers.
 */
class SsdpDiscoveryClient(
    private val httpClient: OkHttpClient = buildDefaultHttpClient()
) {
    constructor(socketFactory: SocketFactory) : this(buildDefaultHttpClient(socketFactory))

    companion object {
        private fun buildDefaultHttpClient(
            socketFactory: SocketFactory = SocketFactory.getDefault()
        ): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(socketFactory)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(2_500L, TimeUnit.MILLISECONDS)
            .readTimeout(3_500L, TimeUnit.MILLISECONDS)
            .callTimeout(10_000L, TimeUnit.MILLISECONDS)
            .build()
        const val SSDP_MULTICAST_HOST = "239.255.255.250"
        const val SSDP_PORT = 1900
        private const val MAX_DESCRIPTION_BYTES = 512 * 1024
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "upnp:rootdevice"
        )
        private val MANUAL_DESC_PATHS = listOf(
            "/device.xml",
            "/description.xml",
            "/desc.xml",
            "/rootDesc.xml",
            "/dmr/description.xml"
        )
    }

    private data class SsdpCandidate(
        val location: String,
        val usnUdn: String,
        val sourceHost: String
    )

    fun scanOnce(
        knownDevices: List<DlnaDevice> = emptyList(),
        listenWindowMs: Long = 2_800L,
        onLog: (String) -> Unit = {}
    ): List<DlnaDevice> {
        val localAddresses = activeLanIpv4Addresses()
        val candidates = LinkedHashMap<String, SsdpCandidate>()
        val discovered = LinkedHashMap<String, DlnaDevice>()

        if (localAddresses.isEmpty()) {
            onLog("SSDP scan: no active IPv4 LAN interface")
        } else {
            val multicastTarget = InetAddress.getByName(SSDP_MULTICAST_HOST)
            val broadcastTarget = InetAddress.getByName("255.255.255.255")
            for (localIp in localAddresses) {
                runCatching {
                    MulticastSocket(InetSocketAddress(localIp, 0)).use { socket ->
                        socket.soTimeout = 250
                        socket.timeToLive = 2
                        socket.broadcast = true
                        runCatching {
                            socket.networkInterface = NetworkInterface.getByInetAddress(localIp)
                        }
                        for (st in SEARCH_TARGETS) {
                            val payload = buildMSearchRequest(st).toByteArray(Charsets.UTF_8)
                            repeat(2) {
                                runCatching {
                                    socket.send(DatagramPacket(payload, payload.size, multicastTarget, SSDP_PORT))
                                }
                                runCatching {
                                    socket.send(DatagramPacket(payload, payload.size, broadcastTarget, SSDP_PORT))
                                }
                            }
                        }
                        val deadline = System.currentTimeMillis() + listenWindowMs
                        val buf = ByteArray(4096)
                        while (System.currentTimeMillis() < deadline) {
                            val packet = DatagramPacket(buf, buf.size)
                            try {
                                socket.receive(packet)
                            } catch (_: SocketTimeoutException) {
                                continue
                            }
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val headers = parseSsdpHeaders(text)
                            val location = headers["location"]?.takeIf { it.isNotBlank() } ?: continue
                            val sourceHost = packet.address?.hostAddress.orEmpty()
                            if (!isValidDiscoveryLocation(location, sourceHost)) {
                                onLog("SSDP skipped mismatched LOCATION $location from $sourceHost")
                                continue
                            }
                            val usnUdn = headers["usn"]?.substringBefore("::")?.trim().orEmpty()
                            val candidateKey = "$location|$usnUdn"
                            if (usnUdn.isNotEmpty()) {
                                // Replace any earlier blank-UDN entry for the same LOCATION
                                candidates.remove("$location|")
                                candidates.putIfAbsent(candidateKey, SsdpCandidate(location, usnUdn, sourceHost))
                            } else if (candidates.keys.none { it.startsWith("$location|") }) {
                                candidates[candidateKey] = SsdpCandidate(location, "", sourceHost)
                            }
                        }
                    }
                }.onFailure { err ->
                    onLog("SSDP socket failed on ${localIp.hostAddress}: ${err.message}")
                }
            }
        }

        val descriptionCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        if (candidates.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(minOf(4, candidates.size))
            try {
                val tasks = candidates.values.map { cand ->
                    Callable {
                        fetchDeviceDescription(
                            locationUrl = cand.location,
                            fallbackUdn = cand.usnUdn,
                            isManual = false,
                            onLog = onLog,
                            sharedXmlCache = descriptionCache
                        )
                    }
                }
                for (future in pool.invokeAll(tasks)) {
                    val dev = runCatching { future.get() }.getOrNull() ?: continue
                    discovered[dev.udn] = dev
                }
            } finally {
                pool.shutdownNow()
            }
        }

        // Direct unicast probe for known/manual devices that didn't respond to multicast
        for (known in knownDevices) {
            if (discovered.containsKey(known.udn)) continue
            val revived = fetchDeviceDescription(
                locationUrl = known.location,
                fallbackUdn = known.udn,
                isManual = known.isManual,
                onLog = onLog,
                sharedXmlCache = descriptionCache
            )
            if (revived != null) {
                discovered[revived.udn] = revived
            }
        }
        return discovered.values.toList()
    }

    internal fun isValidDiscoveryLocation(locationUrl: String, sourceHost: String): Boolean {
        val uri = runCatching { URI(locationUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (sourceHost.isBlank()) return false
        val normalizedSource = sourceHost.trim().lowercase().substringBefore('%')
        return host == normalizedSource
    }

    fun probeManualDevice(input: String, onLog: (String) -> Unit = {}): Result<DlnaDevice> {
        val raw = input.trim()
        if (raw.isEmpty()) return Result.failure(IllegalArgumentException("Device address is empty"))
        val normalized = if (raw.contains("://")) raw else "http://$raw"
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid URL or IP: $input"))
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("Missing host in address: $input"))
        val candidates = if (uri.path.isNullOrBlank() || uri.path == "/") {
            val portSuffix = if (uri.port > 0) ":${uri.port}" else ""
            val scheme = uri.scheme?.lowercase() ?: "http"
            MANUAL_DESC_PATHS.map { path -> "$scheme://$host$portSuffix$path" } + listOf(
                "$scheme://$host:49152/description.xml",
                "$scheme://$host:49812/desc.xml"
            )
        } else {
            listOf(normalized)
        }

        for (candidate in candidates.distinct()) {
            val device = fetchDeviceDescription(candidate, fallbackUdn = "", isManual = true, onLog = onLog)
            if (device != null) {
                return Result.success(device)
            }
        }
        return Result.failure(IllegalStateException("No DLNA MediaRenderer found at $input"))
    }

    fun fetchDeviceDescription(
        locationUrl: String,
        fallbackUdn: String = "",
        isManual: Boolean = false,
        onLog: (String) -> Unit = {},
        sharedXmlCache: MutableMap<String, String>? = null
    ): DlnaDevice? {
        val body = sharedXmlCache?.get(locationUrl) ?: httpGetText(locationUrl)?.also {
            sharedXmlCache?.put(locationUrl, it)
        } ?: run {
            onLog("Description GET failed: $locationUrl")
            return null
        }
        val parsed = UpnpXmlParser.parseDeviceDescription(
            xml = body,
            locationUrl = locationUrl,
            preferredUdn = fallbackUdn
        ) ?: run {
            onLog("Description XML invalid: $locationUrl")
            return null
        }
        val avTransport = parsed.services.firstOrNull {
            it.serviceType.equals(UpnpXmlParser.SERVICE_AV_TRANSPORT, ignoreCase = true)
        } ?: return null // Must support AVTransport:1 for DLNA casting
        val rendering = parsed.services.firstOrNull {
            it.serviceType.equals(UpnpXmlParser.SERVICE_RENDERING_CONTROL, ignoreCase = true)
        }
        val connectionMgr = parsed.services.firstOrNull {
            it.serviceType.equals(UpnpXmlParser.SERVICE_CONNECTION_MANAGER, ignoreCase = true)
        }
        val host = runCatching { URI(locationUrl).host.orEmpty() }.getOrDefault("")
        val udn = parsed.udn.ifBlank { fallbackUdn.ifBlank { locationUrl } }
        return DlnaDevice(
            udn = udn,
            friendlyName = parsed.friendlyName.ifBlank { host.ifBlank { udn } },
            location = locationUrl,
            host = host,
            manufacturer = parsed.manufacturer,
            modelName = parsed.modelName,
            avTransportControlUrl = avTransport.controlUrl,
            avTransportEventSubUrl = avTransport.eventSubUrl,
            renderingControlUrl = rendering?.controlUrl.orEmpty(),
            renderingEventSubUrl = rendering?.eventSubUrl.orEmpty(),
            connectionManagerUrl = connectionMgr?.controlUrl.orEmpty(),
            supportsFlac = true,
            supportsWav = true,
            supportsSetNextUri = false,
            online = true,
            isManual = isManual
        )
    }

    private fun httpGetText(url: String): String? {
        return runCatching {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val stream = response.body.byteStream()
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = stream.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_DESCRIPTION_BYTES) return null
                    out.write(buf, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }

    internal fun buildMSearchRequest(st: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_MULTICAST_HOST:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $st\r\n\r\n"

    internal fun parseSsdpHeaders(response: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val lines = response.split("\r\n", "\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = value
        }
        return headers
    }

    private fun activeLanIpv4Addresses(): List<Inet4Address> {
        val result = mutableListOf<Inet4Address>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return result
        for (iface in interfaces) {
            val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!up) continue
            val name = iface.name.lowercase()
            if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy")) continue
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    result += addr
                }
            }
        }
        return result
    }
}
