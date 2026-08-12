@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal const val CACHED_PLAYBACK_DESCRIPTOR_VERSION = 1
internal const val CACHED_PLAYBACK_DESCRIPTOR_METADATA_KEY =
    "${ContentMetadata.KEY_CUSTOM_PREFIX}neriplayer_playback_descriptor"

internal data class CachedPlaybackDescriptor(
    val version: Int,
    val source: PlaybackAudioSource,
    val qualityKey: String?,
    val mimeType: String?,
    val codecLabel: String?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channelCount: Int?,
    val qualityOptionKeys: List<String>,
    val expectedContentLength: Long?,
    val representationIdentity: String?,
    val representationFingerprint: String
)

internal fun cachedPlaybackDescriptorFromAudioInfo(
    audioInfo: PlaybackAudioInfo,
    expectedContentLength: Long?,
    representationIdentity: String? = null
): CachedPlaybackDescriptor {
    val descriptor = CachedPlaybackDescriptor(
        version = CACHED_PLAYBACK_DESCRIPTOR_VERSION,
        source = audioInfo.source,
        qualityKey = audioInfo.qualityKey.normalizedDescriptorValue(),
        mimeType = audioInfo.mimeType.normalizedDescriptorValue(),
        codecLabel = audioInfo.codecLabel.normalizedDescriptorValue(),
        bitrateKbps = audioInfo.bitrateKbps,
        sampleRateHz = audioInfo.sampleRateHz,
        bitDepth = audioInfo.bitDepth,
        channelCount = audioInfo.channelCount,
        qualityOptionKeys = audioInfo.qualityOptions
            .mapNotNull { it.key.normalizedDescriptorValue() }
            .distinct(),
        expectedContentLength = expectedContentLength?.takeIf { it > 0L },
        representationIdentity = representationIdentity.normalizedDescriptorValue(),
        representationFingerprint = ""
    )
    return descriptor.copy(representationFingerprint = descriptor.fingerprint())
}

private fun String?.normalizedDescriptorValue(): String? = this
    ?.trim()
    ?.takeIf { it.isNotBlank() }

private fun CachedPlaybackDescriptor.fingerprint(): String {
    val canonical = listOf(
        source.name,
        qualityKey.orEmpty(),
        mimeType.orEmpty(),
        codecLabel.orEmpty(),
        bitrateKbps?.toString().orEmpty(),
        sampleRateHz?.toString().orEmpty(),
        bitDepth?.toString().orEmpty(),
        channelCount?.toString().orEmpty(),
        qualityOptionKeys.joinToString(separator = ","),
        expectedContentLength?.toString().orEmpty(),
        representationIdentity.orEmpty()
    ).joinToString(separator = "|")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun CachedPlaybackDescriptor.matches(
    audioInfo: PlaybackAudioInfo,
    expectedContentLength: Long?,
    representationIdentity: String? = null
): Boolean {
    val expected = cachedPlaybackDescriptorFromAudioInfo(
        audioInfo = audioInfo,
        expectedContentLength = expectedContentLength,
        representationIdentity = representationIdentity
    )
    return version == CACHED_PLAYBACK_DESCRIPTOR_VERSION &&
        representationFingerprint == fingerprint() &&
        representationFingerprint == expected.representationFingerprint &&
        (representationIdentity == null ||
            expected.representationIdentity == null ||
            representationIdentity == expected.representationIdentity) &&
        source == expected.source &&
        qualityKey == expected.qualityKey &&
        mimeType == expected.mimeType &&
        codecLabel == expected.codecLabel &&
        bitrateKbps == expected.bitrateKbps &&
        sampleRateHz == expected.sampleRateHz &&
        bitDepth == expected.bitDepth &&
        channelCount == expected.channelCount &&
        (expected.expectedContentLength == null ||
            expectedContentLength == null ||
            expected.expectedContentLength == this.expectedContentLength)
}

internal fun CachedPlaybackDescriptor.matchesCachedContentLength(
    cachedContentLength: Long
): Boolean {
    return expectedContentLength == null || expectedContentLength == cachedContentLength
}

internal fun CachedPlaybackDescriptor.toPlaybackAudioInfo(
    getLocalizedString: (Int) -> String
): PlaybackAudioInfo? {
    if (version != CACHED_PLAYBACK_DESCRIPTOR_VERSION) return null
    if (representationFingerprint != fingerprint()) return null
    val options = qualityOptionKeys.map { key ->
        PlaybackQualityOption(key, qualityLabelForCachedSource(source, key, getLocalizedString))
    }
    return PlaybackAudioInfo(
        source = source,
        qualityKey = qualityKey,
        qualityLabel = qualityKey?.let {
            qualityLabelForCachedSource(source, it, getLocalizedString)
        },
        qualityOptions = options,
        codecLabel = codecLabel,
        mimeType = mimeType,
        bitrateKbps = bitrateKbps,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        channelCount = channelCount
    )
}

private fun qualityLabelForCachedSource(
    source: PlaybackAudioSource,
    key: String,
    getLocalizedString: (Int) -> String
): String {
    return when (source) {
        PlaybackAudioSource.NETEASE -> qualityLabelForNetease(key, getLocalizedString)
        PlaybackAudioSource.BILIBILI -> qualityLabelForBili(key, getLocalizedString)
        PlaybackAudioSource.YOUTUBE_MUSIC -> qualityLabelForYouTube(key, getLocalizedString)
        PlaybackAudioSource.LOCAL -> key
    }
}

internal fun encodeCachedPlaybackDescriptor(
    descriptor: CachedPlaybackDescriptor
): String {
    return JSONObject().apply {
        put("version", descriptor.version)
        put("source", descriptor.source.name)
        putNullable("qualityKey", descriptor.qualityKey)
        putNullable("mimeType", descriptor.mimeType)
        putNullable("codecLabel", descriptor.codecLabel)
        putNullable("bitrateKbps", descriptor.bitrateKbps)
        putNullable("sampleRateHz", descriptor.sampleRateHz)
        putNullable("bitDepth", descriptor.bitDepth)
        putNullable("channelCount", descriptor.channelCount)
        put("qualityOptionKeys", JSONArray(descriptor.qualityOptionKeys))
        putNullable("expectedContentLength", descriptor.expectedContentLength)
        putNullable("representationIdentity", descriptor.representationIdentity)
        put("representationFingerprint", descriptor.representationFingerprint)
    }.toString()
}

internal fun decodeCachedPlaybackDescriptor(raw: String?): CachedPlaybackDescriptor? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val json = JSONObject(raw)
        val source = PlaybackAudioSource.valueOf(json.getString("source"))
        val qualityOptionKeys = buildList {
            val options = json.optJSONArray("qualityOptionKeys") ?: return@buildList
            for (index in 0 until options.length()) {
                options.optString(index).normalizedDescriptorValue()?.let(::add)
            }
        }
        CachedPlaybackDescriptor(
            version = json.optInt("version", 0),
            source = source,
            qualityKey = json.optNullableString("qualityKey"),
            mimeType = json.optNullableString("mimeType"),
            codecLabel = json.optNullableString("codecLabel"),
            bitrateKbps = json.optNullableInt("bitrateKbps"),
            sampleRateHz = json.optNullableInt("sampleRateHz"),
            bitDepth = json.optNullableInt("bitDepth"),
            channelCount = json.optNullableInt("channelCount"),
            qualityOptionKeys = qualityOptionKeys,
            expectedContentLength = json.optNullableLong("expectedContentLength"),
            representationIdentity = json.optNullableString("representationIdentity"),
            representationFingerprint = json.optString("representationFingerprint")
        )
    }.getOrNull()
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.optNullableString(key: String): String? {
    return optString(key).normalizedDescriptorValue()
}

