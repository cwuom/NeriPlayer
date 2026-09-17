package moe.ouom.neriplayer.core.download.manager.runtime

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.upsertDownloadedSongCatalog
import moe.ouom.neriplayer.core.download.catalog.ManagedLibraryItemRoomStore
import moe.ouom.neriplayer.core.download.manager.batch.scheduleCatalogReconcile
import moe.ouom.neriplayer.core.download.manager.catalog.publishDownloadedSongs
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.isFinalizedDownloadedAudioEntry
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.FinalizedManagedAudioSnapshot
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.PlayableManagedAudioSnapshot
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.CatalogPublishMode
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.ReleasableSongExecutionLockScope
import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationResult
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.isReadableManagedAudioPlaybackAllowed
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.remoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.model.stableKey
import java.security.MessageDigest


internal suspend fun <T> GlobalDownloadManager.withSongExecutionLock(
    songKey: String,
    releasable: Boolean,
    block: suspend ReleasableSongExecutionLockScope.() -> T
): T {
    check(releasable) { "releasable lock mode must be explicitly enabled" }
    val mutex = songExecutionMutex(songKey)
    mutex.lock()
    val scope = ReleasableSongExecutionLockScope(mutex)
    return try {
        scope.block()
    } finally {
        withContext(NonCancellable) {
            scope.close()
        }
    }
}

internal suspend fun GlobalDownloadManager.awaitSongCancellationSettled(
    songKey: String,
    timeoutMs: Long = DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS,
    clearCancellationWhenSettled: Boolean = true,
    logProgress: Boolean = true,
    operationIds: Collection<String> = emptySet()
): Boolean {
    val normalizedOperationIds = operationIds
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    fun isTargetActive(): Boolean {
        return if (normalizedOperationIds.isEmpty()) {
            AudioDownloadManager.isSongDownloadActive(songKey)
        } else {
            normalizedOperationIds.any(AudioDownloadManager::isOperationDownloadActive)
        }
    }
    if (!isSongCancelled(songKey) && !isTargetActive()) {
        return true
    }
    if (logProgress) {
        NPLogger.d(
            TAG,
            "等待歌曲取消状态收敛: songKey=$songKey, " +
                "cancelled=${isSongCancelled(songKey)}, " +
                "active=${isTargetActive()}, " +
                "scoped=${normalizedOperationIds.size}"
        )
    }

    val deadlineAt = System.currentTimeMillis() + timeoutMs
    while (isTargetActive() && System.currentTimeMillis() < deadlineAt) {
        delay(50)
    }
    if (isTargetActive()) {
        if (logProgress) {
            NPLogger.w(TAG, "等待取消中的下载清理超时: songKey=$songKey")
        }
        return false
    }
    if (logProgress) {
        NPLogger.d(
            TAG,
            "歌曲取消状态已收敛: songKey=$songKey, " +
                "cancelledBeforeClear=${isSongCancelled(songKey)}"
        )
    }
    if (clearCancellationWhenSettled) {
        clearSongCancelled(songKey)
    }
    return true
}

internal fun GlobalDownloadManager.songExecutionMutex(songKey: String): Mutex {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(songKey.toByteArray(Charsets.UTF_8))
    val stripeValue = ((digest[0].toLong() and 0xffL) shl 24) or
        ((digest[1].toLong() and 0xffL) shl 16) or
        ((digest[2].toLong() and 0xffL) shl 8) or
        (digest[3].toLong() and 0xffL)
    val index = (stripeValue % songExecutionLocks.size).toInt()
    return songExecutionLocks[index]
}

internal suspend fun GlobalDownloadManager.resolveStoredAudio(
    context: Context,
    song: SongItem
): ManagedDownloadStorage.StoredEntry? {
    resolveStoredAudio(context, resolveSongLocation(song))?.let { return it }
    return ManagedDownloadStorage.findDownloadedAudio(context, song)
}

internal fun GlobalDownloadManager.resolveFinalizedManagedAudioSnapshot(
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
    candidate: ManagedDownloadStorage.StoredEntry
): FinalizedManagedAudioSnapshot? {
    val currentAudio = listOfNotNull(
        candidate.reference,
        candidate.mediaUri,
        candidate.localFilePath
    ).firstNotNullOfOrNull(snapshot.audioEntriesByLookupKey::get)
        ?: return null
    val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, currentAudio)
        ?: return null
    if (!isFinalizedDownloadedAudioEntry(
            rootEntriesComplete = snapshot.rootEntriesComplete,
            isPendingAudioWrite = currentAudio.isPendingAudioWrite,
            metadata = metadata
        )
    ) {
        return null
    }
    return FinalizedManagedAudioSnapshot(
        snapshot = snapshot,
        audio = currentAudio,
        metadata = metadata
    )
}

