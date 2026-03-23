package moe.ouom.neriplayer.util

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
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * A ProxySelector that can be toggled at runtime to bypass the system proxy.
 * When [bypassProxy] is true, all requests use Proxy.NO_PROXY.
 * When false, it delegates to the system default ProxySelector.
 */
object DynamicProxySelector : ProxySelector() {
    @Volatile
    var bypassProxy: Boolean = true
    private fun systemDefault(): ProxySelector? {
        val current = getDefault()
        return if (current === this) null else current
    }

    override fun select(uri: URI?): List<Proxy> {
        if (uri == null) return listOf(Proxy.NO_PROXY)
        return if (bypassProxy) listOf(Proxy.NO_PROXY)
        else systemDefault()?.select(uri).takeUnless { it.isNullOrEmpty() } ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        systemDefault()?.connectFailed(uri, sa, ioe)
    }
}


