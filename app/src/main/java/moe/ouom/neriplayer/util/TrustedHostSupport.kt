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
 */

import java.util.Locale

fun normalizeTrustedHost(host: String?): String {
    return host
        ?.trim()
        ?.trim('.')
        ?.lowercase(Locale.US)
        .orEmpty()
}

fun hostMatchesDomain(host: String?, domain: String): Boolean {
    val normalizedHost = normalizeTrustedHost(host)
    val normalizedDomain = normalizeTrustedHost(domain)
    if (normalizedHost.isBlank() || normalizedDomain.isBlank()) {
        return false
    }
    return normalizedHost == normalizedDomain || normalizedHost.endsWith(".$normalizedDomain")
}

fun hostMatchesAnyDomain(host: String?, domains: Iterable<String>): Boolean {
    val normalizedHost = normalizeTrustedHost(host)
    if (normalizedHost.isBlank()) {
        return false
    }
    return domains.any { domain -> hostMatchesDomain(normalizedHost, domain) }
}
