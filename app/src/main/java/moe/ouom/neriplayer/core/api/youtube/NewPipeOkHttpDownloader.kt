package moe.ouom.neriplayer.core.api.youtube

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
 * File: moe.ouom.neriplayer.core.api.youtube/NewPipeOkHttpDownloader
 * Updated: 2026/3/23
 */

import java.io.IOException
import java.util.Locale
import moe.ouom.neriplayer.data.platform.youtube.appendYouTubeConsentCookie
import moe.ouom.neriplayer.data.platform.youtube.effectiveCookieHeader
import moe.ouom.neriplayer.data.auth.youtube.parseCookieHeader
import moe.ouom.neriplayer.data.platform.youtube.resolveAuthorizationHeader
import moe.ouom.neriplayer.data.platform.youtube.resolveRequestUserAgent
import moe.ouom.neriplayer.data.platform.youtube.resolveXGoogAuthUser
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.isTrustedYouTubeHost
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeGoogleVideoHost
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeInnertubeHost
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicHost
import moe.ouom.neriplayer.data.platform.youtube.isYouTubePageHost
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_WEB_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeStreamRequestHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

class NewPipeOkHttpDownloader(
    private val client: OkHttpClient,
    private val authProvider: () -> YouTubeAuthBundle = { YouTubeAuthBundle() }
) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod().uppercase(Locale.ROOT)
        val builder = okhttp3.Request.Builder()
            .url(request.url())

        request.headers().forEach { (name, values) ->
            values.forEach { value ->
                builder.addHeader(name, value)
            }
        }

        if (isYouTubeRequest(builder.build())) {
            applyYouTubeHeaders(builder)
        }

        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> {
                val contentType = builder.build().header("Content-Type")
                    ?.toMediaTypeOrNull()
                    ?: "application/json".toMediaTypeOrNull()
                val body = (request.dataToSend() ?: ByteArray(0)).toRequestBody(contentType)
                builder.post(body)
            }
            else -> throw IOException("Unsupported NewPipe request method: $method")
        }

        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val headers = linkedMapOf<String, List<String>>().apply {
                response.headers.names().forEach { name ->
                    put(name, response.headers.values(name))
                }
            }
            return Response(
                response.code,
                response.message,
                headers,
                responseBody,
                response.request.url.toString()
            )
        }
    }

    private fun applyYouTubeHeaders(builder: okhttp3.Request.Builder) {
        val request = builder.build()
        val auth = authProvider().normalized()
        val originalHeaders = linkedMapOf<String, String>().apply {
            request.headers.names().forEach { name ->
                request.header(name)?.let { value -> put(name, value) }
            }
        }
        if (isYouTubeGoogleVideoHost(request.url.host)) {
            val streamHeaders = auth.buildYouTubeStreamRequestHeaders(
                original = originalHeaders,
                refererOrigin = request.header("Referer")
                    .orEmpty()
                    .removeSuffix("/")
                    .ifBlank {
                        request.header("Origin")
                            .orEmpty()
                            .removeSuffix("/")
                    }
                    .ifBlank { auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN } },
                streamUrl = request.url.toString()
            )
            request.headers.names().forEach { name ->
                builder.removeHeader(name)
            }
            streamHeaders.forEach { (name, value) ->
                builder.header(name, value)
            }
            return
        }

        val mergedCookie = mergeCookieHeader(request.header("Cookie").orEmpty(), auth)
        if (mergedCookie.isNotBlank() && mergedCookie != request.header("Cookie").orEmpty()) {
            builder.header("Cookie", mergedCookie)
        }

        if (!auth.isUsable()) {
            return
        }

        if (request.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", auth.resolveRequestUserAgent())
        }
        if (request.header("X-Goog-AuthUser").isNullOrBlank()) {
            builder.header("X-Goog-AuthUser", auth.resolveXGoogAuthUser())
        }

        if (shouldAttachAuthorization(request) && request.header("Authorization").isNullOrBlank()) {
            val authorization = auth.resolveAuthorizationHeader(
                origin = resolveAuthorizationOrigin(request, auth)
            )
            if (authorization.isNotBlank()) {
                builder.header("Authorization", authorization)
            }
        }

        if (shouldAttachWebOriginHeaders(request)) {
            val origin = resolvePageOrigin(request, auth)
            if (request.header("Origin").isNullOrBlank()) {
                builder.header("Origin", origin)
            }
            if (request.header("X-Origin").isNullOrBlank()) {
                builder.header("X-Origin", origin)
            }
            if (request.header("Referer").isNullOrBlank()) {
                builder.header("Referer", "$origin/")
            }
        }
    }

    private fun mergeCookieHeader(
        existingCookieHeader: String,
        auth: YouTubeAuthBundle
    ): String {
        val merged = linkedMapOf<String, String>()
        parseCookieHeader(existingCookieHeader).forEach { (key, value) ->
            merged[key] = value
        }
        parseCookieHeader(auth.effectiveCookieHeader()).forEach { (key, value) ->
            merged[key] = value
        }
        val rawCookieHeader = when {
            merged.isNotEmpty() -> merged.entries.joinToString("; ") { (key, value) -> "$key=$value" }
            existingCookieHeader.isNotBlank() -> existingCookieHeader.trim()
            else -> auth.effectiveCookieHeader()
        }
        return appendYouTubeConsentCookie(rawCookieHeader)
    }

    private fun resolveAuthorizationOrigin(
        request: okhttp3.Request,
        auth: YouTubeAuthBundle
    ): String {
        request.header("Origin")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val host = request.url.host.lowercase(Locale.US)
        return when {
            isYouTubeInnertubeHost(host) -> auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
            isYouTubeMusicHost(host) -> YOUTUBE_MUSIC_ORIGIN
            isYouTubePageHost(host) -> YOUTUBE_WEB_ORIGIN
            else -> auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        }
    }

    private fun resolvePageOrigin(
        request: okhttp3.Request,
        auth: YouTubeAuthBundle
    ): String {
        request.header("Origin")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val host = request.url.host.lowercase(Locale.US)
        return when {
            isYouTubeMusicHost(host) -> YOUTUBE_MUSIC_ORIGIN
            isYouTubePageHost(host) -> YOUTUBE_WEB_ORIGIN
            else -> auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        }
    }

    private fun isYouTubeRequest(request: okhttp3.Request): Boolean {
        val host = request.url.host.lowercase(Locale.US)
        return isTrustedYouTubeHost(host)
    }

    private fun shouldAttachAuthorization(request: okhttp3.Request): Boolean {
        val host = request.url.host.lowercase(Locale.US)
        return isYouTubeInnertubeHost(host) || isYouTubePageHost(host)
    }

    private fun shouldAttachWebOriginHeaders(request: okhttp3.Request): Boolean {
        val host = request.url.host.lowercase(Locale.US)
        val path = request.url.encodedPath.lowercase(Locale.US)
        return !isYouTubeInnertubeHost(host) &&
            !path.startsWith("/youtubei/") &&
            isYouTubePageHost(host)
    }
}
