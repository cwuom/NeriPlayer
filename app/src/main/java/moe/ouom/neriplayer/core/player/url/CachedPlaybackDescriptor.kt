@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import androidx.annotation.VisibleForTesting
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
import java.lang.ref.WeakReference
import java.security.MessageDigest

internal const val CACHED_PLAYBACK_DESCRIPTOR_VERSION = 2
internal const val CACHED_PLAYBACK_DESCRIPTOR_METADATA_KEY =
    "${ContentMetadata.KEY_CUSTOM_PREFIX}neriplayer_playback_descriptor"
internal const val CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY =
    "${ContentMetadata.KEY_CUSTOM_PREFIX}neriplayer_playback_cache_unsafe"

private const val CACHED_PLAYBACK_CACHE_UNSAFE_VALUE = "1"

internal enum class CachedPlaybackDescriptorSynchronizationResult {
    APPLIED,
    NO_METADATA,
    SKIPPED,
    CACHE_UNUSABLE
}

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
        source == expected.source &&
        qualityKey == expected.qualityKey &&
        mimeType == expected.mimeType &&
        codecLabel == expected.codecLabel &&
        bitrateKbps == expected.bitrateKbps &&
        sampleRateHz == expected.sampleRateHz &&
        bitDepth == expected.bitDepth &&
        channelCount == expected.channelCount &&
        this.representationIdentity == expected.representationIdentity &&
        (
            this.expectedContentLength == null ||
                expected.expectedContentLength == null ||
                expected.expectedContentLength == this.expectedContentLength
            )
}

internal fun CachedPlaybackDescriptor.matchesCachedContentLength(
    cachedContentLength: Long
): Boolean {
    return expectedContentLength == null || expectedContentLength == cachedContentLength
}

internal fun CachedPlaybackDescriptorSynchronizationResult.allowsCustomCacheKey(): Boolean {
    return this == CachedPlaybackDescriptorSynchronizationResult.APPLIED ||
        this == CachedPlaybackDescriptorSynchronizationResult.NO_METADATA
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
        ContentMetadataMutations()
            .set(
                CACHED_PLAYBACK_DESCRIPTOR_METADATA_KEY,
                encodeCachedPlaybackDescriptor(descriptor)
            )
            .remove(CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY)
    )
}

private fun Cache.isCachedPlaybackResourceUnsafe(cacheKey: String): Boolean {
    if (cacheKey.isBlank()) return false
    return runCatching {
        getContentMetadata(cacheKey).get(
            CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY,
            null as String?
        ) == CACHED_PLAYBACK_CACHE_UNSAFE_VALUE
    }.getOrDefault(false)
}

private fun Cache.markCachedPlaybackResourceUnsafe(cacheKey: String) {
    runCatching {
        applyContentMetadataMutations(
            cacheKey,
            ContentMetadataMutations().set(
                CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY,
                CACHED_PLAYBACK_CACHE_UNSAFE_VALUE
            )
        )
    }.onFailure { error ->
        NPLogger.w(
            "NERI-PlayerManager",
            "标记异常播放缓存失败: key=$cacheKey, error=${error.message}"
        )
    }
}

private fun Cache.clearCachedPlaybackResourceUnsafe(cacheKey: String): Boolean {
    if (cacheKey.isBlank()) return true
    return runCatching {
        if (!getContentMetadata(cacheKey).contains(CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY)) {
            return@runCatching true
        }
        applyContentMetadataMutations(
            cacheKey,
            ContentMetadataMutations().remove(CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY)
        )
        true
    }.getOrElse { error ->
        NPLogger.w(
            "NERI-PlayerManager",
            "清除异常播放缓存标记失败: key=$cacheKey, error=${error.message}"
        )
        false
    }
}

private object PlaybackCacheSafetyTracker {
    private val lock = Any()
    private var cacheOwner: WeakReference<Cache>? = null
    private val unsafeKeys = mutableSetOf<String>()

    fun markUnsafe(cache: Cache, cacheKey: String) {
        synchronized(lock) {
            resetFor(cache)
            unsafeKeys += cacheKey
        }
    }

