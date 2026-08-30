package moe.ouom.neriplayer.core.player.resolver.youtube

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
 * File: moe.ouom.neriplayer.core.player/YouTubeGoogleVideoRangeSupport
 * Updated: 2026/3/23
 */


import android.net.Uri
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeGoogleVideoHost
import okhttp3.Request
import java.util.Locale

internal object YouTubeGoogleVideoRangeSupport {
    fun supportsSeekingWithoutUrlRefresh(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (!isYouTubeGoogleVideoHost(host)) {
            return false
        }
        val path = uri.path?.lowercase(Locale.US).orEmpty()
        if (host.startsWith("manifest.") || path.contains("/api/manifest/")) {
            return true
        }
        if (path.contains("/playlist/index.m3u8") || path.contains("/file/seg.ts")) {
            return true
        }
        val queryParameters = parseQueryParameters(uri.rawQuery)
        val hasResolvedThrottling = queryParameters["n"]?.isNotBlank() == true
        val hasResolvedSignature =
            queryParameters["sig"]?.isNotBlank() == true ||
                queryParameters["signature"]?.isNotBlank() == true
        return hasResolvedThrottling || hasResolvedSignature
    }

    fun shouldUseChunkedRange(uri: Uri): Boolean {
        return shouldUseChunkedRange(uri.toString())
    }

    fun shouldUseChunkedRange(url: String): Boolean {
        return isGoogleVideoDirectMediaUrl(url)
    }

    fun shouldUseChunkedRange(request: Request): Boolean {
        return shouldUseChunkedRange(request.url.toString())
    }

    fun shouldUseChunkedRangeForDownload(url: String): Boolean {
        return isGoogleVideoDirectMediaUrl(url)
    }

    fun shouldUseChunkedRangeForDownload(request: Request): Boolean {
        return shouldUseChunkedRangeForDownload(request.url.toString())
    }

    private fun isGoogleVideoDirectMediaUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.US)
            ?: return false
        if (!isYouTubeGoogleVideoHost(host)) {
            return false
        }
        val path = uri.path?.lowercase(Locale.US).orEmpty()
        if (host.startsWith("manifest.") || path.contains("/api/manifest/")) {
            return false
        }
        if (path.contains("/playlist/index.m3u8") || path.contains("/file/seg.ts")) {
            return false
        }
        val rawUrl = url.lowercase(Locale.US)
        return rawUrl.contains("source=youtube") || rawUrl.contains("/videoplayback")
    }

    private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) {
            return emptyMap()
        }
        return rawQuery
            .split('&')
            .mapNotNull { segment ->
                val rawKey = segment.substringBefore('=')
                if (rawKey.isBlank()) {
                    null
                } else {
                    val rawValue = segment.substringAfter('=', "")
                    runCatching {
                        java.net.URLDecoder.decode(rawKey, Charsets.UTF_8.name()) to
                            java.net.URLDecoder.decode(rawValue, Charsets.UTF_8.name())
                    }.getOrElse {
                        rawKey to rawValue
                    }
                }
            }
            .toMap()
    }
}
