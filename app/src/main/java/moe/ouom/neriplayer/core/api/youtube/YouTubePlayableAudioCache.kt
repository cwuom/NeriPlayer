package moe.ouom.neriplayer.core.api.youtube

import java.util.Locale
import moe.ouom.neriplayer.core.logging.NPLogger

private const val PLAYABLE_URL_EXPIRY_SAFETY_MARGIN_MS = 90L * 1000L
private const val PLAYABLE_URL_CACHE_TTL_MS = 8L * 60L * 1000L
private const val PLAYABLE_URL_CACHE_MAX_SIZE = 64

internal fun resolvePlayableAudioCacheExpiresAtMs(
    url: String,
    cachedAtMs: Long,
    defaultTtlMs: Long,
    safetyMarginMs: Long = PLAYABLE_URL_EXPIRY_SAFETY_MARGIN_MS
): Long {
    val defaultExpiresAtMs = cachedAtMs + defaultTtlMs.coerceAtLeast(0L)
    val streamExpiresAtMs = extractStreamQueryParameter(url, "expire")
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?.let { expireSeconds ->
            (expireSeconds * 1000L - safetyMarginMs.coerceAtLeast(0L))
                .coerceAtLeast(cachedAtMs)
        }
    return minOf(defaultExpiresAtMs, streamExpiresAtMs ?: defaultExpiresAtMs)
}

internal class YouTubePlayableAudioCache {
    private data class Entry(
        val audio: YouTubePlayableAudio,
        val cachedAtMs: Long,
        val expiresAtMs: Long
    )

    private val entries = linkedMapOf<String, Entry>()

    fun clear() = synchronized(entries) { entries.clear() }

    fun get(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean = false,
        avoidDirect: Boolean = false,
        allowUnverifiedDirectFallback: Boolean = true
    ): YouTubePlayableAudio? = synchronized(entries) {
        val key = entryKey(videoId, preferredQualityKey)
        val entry = entries[key] ?: return@synchronized null
        val nowMs = System.currentTimeMillis()
        if (nowMs >= entry.expiresAtMs) {
            entries.remove(key)
            NPLogger.d(
                "YouTubeMusicPlayback",
                "drop expired playable audio cache: videoId=$videoId, quality=$preferredQualityKey, ageMs=${nowMs - entry.cachedAtMs}, expiresInMs=${entry.expiresAtMs - nowMs}"
            )
            return@synchronized null
        }
        if (!meetsCachedQuality(entry.audio, preferredQualityKey)) {
            entries.remove(key)
            NPLogger.d(
                "YouTubeMusicPlayback",
                "drop cached playable audio below selected quality: videoId=$videoId, quality=$preferredQualityKey, bitrate=${entry.audio.bitrateKbps}"
            )
            return@synchronized null
        }
        if (!acceptsStream(entry.audio, requireDirect, avoidDirect, allowUnverifiedDirectFallback)) {
            return@synchronized null
        }
        entry.audio
    }

    fun put(videoId: String, qualityKey: String, audio: YouTubePlayableAudio) {
        synchronized(entries) {
            val key = entryKey(videoId, qualityKey)
            val nowMs = System.currentTimeMillis()
            entries.remove(key)
            entries[key] = Entry(
                audio = audio,
                cachedAtMs = nowMs,
                expiresAtMs = resolvePlayableAudioCacheExpiresAtMs(
                    url = audio.url,
                    cachedAtMs = nowMs,
                    defaultTtlMs = PLAYABLE_URL_CACHE_TTL_MS
                )
            )
            while (entries.size > PLAYABLE_URL_CACHE_MAX_SIZE) {
                val oldest = entries.entries.firstOrNull()?.key ?: break
                entries.remove(oldest)
            }
        }
    }

    private fun meetsCachedQuality(audio: YouTubePlayableAudio, qualityKey: String): Boolean {
        val selectedQuality = qualityKey.substringAfter('|', qualityKey)
        if (selectedQuality.endsWith("_m4a") && isPlayableM4aContainer(audio.mimeType)) return true
        return satisfiesYouTubePlaybackQuality(audio, selectedQuality.removeSuffix("_m4a"))
    }

    private fun acceptsStream(
        audio: YouTubePlayableAudio,
        requireDirect: Boolean,
        avoidDirect: Boolean,
        allowUnverifiedDirectFallback: Boolean
    ): Boolean {
        val isDirect = audio.streamType == YouTubePlayableStreamType.DIRECT
        if (!isDirect) return !requireDirect
        if (avoidDirect) return false
        return allowUnverifiedDirectFallback || isTrustedYouTubeDirectUrlForStrictRecovery(audio.url)
    }

    private fun entryKey(videoId: String, qualityKey: String): String =
        "$videoId|${qualityKey.lowercase(Locale.US)}"
}
