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
import moe.ouom.neriplayer.core.player.engine.datasource.ResumableHttpRangeSupport
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeGoogleVideoHost
import okhttp3.Request
import java.io.IOException
import java.util.Locale

internal typealias ChunkLengthFallbackResult<T> =
    moe.ouom.neriplayer.core.player.engine.datasource.ChunkLengthFallbackResult<T>

internal typealias ChunkRequestIOException =
    moe.ouom.neriplayer.core.player.engine.datasource.ChunkRequestIOException

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

    fun resolveQueryContentLength(url: String): Long? {
        return ResumableHttpRangeSupport.resolveQueryContentLength(url)
    }

    fun hasExplicitRangeHeader(headers: Map<String, String>): Boolean {
        return ResumableHttpRangeSupport.hasExplicitRangeHeader(headers)
    }

    fun candidateChunkLengths(
        requestLength: Long,
        preferredChunkSize: Long = 1024L * 1024L
    ): List<Long> {
        return ResumableHttpRangeSupport.candidateChunkLengths(
            requestLength = requestLength,
            preferredChunkSize = preferredChunkSize
        )
    }

    fun shouldRetryChunkError(error: IOException): Boolean {
        return ResumableHttpRangeSupport.shouldRetryChunkError(error)
    }

    inline fun <T> executeChunkLengthFallback(
        requestLength: Long,
        preferredChunkSize: Long = 1024L * 1024L,
        execute: (Long) -> T
    ): ChunkLengthFallbackResult<T> {
        return ResumableHttpRangeSupport.executeChunkLengthFallback(
            requestLength = requestLength,
            preferredChunkSize = preferredChunkSize,
            execute = execute
        )
    }

    fun resolveTotalContentLength(uri: Uri, headers: Map<String, List<String>>): Long? {
        return ResumableHttpRangeSupport.resolveTotalContentLength(uri, headers)
    }

    fun resolveTotalContentLength(url: String, headers: Map<String, List<String>>): Long? {
        return ResumableHttpRangeSupport.resolveTotalContentLength(url, headers)
    }

    fun resolveContentRangeTotal(headers: Map<String, List<String>>): Long? {
        val contentRange = headers.entries.firstOrNull { (key, _) ->
            key.equals("Content-Range", ignoreCase = true)
        }?.value?.firstOrNull() ?: return null
        return contentRange.substringAfter('/', "").trim()
            .toLongOrNull()
            ?.takeIf { it > 0L }
    }

    fun resolveChunkResponseLength(
        requestedLength: Long,
        headers: Map<String, List<String>>,
        delegateOpenLength: Long
    ): Long {
        return ResumableHttpRangeSupport.resolveChunkResponseLength(
            requestedLength = requestedLength,
            headers = headers,
            delegateOpenLength = delegateOpenLength
        )
    }

    fun buildChunkedRequest(request: Request, start: Long, length: Long): Request {
        return ResumableHttpRangeSupport.buildChunkedRequest(
            request = request,
            start = start,
            length = length
        )
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
