package moe.ouom.neriplayer.core.player.dlna.server

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Resolves the local LAN IPv4 address on the same route/interface as a target renderer,
 * filtering out loopback, link-local 169.254.x.x, and VPN tunnel interfaces.
 */
object DlnaLanAddressResolver {

    fun resolveLanIpv4ForTarget(targetHost: String?): String? {
        if (!targetHost.isNullOrBlank()) {
            val probed = runCatching {
                DatagramSocket().use { socket ->
                    socket.connect(InetAddress.getByName(targetHost), 1900)
                    (socket.localAddress as? Inet4Address)
                        ?.takeIf { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        ?.hostAddress
                }
            }.getOrNull()
            if (!probed.isNullOrBlank()) return probed
        }
        return listActiveLanIpv4().firstOrNull()
    }

    fun listActiveLanIpv4(): List<String> {
        val candidates = mutableListOf<Pair<Int, String>>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        for (iface in ifaces) {
            val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!up) continue
            val name = iface.name.lowercase()
            if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet")) continue
            val priority = when {
                name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en") -> 0
                else -> 1
            }
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    val host = addr.hostAddress ?: continue
                    candidates += priority to host
                }
            }
        }
        return candidates.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun isPrivateOrLocalAddress(address: InetAddress?): Boolean {
        if (address == null) return false
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
    }

    fun isAuthorizedClient(address: InetAddress?, authorizedPeerHost: String?): Boolean {
        if (address == null) return false
        if (isPrivateOrLocalAddress(address)) return true
        val peerIp = address.hostAddress?.substringBefore('%')?.trim().orEmpty()
        val allowed = authorizedPeerHost?.substringBefore('%')?.trim().orEmpty()
        if (allowed.isNotEmpty() && peerIp == allowed) return true
        if (address is Inet4Address) {
            val clientBytes = address.address
            val localMatch = runCatching {
                NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty().any { iface ->
                    iface.interfaceAddresses.any { ifAddr ->
                        val local = ifAddr.address as? Inet4Address ?: return@any false
                        val prefix = ifAddr.networkPrefixLength.toInt().coerceIn(0, 32)
                        sameIpv4Subnet(local.address, clientBytes, prefix)
                    }
                }
            }.getOrDefault(false)
            if (localMatch) return true
        }
        return false
    }

    private fun sameIpv4Subnet(a: ByteArray, b: ByteArray, prefixBits: Int): Boolean {
        if (a.size != 4 || b.size != 4 || prefixBits <= 0) return false
        val mask = if (prefixBits >= 32) -1 else (-1 shl (32 - prefixBits))
        val intA = ((a[0].toInt() and 0xFF) shl 24) or ((a[1].toInt() and 0xFF) shl 16) or
            ((a[2].toInt() and 0xFF) shl 8) or (a[3].toInt() and 0xFF)
        val intB = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        return (intA and mask) == (intB and mask)
    }
}