internal fun GlobalDownloadManager.resolvePlayableManagedAudioSnapshot(
    context: Context,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
    candidate: ManagedDownloadStorage.StoredEntry,
    song: SongItem
): PlayableManagedAudioSnapshot? {
    val references = listOfNotNull(
        candidate.reference.takeIf(String::isNotBlank),
        candidate.mediaUri.takeIf(String::isNotBlank),
        candidate.localFilePath?.takeIf(String::isNotBlank)
    )
    val candidateEntries = sequence {
        references.forEach { reference ->
            snapshot.audioEntriesByLookupKey[reference]?.let { yield(it) }
            snapshot.pendingAudioEntries.firstOrNull { entry ->
                entry.reference == reference ||
                    entry.mediaUri == reference ||
                    entry.localFilePath == reference
            }?.let { yield(it) }
        }
        ManagedDownloadStorage.findDownloadedAudio(snapshot, song)?.let { yield(it) }
        ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song)?.let { yield(it) }
    }.distinctBy(ManagedDownloadStorage.StoredEntry::reference)
    val currentAudio = candidateEntries.firstOrNull { audio ->
        val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
        metadata == null || isDownloadedMetadataIdentityCompatible(song, metadata)
    } ?: return null
    val playbackReference = ManagedDownloadStorage.resolveStoredEntryPlaybackUri(
        entry = currentAudio,
        allowPending = currentAudio.isPendingAudioWrite
    ) ?: return null
    if (
        ManagedDownloadReferenceLookup.inspect(context, playbackReference) !=
            ManagedDownloadReferenceLookup.Result.Present
    ) {
        return null
    }
    val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, currentAudio)
    if (
        !isReadableManagedAudioPlaybackAllowed(
            audioIsPending = currentAudio.isPendingAudioWrite,
            downloadActive = false,
            downloadCancelled = false,
            metadata = metadata,
            allowLegacyPublishedAudio = !currentAudio.isPendingAudioWrite
        )
    ) {
        return null
    }
    return PlayableManagedAudioSnapshot(
        snapshot = snapshot,
        audio = currentAudio,
        metadata = metadata
    )
}

internal fun GlobalDownloadManager.isDownloadedMetadataIdentityCompatible(
    song: SongItem,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata
): Boolean {
    val expectedRemoteIdentity = song.remoteSourceIdentityOrNull() ?: return true
    val metadataStableKey = metadata.stableKey
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return true
    val expectedKeys = buildSet {
        add(song.stableKey())
        song.sourceStableKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
    }
    if (metadataStableKey in expectedKeys) {
        return true
    }
    val metadataSong = SongItem(
        id = metadata.songId ?: song.id,
        name = metadata.name ?: song.name,
        artist = metadata.artist ?: song.artist,
        album = metadata.album ?: metadata.identityAlbum ?: song.album,
        albumId = 0L,
        durationMs = metadata.durationMs.takeIf { it > 0L } ?: song.durationMs,
        coverUrl = metadata.coverUrl,
        mediaUri = metadata.mediaUri,
        channelId = metadata.channelId,
        audioId = metadata.audioId,
        subAudioId = metadata.subAudioId,
        sourceStableKey = metadataStableKey
    )
    return metadataSong.remoteSourceIdentityOrNull() == expectedRemoteIdentity
}

internal suspend fun GlobalDownloadManager.resolveStoredAudio(
    context: Context,
    reference: String?
): ManagedDownloadStorage.StoredEntry? {
    val normalized = reference?.takeIf { it.isNotBlank() } ?: return null
    return ManagedDownloadStorage.queryStoredEntry(context, normalized)
}