private fun JSONObject.optNullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key).takeIf { it > 0 }
}

private fun JSONObject.optNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key).takeIf { it > 0L }
}

internal fun Cache.readCachedPlaybackDescriptor(cacheKey: String): CachedPlaybackDescriptor? {
    return decodeCachedPlaybackDescriptor(
        getContentMetadata(cacheKey).get(
            CACHED_PLAYBACK_DESCRIPTOR_METADATA_KEY,
            null as String?
        )
    )
}

internal fun Cache.writeCachedPlaybackDescriptor(
    cacheKey: String,
    descriptor: CachedPlaybackDescriptor
) {
    applyContentMetadataMutations(
        cacheKey,
        ContentMetadataMutations().set(
            CACHED_PLAYBACK_DESCRIPTOR_METADATA_KEY,
            encodeCachedPlaybackDescriptor(descriptor)
        )
    )
}

internal suspend fun PlayerManager.invalidateMismatchedCachedPlaybackDescriptor(
    cacheKey: String,
    audioInfo: PlaybackAudioInfo?,
    expectedContentLength: Long?,
    representationIdentity: String?,
    shouldApplyMutation: () -> Boolean = { true }
): Boolean = withContext(Dispatchers.IO) {
    if (cacheKey.isBlank() || audioInfo == null || audioInfo.source == PlaybackAudioSource.LOCAL) {
        return@withContext false
    }
    val mediaCache = cache ?: return@withContext false
    runCatching {
        if (mediaCache.getCachedSpans(cacheKey).isEmpty()) return@runCatching false
        val existing = mediaCache.readCachedPlaybackDescriptor(cacheKey)
        val matches = existing?.matches(
            audioInfo = audioInfo,
            expectedContentLength = expectedContentLength,
            representationIdentity = representationIdentity
        ) == true
        if (matches || !shouldApplyMutation()) return@runCatching false
        mediaCache.removeResource(cacheKey)
        NPLogger.w(
            "NERI-PlayerManager",
            "缓存表示描述符不匹配，移除旧资源: key=$cacheKey, source=${audioInfo.source}"
        )
        true
    }.getOrElse { error ->
        NPLogger.w(
            "NERI-PlayerManager",
            "检查缓存表示描述符失败: key=$cacheKey, error=${error.message}"
        )
        false
    }
}

internal suspend fun PlayerManager.persistCachedPlaybackDescriptor(
    cacheKey: String,
    audioInfo: PlaybackAudioInfo?,
    expectedContentLength: Long?,
    representationIdentity: String?
) = withContext(Dispatchers.IO) {
    if (cacheKey.isBlank() || audioInfo == null || audioInfo.source == PlaybackAudioSource.LOCAL) {
        return@withContext
    }
    val mediaCache = cache ?: return@withContext
    runCatching {
        mediaCache.writeCachedPlaybackDescriptor(
            cacheKey = cacheKey,
            descriptor = cachedPlaybackDescriptorFromAudioInfo(
                audioInfo = audioInfo,
                expectedContentLength = expectedContentLength,
                representationIdentity = representationIdentity
            )
        )
    }.onFailure { error ->
        NPLogger.w(
            "NERI-PlayerManager",
            "写入播放缓存描述符失败: key=$cacheKey, error=${error.message}"
        )
    }
}
