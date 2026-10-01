package moe.ouom.neriplayer.data.platform.netease.playlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.api.netease.client.NeteaseClient
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncPlan
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseRemotePlaylist
import java.io.IOException

enum class NeteasePlaylistSyncMessage {
    EMPTY_SONGS,
    NO_SUPPORTED_SONGS,
    LOGIN_REQUIRED,
    ALL_SYNCED
}

class NeteasePlaylistSync(
    private val message: (NeteasePlaylistSyncMessage) -> String?
) {
    fun filterNeteaseLikeSyncCandidates(songs: List<SongItem>): List<SongItem> {
        return buildLocalNeteaseCandidates(songs).candidates.map { it.song }
    }

    fun filterNeteaseLikeSyncCandidatesPreservingDuplicates(songs: List<SongItem>): List<SongItem> {
        return resolveLocalNeteaseCandidates(songs).map(NeteaseResolvedCandidate::song)
    }

    suspend fun fetchNeteaseRemotePlaylists(client: NeteaseClient): List<NeteaseRemotePlaylist> {
        return withContext(Dispatchers.IO) {
            if (!client.hasLogin()) {
                throw IOException(message(NeteasePlaylistSyncMessage.LOGIN_REQUIRED))
            }
            runCatching { client.ensureWeapiSession() }.onFailure {
                NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession failed: ${it.message}")
            }
            val uid = client.getCurrentUserId()
            parseNeteaseRemotePlaylists(
                raw = client.getUserPlaylists(uid, offset = 0, limit = 1000),
                ownerUserId = uid
            )
        }
    }

    suspend fun prepareNeteaseLikeSyncPlan(
        client: NeteaseClient,
        songs: List<SongItem>
    ): NeteaseLikeSyncPlan {
        return withContext(Dispatchers.IO) {
            if (songs.isEmpty()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = 0,
                    supportedSongs = 0,
                    skippedUnsupported = 0,
                    skippedExisting = 0,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = message(NeteasePlaylistSyncMessage.EMPTY_SONGS)
                )
            }

            val localSummary = buildLocalNeteaseCandidates(songs)
            if (localSummary.candidates.isEmpty()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = localSummary.supportedSongs,
                    skippedUnsupported = localSummary.skippedUnsupported,
                    skippedExisting = localSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = message(NeteasePlaylistSyncMessage.NO_SUPPORTED_SONGS)
                )
            }

            if (!client.hasLogin()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = localSummary.supportedSongs,
                    skippedUnsupported = localSummary.skippedUnsupported,
                    skippedExisting = localSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = message(NeteasePlaylistSyncMessage.LOGIN_REQUIRED)
                )
            }

            runCatching { client.ensureWeapiSession() }.onFailure {
                NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession failed: ${it.message}")
            }

            val validatedSummary = validateNeteaseSyncCandidates(client, localSummary)
            if (validatedSummary.candidates.isEmpty()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = validatedSummary.supportedSongs,
                    skippedUnsupported = validatedSummary.skippedUnsupported,
                    skippedExisting = validatedSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = message(NeteasePlaylistSyncMessage.NO_SUPPORTED_SONGS)
                )
            }

            val targetPlaylistId = resolveLikedNeteasePlaylistId(client)
                ?: return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = validatedSummary.supportedSongs,
                    skippedUnsupported = validatedSummary.skippedUnsupported,
                    skippedExisting = validatedSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = NETEASE_COMPARE_FAILED_MESSAGE
                )

            buildNeteasePlaylistSyncPlan(
                client = client,
                targetPlaylistId = targetPlaylistId,
                totalSongs = songs.size,
                validatedSummary = validatedSummary,
                allSyncedMessage = { message(NeteasePlaylistSyncMessage.ALL_SYNCED) }
            ).toLikeSyncPlan()
        }
    }

    suspend fun prepareNeteasePlaylistSyncPlan(
        client: NeteaseClient,
        targetPlaylistId: Long,
        songs: List<SongItem>
    ): NeteaseLikeSyncPlan {
        return withContext(Dispatchers.IO) {
            if (targetPlaylistId <= 0L) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = 0,
                    skippedUnsupported = 0,
                    skippedExisting = 0,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = NETEASE_COMPARE_FAILED_MESSAGE
                )
            }
            if (songs.isEmpty()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = 0,
                    supportedSongs = 0,
                    skippedUnsupported = 0,
                    skippedExisting = 0,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = message(NeteasePlaylistSyncMessage.EMPTY_SONGS)
                )
            }

            val localSummary = buildLocalNeteaseCandidates(songs)
            if (localSummary.candidates.isEmpty()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = localSummary.supportedSongs,
                    skippedUnsupported = localSummary.skippedUnsupported,
                    skippedExisting = localSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = message(NeteasePlaylistSyncMessage.NO_SUPPORTED_SONGS)
                )
            }

            if (!client.hasLogin()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = localSummary.supportedSongs,
                    skippedUnsupported = localSummary.skippedUnsupported,
                    skippedExisting = localSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = message(NeteasePlaylistSyncMessage.LOGIN_REQUIRED)
                )
            }

            runCatching { client.ensureWeapiSession() }.onFailure {
                NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession failed: ${it.message}")
            }

            val validatedSummary = validateNeteaseSyncCandidates(client, localSummary)
            if (validatedSummary.candidates.isEmpty()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = validatedSummary.supportedSongs,
                    skippedUnsupported = validatedSummary.skippedUnsupported,
                    skippedExisting = validatedSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = message(NeteasePlaylistSyncMessage.NO_SUPPORTED_SONGS)
                )
            }

            buildNeteasePlaylistSyncPlan(
                client = client,
                targetPlaylistId = targetPlaylistId,
                totalSongs = songs.size,
                validatedSummary = validatedSummary,
                allSyncedMessage = { message(NeteasePlaylistSyncMessage.ALL_SYNCED) }
            ).toLikeSyncPlan()
        }
    }

    suspend fun syncSongsToNeteaseLiked(
        client: NeteaseClient,
        songs: List<SongItem>
    ): NeteaseLikeSyncResult {
        return withContext(Dispatchers.IO) {
            if (songs.isEmpty()) {
                return@withContext NeteaseLikeSyncResult(
                    totalSongs = 0,
                    supportedSongs = 0,
                    skippedUnsupported = 0,
                    skippedExisting = 0,
                    added = 0,
                    failed = 0,
                    message = message(NeteasePlaylistSyncMessage.EMPTY_SONGS)
                )
            }

            val targetPlaylistId = resolveLikedNeteasePlaylistId(client)
                ?: return@withContext NeteaseLikeSyncResult(
                    totalSongs = songs.size,
                    supportedSongs = 0,
                    skippedUnsupported = 0,
                    skippedExisting = 0,
                    added = 0,
                    failed = 0,
                    message = NETEASE_COMPARE_FAILED_MESSAGE
                )

            syncSongsToNeteasePlaylist(client, targetPlaylistId, songs)
        }
    }

    suspend fun syncSongsToNeteasePlaylist(
        client: NeteaseClient,
        targetPlaylistId: Long,
        songs: List<SongItem>
    ): NeteaseLikeSyncResult {
        return withContext(Dispatchers.IO) {
            val plan = prepareNeteasePlaylistSyncPlan(client, targetPlaylistId, songs)
            if (songs.isEmpty()) {
                return@withContext plan.toLikeSyncResult(targetPlaylistId)
            }

            if (!plan.compareSucceeded) {
                return@withContext plan.toLikeSyncResult(targetPlaylistId)
            }

            val candidates = buildLocalNeteaseCandidates(plan.pendingSongs).candidates

            if (candidates.isEmpty()) {
                return@withContext plan.toLikeSyncResult(targetPlaylistId)
            }

            val addResult = addNeteasePlaylistSongIdsInBatches(
                songIds = candidates.map(NeteaseResolvedCandidate::neteaseId),
                batchSize = NETEASE_PLAYLIST_ADD_BATCH_SIZE
            ) { ids ->
                addNeteasePlaylistSongIdsBatch(client, targetPlaylistId, ids)
            }
            val reconciled = reconcileNeteasePlaylistAddResult(client, targetPlaylistId, addResult)
            val failedSongResolution = classifyNeteasePlaylistAddFailures(
                failedIds = reconciled.failedIds,
                batchSize = NETEASE_SONG_DETAIL_BATCH_SIZE
            ) { ids ->
                fetchResolvableNeteaseSongIds(
                    client = client,
                    ids = ids,
                    logLabel = "resolveFailedNeteaseSongIds"
                )
            }

            plan.toLikeSyncResult(
                targetPlaylistId = targetPlaylistId,
                added = reconciled.addedIds.size,
                failed = failedSongResolution.unresolvedFailedIds.size,
                skippedUnsupported = plan.skippedUnsupported + failedSongResolution.skippedUnsupported
            )
        }
    }
}