internal fun GlobalDownloadManager.publishCompletedDownloadOptimistically(
    context: Context,
    song: SongItem,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null,
    state: String = "FINALIZED"
) {
    LocalMediaSupport.invalidateSongAssetCaches(song)
    val optimisticSong = buildOptimisticDownloadedSong(
        song = song,
        storedAudio = storedAudio,
        sidecarReferences = sidecarReferences
    )
    publishOptimisticDownloadedSongs(
        context = context,
        songs = listOf(optimisticSong)
    )
    scope.launch {
        val appContext = context.applicationContext
        runCatching {
            ManagedLibraryItemRoomStore.upsert(
                context = appContext,
                song = song,
                audio = storedAudio,
                state = state
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "更新 managed_library_item 预览失败: ${error.message}")
        }
        upsertCompletedFastIndexEntry(
            context = appContext,
            song = song,
            storedAudio = storedAudio,
            state = state,
            coverPath = optimisticSong.coverPath
        )
    }
}

internal suspend fun GlobalDownloadManager.updateFastIndexAfterMetadataEdit(
    context: Context,
    song: SongItem,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    state: String,
    coverPath: String?
) {
    val stableKey = song.stableKey()
    val updatedAtMs = System.currentTimeMillis()
    val result = runCatching {
        ManagedDownloadStorage.updateExistingFastIndexEntry(
            context = context,
            stableKey = stableKey
        ) { existing ->
            existing.copy(
                audioName = storedAudio.logicalName,
                audioReference = storedAudio.reference,
                metadataName = "${storedAudio.logicalName}$METADATA_SUFFIX",
                state = state,
                downloadTimeMs = existing.downloadTimeMs
                    ?: storedAudio.lastModifiedMs.takeIf { it > 0L },
                updatedAtMs = updatedAtMs,
                songId = song.id.takeIf { it > 0L } ?: existing.songId,
                title = song.name,
                artist = song.artist,
                album = song.album,
                mediaUri = song.mediaUri ?: existing.mediaUri,
                channelId = song.channelId ?: existing.channelId,
                audioId = song.audioId ?: existing.audioId,
                subAudioId = song.subAudioId ?: existing.subAudioId,
                playlistContextId = song.playlistContextId
                    ?: existing.playlistContextId,
                durationMs = song.durationMs.takeIf { it > 0L }
                    ?: existing.durationMs,
                coverPath = coverPath
            )
        }
    }.getOrElse { error ->
        ManagedLibraryFastIndexMutationResult.Failed(
            shard = "",
            error = error
        )
    }
    when (result) {
        is ManagedLibraryFastIndexMutationResult.EntryMissing -> {
            upsertCompletedFastIndexEntry(
                context = context,
                song = song,
                storedAudio = storedAudio,
                state = state,
                coverPath = coverPath
            )
        }
        is ManagedLibraryFastIndexMutationResult.Failed -> {
            reportFastIndexFailure(context, song, result)
        }
        else -> Unit
    }
}

internal suspend fun GlobalDownloadManager.upsertCompletedFastIndexEntry(
    context: Context,
    song: SongItem,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    state: String,
    coverPath: String?
) {
    val result = runCatching {
        ManagedDownloadStorage.upsertCompleteFastIndexEntry(
            context = context,
            song = song,
            audio = storedAudio,
            state = state,
            coverPath = coverPath
        )
    }.getOrElse { error ->
        ManagedLibraryFastIndexMutationResult.Failed(
            shard = "",
            error = error
        )
    }
    if (result is ManagedLibraryFastIndexMutationResult.Failed) {
        reportFastIndexFailure(context, song, result)
    }
}

internal fun GlobalDownloadManager.reportFastIndexFailure(
    context: Context,
    song: SongItem,
    result: ManagedLibraryFastIndexMutationResult.Failed
) {
    NPLogger.w(
        TAG,
        "更新 managed library 快速索引失败: song=${song.name}, " +
            "error=${result.error.message}"
    )
    scheduleCatalogReconcile(context, forceRefresh = true)
}

internal fun GlobalDownloadManager.publishOptimisticDownloadedSongs(
    context: Context,
    songs: List<DownloadedSong>
) {
    if (songs.isEmpty()) {
        return
    }

    synchronized(downloadedSongCatalogMutationLock) {
        var mergedSongs = downloadedSongsMutable.value
        songs.forEach { song ->
            mergedSongs = upsertDownloadedSongCatalog(mergedSongs, song)
        }
        if (mergedSongs != downloadedSongsMutable.value) {
            publishDownloadedSongs(
                context = context,
                songs = mergedSongs,
                persistCatalog = true,
                catalogPublishMode = CatalogPublishMode.DELTA
            )
        }
    }
}