    fun clearUnsafe(cache: Cache, cacheKey: String) {
        synchronized(lock) {
            resetFor(cache)
            unsafeKeys -= cacheKey
        }
    }

    fun isUnsafe(cache: Cache?, cacheKey: String): Boolean {
        if (cache == null || cacheKey.isBlank()) return false
        return synchronized(lock) {
            resetFor(cache)
            cacheKey in unsafeKeys
        }
    }

    @VisibleForTesting
    fun clearForTesting() {
        synchronized(lock) {
            cacheOwner = null
            unsafeKeys.clear()
        }
    }

    private fun resetFor(cache: Cache) {
        if (cacheOwner?.get() !== cache) {
            cacheOwner = WeakReference(cache)
            unsafeKeys.clear()
        }
    }
}

@VisibleForTesting
internal fun clearPlaybackCacheSafetyForTesting() {
    PlaybackCacheSafetyTracker.clearForTesting()
}

internal fun PlayerManager.markPlaybackCacheKeyUnsafe(
    mediaCache: Cache,
    cacheKey: String
) {
    if (cache !== mediaCache || cacheKey.isBlank()) return
    PlaybackCacheSafetyTracker.markUnsafe(mediaCache, cacheKey)
    mediaCache.markCachedPlaybackResourceUnsafe(cacheKey)
}

internal fun PlayerManager.clearPlaybackCacheKeyUnsafe(
    mediaCache: Cache,
    cacheKey: String
): Boolean {
    if (cacheKey.isBlank()) return true
    if (cache !== mediaCache) return false
    return if (mediaCache.clearCachedPlaybackResourceUnsafe(cacheKey)) {
        if (cache !== mediaCache) return false
        PlaybackCacheSafetyTracker.clearUnsafe(mediaCache, cacheKey)
        true
    } else {
        if (cache === mediaCache) {
            PlaybackCacheSafetyTracker.markUnsafe(mediaCache, cacheKey)
        }
        false
    }
}

internal fun PlayerManager.isPlaybackCacheKeyUnsafe(cacheKey: String): Boolean {
    return PlaybackCacheSafetyTracker.isUnsafe(cache, cacheKey)
}

private fun PlayerManager.loadPersistedPlaybackCacheKeySafety(
    mediaCache: Cache,
    cacheKey: String
): Boolean {
    if (cache !== mediaCache) return false
    if (!mediaCache.isCachedPlaybackResourceUnsafe(cacheKey)) return false
    if (cache !== mediaCache) return false
    PlaybackCacheSafetyTracker.markUnsafe(mediaCache, cacheKey)
    return true
}

internal suspend fun PlayerManager.loadPlaybackCacheKeySafety(cacheKey: String): Boolean {
    return withContext(Dispatchers.IO) {
        val mediaCache = cache ?: return@withContext false
        PlaybackCacheSafetyTracker.isUnsafe(mediaCache, cacheKey) ||
            loadPersistedPlaybackCacheKeySafety(mediaCache, cacheKey)
    }
}

internal fun PlayerManager.safeCustomPlaybackCacheKey(cacheKey: String): String? {
    return cacheKey.takeIf {
        it.isNotBlank() &&
            !PlaybackCacheSafetyTracker.isUnsafe(cache, it)
    }
}

