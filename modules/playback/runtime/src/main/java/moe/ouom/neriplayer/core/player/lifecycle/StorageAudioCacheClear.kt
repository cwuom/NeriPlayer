@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.core.player.media.PlaybackMediaItemFactory
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId
import org.json.JSONArray

internal const val CACHE_SONG_OWNERS = "${ContentMetadata.KEY_CUSTOM_PREFIX}neriplayer_cache_song_owners"

internal fun selectRetainedCacheSongs(songs: List<SongItem>, count: Int): List<SongItem> =
    songs.distinctBy { it.stableKey() }.take(count.coerceAtLeast(0))

internal fun Cache.recordStorageCacheOwner(key: String, song: SongItem) = synchronized(this) {
    val previous = storageCacheOwners(key)
    if (song.stableKey() in previous) return@synchronized
    val owners = previous + song.stableKey()
    applyContentMetadataMutations(key, ContentMetadataMutations().set(CACHE_SONG_OWNERS, JSONArray(owners).toString()))
}

private fun Cache.storageCacheOwners(key: String): Set<String> {
    val encoded = getContentMetadata(key).get(CACHE_SONG_OWNERS, "").orEmpty()
    if (encoded.isBlank()) return emptySet()
    return runCatching {
        val array = JSONArray(encoded)
        (0 until array.length()).map { array.getString(it) }.toSet()
    }.getOrDefault(emptySet())
}

internal suspend fun estimateAudioStorageCache(
    cache: Cache,
    retainedSongs: List<SongItem>,
    protectedKeys: Set<String>,
    preserveUnmapped: Boolean
): Long? = try {
    currentCoroutineContext().ensureActive()
    val retention = AudioStorageCacheRetention(retainedSongs, protectedKeys, preserveUnmapped)
    var bytes = 0L
    for (key in cache.keys.toList()) {
        currentCoroutineContext().ensureActive()
        if (!retention.retains(key, cache.storageCacheOwners(key))) {
            bytes += cache.getCachedSpans(key).sumOf { it.length }
        }
    }
    bytes
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

internal suspend fun clearAudioStorageCache(
    cache: Cache,
    retainedSongs: List<SongItem>,
    protectedKeys: Set<String>,
    preserveUnmapped: Boolean,
    removalGate: ((() -> Unit) -> Boolean) = { remove -> remove(); true }
): ExtraCacheClearResult {
    val before = cache.cacheSpace
    val retention = AudioStorageCacheRetention(retainedSongs, protectedKeys, preserveUnmapped)
    var success = true
    var deletedFiles = 0
    var confirmedBytes = 0L
    for (key in cache.keys.toList()) {
        currentCoroutineContext().ensureActive()
        try {
            if (retention.retains(key, cache.storageCacheOwners(key))) continue
            val oldSpans = cache.getCachedSpans(key)
            if (!removalGate { cache.removeResource(key) }) {
                success = false
                break
            }
            val remaining = cache.getCachedSpans(key)
            if (remaining.isNotEmpty()) success = false
            deletedFiles += (oldSpans.size - remaining.size).coerceAtLeast(0)
            confirmedBytes += (oldSpans.sumOf { it.length } - remaining.sumOf { it.length }).coerceAtLeast(0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            success = false
        }
    }
    val freed = try {
        (before - cache.cacheSpace).coerceAtLeast(0)
    } catch (_: Exception) {
        success = false
        confirmedBytes
    }
    return ExtraCacheClearResult(success, freed, 0, deletedFiles)
}

private class AudioStorageCacheRetention(
    retainedSongs: List<SongItem>,
    private val protectedKeys: Set<String>,
    private val preserveUnmapped: Boolean
) {
    private val identities = retainedSongs.map { it.stableKey() }.toSet()
    private val prefixes = retainedSongs.flatMap(::legacySongCachePrefixes)

    fun retains(key: String, owners: Set<String>): Boolean =
        key in protectedKeys || owners.any(identities::contains) ||
            (owners.isEmpty() && prefixes.any(key::startsWith)) ||
            (preserveUnmapped && owners.isEmpty() && !isRecognizedSongCacheKey(key))
}

private fun legacySongCachePrefixes(song: SongItem): List<String> {
    if (LocalSongSupport.isLocalSong(song)) return emptyList()
    val sourcePrefixes = when {
        PlaybackMediaItemFactory.isYouTubeSource(song) -> {
            val id = song.audioId ?: extractYouTubeMusicVideoId(song.mediaUri)
            listOfNotNull(id?.takeIf(String::isNotBlank)?.let { "ytmusic-$it-" })
        }
        PlaybackMediaItemFactory.isBiliSource(song) -> {
            val id = song.audioId ?: song.id.toString()
            val cid = song.subAudioId ?: song.album.substringAfter('|', "").substringBefore('|').takeIf(String::isNotBlank)
            listOf(if (cid == null) "bili-$id-" else "bili-$id-$cid-")
        }
        else -> listOf("netease-${song.id}-", "netease-preview-v1-${song.id}-")
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(song.stableKey().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(24)
    return sourcePrefixes + "listen-together-stream-$digest-"
}

private fun isRecognizedSongCacheKey(key: String): Boolean =
    key.startsWith("netease-") || key.startsWith("ytmusic-") ||
        (key.startsWith("bili-") && !key.startsWith("bili-auto-")) ||
        key.startsWith("listen-together-stream-")
