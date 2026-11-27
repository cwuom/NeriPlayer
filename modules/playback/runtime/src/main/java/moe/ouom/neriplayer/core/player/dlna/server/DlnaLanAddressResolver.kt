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

    internal fun isNonLanTunnelOrCellularInterface(ifaceName: String): Boolean {
        val name = ifaceName.lowercase()
        return name.startsWith("tun") ||
            name.startsWith("ppp") ||
            name.startsWith("rmnet") ||
            name.startsWith("ccmni") ||
            name.startsWith("clat") ||
            name.startsWith("v4-") ||
            name.startsWith("epdg") ||
            name.startsWith("wg") ||
            name.startsWith("dummy")
    }

    private fun lanInterfacePriority(ifaceName: String): Int {
        val name = ifaceName.lowercase()
        return if (
            name.startsWith("wlan") ||
            name.startsWith("swlan") ||
            name.startsWith("eth") ||
            name.startsWith("en") ||
            name.startsWith("ap")
        ) {
            0
        } else {
            1
        }
    }

    internal data class LanSubnetCandidate(
        val ifaceName: String,
        val localIpv4: ByteArray,
        val hostAddress: String,
        val prefixLength: Int
    )

    internal fun selectSubnetMatchedLanIpv4(
        targetIpv4: ByteArray,
        candidates: Iterable<LanSubnetCandidate>
    ): String? {
        if (targetIpv4.size != 4) return null
        var bestHost: String? = null
        var bestPriority = Int.MAX_VALUE
        var bestPrefix = -1
        for (item in candidates) {
            if (isNonLanTunnelOrCellularInterface(item.ifaceName)) continue
            val prefix = item.prefixLength.coerceIn(1, 32)
            if (!sameIpv4Subnet(item.localIpv4, targetIpv4, prefix)) continue
            val prio = lanInterfacePriority(item.ifaceName)
            if (prio < bestPriority || (prio == bestPriority && prefix > bestPrefix)) {
                bestPriority = prio
                bestPrefix = prefix
                bestHost = item.hostAddress
            }
        }
        return bestHost
    }

    fun resolveLanIpv4ForTarget(targetHost: String?): String? {
        if (!targetHost.isNullOrBlank()) {
            val targetAddr = runCatching { InetAddress.getByName(targetHost) }.getOrNull()
            if (targetAddr is Inet4Address) {
                val subnetMatched = runCatching {
                    val candidates = mutableListOf<LanSubnetCandidate>()
                    val ifaces = NetworkInterface.getNetworkInterfaces()
                    while (ifaces != null && ifaces.hasMoreElements()) {
                        val iface = ifaces.nextElement()
                        val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
                        if (!up || isNonLanTunnelOrCellularInterface(iface.name)) continue
                        for (ifAddr in iface.interfaceAddresses) {
                            val ipv4 = ifAddr.address as? Inet4Address ?: continue
                            if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) continue
                            val host = ipv4.hostAddress ?: continue
                            candidates += LanSubnetCandidate(
                                ifaceName = iface.name,
                                localIpv4 = ipv4.address,
                                hostAddress = host,
                                prefixLength = ifAddr.networkPrefixLength.toInt()
                            )
                        }
                    }
                    selectSubnetMatchedLanIpv4(targetAddr.address, candidates)
                }.getOrNull()
                if (!subnetMatched.isNullOrBlank()) return subnetMatched
            }
            val probed = runCatching {
                DatagramSocket().use { socket ->
                    val peer = targetAddr ?: InetAddress.getByName(targetHost)
                    socket.connect(peer, 1900)
                    (socket.localAddress as? Inet4Address)
                        ?.takeIf { local ->
                            !local.isLoopbackAddress &&
                                !local.isLinkLocalAddress &&
                                isAllowedLanLocalAddress(local)
                        }
                        ?.hostAddress
                }
            }.getOrNull()
            if (!probed.isNullOrBlank()) return probed
        }
        return listActiveLanIpv4().firstOrNull()
    }

    private fun isAllowedLanLocalAddress(address: Inet4Address): Boolean {
        val iface = runCatching { NetworkInterface.getByInetAddress(address) }.getOrNull()
            ?: return false
        val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
        return up && !isNonLanTunnelOrCellularInterface(iface.name)
    }

    fun listActiveLanIpv4(): List<String> {
        val candidates = mutableListOf<Pair<Int, String>>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        for (iface in ifaces) {
            val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!up) continue
            if (isNonLanTunnelOrCellularInterface(iface.name)) continue
            val priority = lanInterfacePriority(iface.name)
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

    internal fun sameIpv4Subnet(a: ByteArray, b: ByteArray, prefixBits: Int): Boolean {
        if (a.size != 4 || b.size != 4 || prefixBits <= 0) return false
        val mask = if (prefixBits >= 32) -1 else (-1 shl (32 - prefixBits))
        val intA = ((a[0].toInt() and 0xFF) shl 24) or ((a[1].toInt() and 0xFF) shl 16) or
            ((a[2].toInt() and 0xFF) shl 8) or (a[3].toInt() and 0xFF)
        val intB = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        return (intA and mask) == (intB and mask)
    }
}
