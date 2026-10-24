@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.ouom.neriplayer.core.player.lifecycle

import coil.Coil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.cache.clearRetainingImageCache
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.url.currentPlaybackCacheKeyForRecovery
import moe.ouom.neriplayer.data.history.toSongItem
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearOutcome
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions

private val storageCacheClearMutex = Mutex()

internal suspend fun PlayerManager.clearStorageCachesImpl(options: StorageCacheClearOptions): PlayerCacheClearOutcome =
    storageCacheClearMutex.withLock {
        withContext(Dispatchers.IO) {
            val empty = ExtraCacheClearResult(true, 0, 0, 0)
            if (!options.needsPlayerCacheClear) return@withContext PlayerCacheClearOutcome(empty)
            var completed = empty
            var retentionUnavailable = options.retainRecentSongs > 0
            try {
                val recent = if (options.retainRecentSongs > 0) {
                    val history = PlayerDependencies.repositories.playHistoryRepo
                    if (!history.awaitInitialized()) {
                        return@withContext PlayerCacheClearOutcome(empty.copy(success = false), retentionUnavailable = true)
                    }
                    selectRetainedCacheSongs(history.historyFlow.value.map { it.toSongItem() }, options.retainRecentSongs)
                } else emptyList()
                retentionUnavailable = false
                val (currentSong, currentKeys, requestToken) = withContext(Dispatchers.Main.immediate) {
                    val mediaUri = if (isPlayerInitialized()) player.currentMediaItem?.localConfiguration?.uri?.toString() else null
                    val keys = setOfNotNull(currentPlaybackCacheKeyForRecovery(), currentMediaUrlFlow.value, mediaUri)
                    Triple(currentSongFlow.value, keys, playbackRequestToken)
                }
                val retained = (recent + listOfNotNull(currentSong)).distinctBy { it.stableKey() }
                val audio = if (options.audioCache) cache?.let {
                    clearAudioStorageCache(it, retained, currentKeys, options.retainRecentSongs > 0) { remove ->
                        synchronized(storageCachePlaybackLock) {
                            if (playbackRequestToken != requestToken || currentSongFlow.value?.stableKey() != currentSong?.stableKey()) {
                                false
                            } else {
                                remove()
                                true
                            }
                        }
                    }
                } ?: empty else empty
                completed = audio
                val image = if (options.imageCache) {
                    val covers = (if (options.retainRecentSongs > 0) retained else emptyList())
                        .flatMap { listOfNotNull(it.coverUrl, it.customCoverUrl, it.originalCoverUrl) }
                        .filter(String::isNotBlank).toSet()
                    clearRetainingImageCache(Coil.imageLoader(application), covers, options.retainRecentSongs > 0)
                } else empty
                PlayerCacheClearOutcome(
                    ExtraCacheClearResult(audio.success && image.success, audio.freedBytes + image.freedBytes, 0,
                        audio.deletedFiles + image.deletedFiles)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                NPLogger.w("NERI-Player", "Storage cache clear failed", error)
                PlayerCacheClearOutcome(completed.copy(success = false), retentionUnavailable)
            }
        }
    }
