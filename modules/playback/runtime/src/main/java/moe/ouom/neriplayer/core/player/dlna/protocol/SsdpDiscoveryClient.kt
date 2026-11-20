package moe.ouom.neriplayer.core.player.dlna.protocol

import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Native Kotlin SSDP M-SEARCH discovery + manual device HTTP description probe.
 * Uses Proxy.NO_PROXY so LAN UPnP HTTP fetches never route through proxy servers.
 */
class SsdpDiscoveryClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(2_500L, TimeUnit.MILLISECONDS)
        .readTimeout(3_500L, TimeUnit.MILLISECONDS)
        .build()
) {
    companion object {
        const val SSDP_MULTICAST_HOST = "239.255.255.250"
        const val SSDP_PORT = 1900
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

    fun scanOnce(
        knownDevices: List<DlnaDevice> = emptyList(),
        listenWindowMs: Long = 2_800L,
        onLog: (String) -> Unit = {}
    ): List<DlnaDevice> {
        val localAddresses = activeLanIpv4Addresses()
        val locations = LinkedHashSet<String>()
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
                            if (locations.add(location)) {
                                val usnUdn = headers["usn"]?.substringBefore("::")?.trim().orEmpty()
                                fetchDeviceDescription(location, usnUdn, isManual = false, onLog = onLog)
                                    ?.let { dev -> discovered[dev.udn] = dev }
                            }
                        }
                    }
                }.onFailure { err ->
                    onLog("SSDP socket failed on ${localIp.hostAddress}: ${err.message}")
                }
            }
        }

        // Direct unicast probe for known/manual devices that didn't respond to multicast
        for (known in knownDevices) {
            if (discovered.containsKey(known.udn)) continue
            val revived = fetchDeviceDescription(known.location, known.udn, isManual = known.isManual, onLog = onLog)
            if (revived != null) {
                discovered[revived.udn] = revived
            }
        }
        return discovered.values.toList()
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
        onLog: (String) -> Unit = {}
    ): DlnaDevice? {
        val body = httpGetText(locationUrl) ?: run {
            onLog("Description GET failed: $locationUrl")
            return null
        }
        val parsed = UpnpXmlParser.parseDeviceDescription(body, locationUrl) ?: run {
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
                val bytes = response.body.bytes()
                if (bytes.size > 512 * 1024) return null
                String(bytes, Charsets.UTF_8)
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