internal suspend fun PlayerManager.synchronizeCachedPlaybackDescriptor(
    cacheKey: String,
    audioInfo: PlaybackAudioInfo?,
    expectedContentLength: Long?,
    representationIdentity: String?,
    shouldApplyMutation: () -> Boolean = { true }
): CachedPlaybackDescriptorSynchronizationResult = withContext(Dispatchers.IO) {
    if (cacheKey.isBlank()) {
        return@withContext CachedPlaybackDescriptorSynchronizationResult.NO_METADATA
    }
    val mediaCache = cache
        ?: return@withContext CachedPlaybackDescriptorSynchronizationResult.NO_METADATA
    if (cache !== mediaCache) {
        return@withContext CachedPlaybackDescriptorSynchronizationResult.SKIPPED
    }
    loadPersistedPlaybackCacheKeySafety(mediaCache, cacheKey)
    val remoteAudioInfo = audioInfo
        ?.takeUnless { it.source == PlaybackAudioSource.LOCAL }
        ?: return@withContext CachedPlaybackDescriptorSynchronizationResult.NO_METADATA
    val descriptor = cachedPlaybackDescriptorFromAudioInfo(
        audioInfo = remoteAudioInfo,
        expectedContentLength = expectedContentLength,
        representationIdentity = representationIdentity
    )
    val expectedLength = expectedContentLength?.takeIf { it > 0L }

    runCatching<CachedPlaybackDescriptorSynchronizationResult> {
        fun canMutate(): Boolean = cache === mediaCache && shouldApplyMutation()

        if (!canMutate()) {
            return@runCatching CachedPlaybackDescriptorSynchronizationResult.SKIPPED
        }

        val cachedSpans = mediaCache.getCachedSpans(cacheKey)
        if (cache !== mediaCache) {
            return@runCatching CachedPlaybackDescriptorSynchronizationResult.SKIPPED
        }
        val hasCachedData = cachedSpans.isNotEmpty()
        val cachedContentLength = if (hasCachedData) {
            ContentMetadata.getContentLength(mediaCache.getContentMetadata(cacheKey))
        } else {
            0L
        }
        val existingDescriptor = if (hasCachedData) {
            mediaCache.readCachedPlaybackDescriptor(cacheKey)
        } else {
            null
        }
        if (cache !== mediaCache) {
            return@runCatching CachedPlaybackDescriptorSynchronizationResult.SKIPPED
        }
        val shouldReplaceForLength = expectedLength != null &&
            hasCachedData &&
            shouldReplaceCachedPreviewResource(cachedContentLength, expectedLength)
        val shouldReplaceForDescriptor = hasCachedData &&
            existingDescriptor?.matches(
                audioInfo = remoteAudioInfo,
                expectedContentLength = expectedContentLength,
                representationIdentity = representationIdentity
            ) != true
        val shouldReplaceUnsafeResource = hasCachedData &&
            PlaybackCacheSafetyTracker.isUnsafe(mediaCache, cacheKey)

        if (
            shouldReplaceForLength ||
                shouldReplaceForDescriptor ||
                shouldReplaceUnsafeResource
        ) {
            if (!canMutate()) {
                return@runCatching CachedPlaybackDescriptorSynchronizationResult.SKIPPED
            }
            mediaCache.removeResource(cacheKey)
            if (cache !== mediaCache) {
                return@runCatching CachedPlaybackDescriptorSynchronizationResult.SKIPPED
            }
            if (mediaCache.getCachedSpans(cacheKey).isNotEmpty()) {
                markPlaybackCacheKeyUnsafe(mediaCache, cacheKey)
                NPLogger.w(
                    "NERI-PlayerManager",
                    "缓存资源未完全移除，保留旧描述符: key=$cacheKey"
                )
                return@runCatching CachedPlaybackDescriptorSynchronizationResult.CACHE_UNUSABLE
            }
            NPLogger.w(
                "NERI-PlayerManager",
                "缓存表示不匹配，移除旧资源: key=$cacheKey, " +
                    "source=${remoteAudioInfo.source}, lengthMismatch=$shouldReplaceForLength"
            )
        }

        if (!canMutate()) {
            return@runCatching CachedPlaybackDescriptorSynchronizationResult.SKIPPED
        }
        mediaCache.writeCachedPlaybackDescriptor(cacheKey, descriptor)
        if (cache !== mediaCache) {
            return@runCatching CachedPlaybackDescriptorSynchronizationResult.SKIPPED
        }
        PlaybackCacheSafetyTracker.clearUnsafe(mediaCache, cacheKey)
        CachedPlaybackDescriptorSynchronizationResult.APPLIED
    }.getOrElse { error ->
        if (cache === mediaCache) {
            markPlaybackCacheKeyUnsafe(mediaCache, cacheKey)
        }
        NPLogger.w(
            "NERI-PlayerManager",
            "同步播放缓存描述符失败: key=$cacheKey, error=${error.message}"
        )
        if (cache === mediaCache) {
            CachedPlaybackDescriptorSynchronizationResult.CACHE_UNUSABLE
        } else {
            CachedPlaybackDescriptorSynchronizationResult.SKIPPED
        }
    }
}
