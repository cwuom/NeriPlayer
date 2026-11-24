package moe.ouom.neriplayer.network.proxy

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.util/DynamicProxySelector
 * Updated: 2026/3/23
 */


import java.io.IOException
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class NetworkProxyEndpoint private constructor(val type: Proxy.Type, val host: String, val port: Int) {
    val uri: String
        get() = URI(if (type == Proxy.Type.HTTP) "http" else "socks5", null, host, port, null, null, null).toASCIIString()

    fun toProxy(): Proxy = Proxy(type, InetSocketAddress.createUnresolved(host.removeSurrounding("[", "]"), port))

    companion object {
        fun fromFields(type: Proxy.Type, host: String, port: String): NetworkProxyEndpoint? {
            if (type == Proxy.Type.DIRECT) return null
            val portNumber = port.toIntOrNull() ?: return null
            val input = host.trim()
            val uri = runCatching {
                val asciiHost = if (':' in input) input else IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES)
                NetworkProxyEndpoint(type, asciiHost, portNumber).uri
            }.getOrNull() ?: return null
            return parse(uri)
        }

        fun parse(raw: String): NetworkProxyEndpoint? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            val type = when (uri.scheme?.lowercase()) {
                "http" -> Proxy.Type.HTTP
                "socks5" -> Proxy.Type.SOCKS
                else -> return null
            }
            val host = uri.host ?: return null
            if (uri.rawUserInfo != null || uri.port !in 1..65535 ||
                !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null
            ) return null
            return NetworkProxyEndpoint(type, host.removeSurrounding("[", "]"), uri.port)
        }
    }
}

object DynamicProxySelector : ProxySelector() {
    // null follows Android, NO_PROXY connects directly, and a concrete proxy overrides Android
    @Volatile
    var overrideProxy: Proxy? = Proxy.NO_PROXY

    private val systemProxyHosts = ConcurrentHashMap.newKeySet<String>()

    internal fun isProxyHost(hostname: String): Boolean {
        val host = hostname.trimEnd('.').lowercase(Locale.ROOT)
        val custom = (overrideProxy?.address() as? InetSocketAddress)?.hostString
        return host.equals(custom?.trimEnd('.'), ignoreCase = true) || host in systemProxyHosts
    }

    private fun systemDefault(): ProxySelector? {
        val current = getDefault()
        return if (current === this) null else current
    }

    override fun select(uri: URI?): List<Proxy> {
        if (uri == null) return listOf(Proxy.NO_PROXY)
        overrideProxy?.let { return listOf(it) }
        val proxies = systemDefault()?.select(uri).takeUnless { it.isNullOrEmpty() } ?: return listOf(Proxy.NO_PROXY)
        for (proxy in proxies) {
            val address = proxy.address() as? InetSocketAddress ?: continue
            systemProxyHosts.add(address.hostString.trimEnd('.').lowercase(Locale.ROOT))
        }
        return proxies
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        if (overrideProxy == null) systemDefault()?.connectFailed(uri, sa, ioe)
    }
}

