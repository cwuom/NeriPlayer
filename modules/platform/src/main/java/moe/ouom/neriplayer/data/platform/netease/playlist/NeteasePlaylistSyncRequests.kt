package moe.ouom.neriplayer.data.platform.netease.playlist

import moe.ouom.neriplayer.api.netease.client.NeteaseClient
import moe.ouom.neriplayer.core.logging.NPLogger
import java.util.LinkedHashSet

internal const val NETEASE_PLAYLIST_ADD_BATCH_SIZE = 50
internal const val NETEASE_SONG_DETAIL_BATCH_SIZE = 300
internal const val NETEASE_COMPARE_FAILED_MESSAGE =
    "网易云云端比对失败，已停止同步以避免误同步"

internal fun fetchNeteasePlaylistTrackSnapshot(
    client: NeteaseClient,
    playlistId: Long
): NeteasePlaylistTrackSnapshot {
    val raw = runCatching { client.getPlaylistDetail(playlistId) }
        .getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "getPlaylistDetail failed: ${error.message}", error)
            return NeteasePlaylistTrackSnapshot(
                trackIds = emptySet(),
                fingerprints = emptySet(),
                compareSucceeded = false,
                message = NETEASE_COMPARE_FAILED_MESSAGE
            )
        }
    val retriedRaw = if (parseNeteaseCode(raw) == 301 && client.hasLogin()) {
        retryNeteaseSessionRequest(
            client = client,
            ensureFailureMessage = "ensureWeapiSession retry failed",
            retryFailureMessage = "getPlaylistDetail retry failed"
        ) { client.getPlaylistDetail(playlistId) }
            ?: return NeteasePlaylistTrackSnapshot(
                trackIds = emptySet(),
                fingerprints = emptySet(),
                compareSucceeded = false,
                message = NETEASE_COMPARE_FAILED_MESSAGE
            )
    } else {
        raw
    }

    val parsed = parseNeteaseTrackIdsFromPlaylistDetail(retriedRaw)
    if (!parsed.success) {
        return NeteasePlaylistTrackSnapshot(
            trackIds = emptySet(),
            fingerprints = emptySet(),
            compareSucceeded = false,
            message = NETEASE_COMPARE_FAILED_MESSAGE
        )
    }
    if (parsed.trackIds.isEmpty() && parsed.trackCount > 0) {
        NPLogger.w(
            "LocalPlaylistRepo",
            "Playlist detail returned empty trackIds but trackCount=${parsed.trackCount} for playlistId=$playlistId"
        )
        return NeteasePlaylistTrackSnapshot(
            trackIds = emptySet(),
            fingerprints = emptySet(),
            compareSucceeded = false,
            message = NETEASE_COMPARE_FAILED_MESSAGE
        )
    }

    val detailSummary = fetchNeteaseLikedSongDetailSummaryByPages(client, parsed.trackIds)
    return NeteasePlaylistTrackSnapshot(
        trackIds = LinkedHashSet(parsed.trackIds),
        fingerprints = detailSummary.fingerprints,
        compareSucceeded = true
    )
}

internal fun addNeteasePlaylistSongIdsBatch(
    client: NeteaseClient,
    playlistId: Long,
    songIds: List<Long>
): Boolean {
    if (songIds.isEmpty()) return true
    val raw = runCatching { client.addSongsToPlaylist(playlistId, songIds) }
        .getOrElse { error ->
            NPLogger.e(
                "LocalPlaylistRepo",
                "addSongsToPlaylist failed for playlistId=$playlistId: ${error.message}",
                error
            )
            return false
        }
    val code = parseNeteaseCode(raw)
    if (code == 200) return true
    if (code == 301 && client.hasLogin()) {
        val retry = retryNeteaseSessionRequest(
            client = client,
            ensureFailureMessage = "ensureWeapiSession retry failed",
            retryFailureMessage = "addSongsToPlaylist retry failed for playlistId=$playlistId"
        ) { client.addSongsToPlaylist(playlistId, songIds) } ?: return false
        return parseNeteaseCode(retry) == 200
    }
    NPLogger.w(
        "LocalPlaylistRepo",
        "addSongsToPlaylist returned code=$code for playlistId=$playlistId, size=${songIds.size}"
    )
    return false
}

internal fun validateNeteaseSyncCandidates(
    client: NeteaseClient,
    summary: LocalNeteaseCandidateSummary
): NeteaseCandidateValidationResult {
    if (summary.candidates.isEmpty()) {
        return NeteaseCandidateValidationResult(
            supportedSongs = 0,
            skippedUnsupported = summary.skippedUnsupported,
            skippedExisting = summary.skippedExisting,
            candidates = emptyList()
        )
    }

    val validatedCandidates = ArrayList<NeteaseResolvedCandidate>(summary.candidates.size)
    var skippedUnsupported = summary.skippedUnsupported
    summary.candidates.chunked(NETEASE_SONG_DETAIL_BATCH_SIZE).forEachIndexed { pageIndex, chunk ->
        val resolvedIds = fetchResolvableNeteaseSongIds(
            client = client,
            ids = chunk.map(NeteaseResolvedCandidate::neteaseId),
            logLabel = "validateNeteaseSyncCandidates page ${pageIndex + 1}"
        )
        if (resolvedIds == null) {
            validatedCandidates.addAll(chunk)
            return@forEachIndexed
        }

        chunk.forEach { candidate ->
            if (candidate.neteaseId in resolvedIds) {
                validatedCandidates += candidate
            } else {
                skippedUnsupported += 1
                NPLogger.w(
                    "LocalPlaylistRepo",
                    "Filtered invalid netease songId before sync: songId=${candidate.neteaseId} name=${candidate.song.name}"
                )
            }
        }
    }

    return NeteaseCandidateValidationResult(
        supportedSongs = validatedCandidates.size,
        skippedUnsupported = skippedUnsupported,
        skippedExisting = summary.skippedExisting,
        candidates = validatedCandidates
    )
}

internal fun fetchNeteaseLikedSongDetailSummaryByPages(
    client: NeteaseClient,
    trackIds: List<Long>
): NeteaseSongDetailSummary {
    if (trackIds.isEmpty()) {
        return NeteaseSongDetailSummary(
            ids = emptySet(),
            fingerprints = emptySet()
        )
    }

    val resolvedIds = LinkedHashSet<Long>(trackIds.size)
    val fingerprints = mutableSetOf<String>()
    trackIds.chunked(NETEASE_SONG_DETAIL_BATCH_SIZE).forEachIndexed { pageIndex, ids ->
        val raw = runCatching { client.getSongDetail(ids) }
            .getOrElse { error ->
                NPLogger.e(
                    "LocalPlaylistRepo",
                    "getSongDetail page ${pageIndex + 1} failed: ${error.message}",
                    error
                )
                return@forEachIndexed
            }
        val parsed = parseNeteaseSongDetailSummary(raw)
        if (!parsed.success) {
            NPLogger.w(
                "LocalPlaylistRepo",
                "getSongDetail page ${pageIndex + 1} returned invalid payload"
            )
            return@forEachIndexed
        }
        resolvedIds.addAll(parsed.ids)
        fingerprints.addAll(parsed.fingerprints)
    }
    return NeteaseSongDetailSummary(
        ids = resolvedIds,
        fingerprints = fingerprints
    )
}

internal fun fetchResolvableNeteaseSongIds(
    client: NeteaseClient,
    ids: List<Long>,
    logLabel: String
): Set<Long>? {
    if (ids.isEmpty()) return emptySet()

    fun requestSongDetail(): String {
        return client.getSongDetail(ids)
    }

    val raw = runCatching { requestSongDetail() }
        .getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "$logLabel failed: ${error.message}", error)
            return null
        }

    val retriedRaw = if (parseNeteaseCode(raw) == 301 && client.hasLogin()) {
        retryNeteaseSessionRequest(
            client = client,
            ensureFailureMessage = "$logLabel ensureWeapiSession retry failed",
            retryFailureMessage = "$logLabel retry failed"
        ) { requestSongDetail() } ?: return null
    } else {
        raw
    }

    val parsed = parseNeteaseSongDetailSummary(retriedRaw)
    if (!parsed.success) {
        NPLogger.w("LocalPlaylistRepo", "$logLabel returned invalid payload")
        return null
    }
    return parsed.ids
}

internal fun resolveLikedNeteasePlaylistId(client: NeteaseClient): Long? {
    val raw = runCatching { client.getLikedPlaylistId(0) }
        .getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "getLikedPlaylistId failed: ${error.message}", error)
            return null
        }
    if (parseNeteaseCode(raw) == 301 && client.hasLogin()) {
        val retried = retryNeteaseSessionRequest(
            client = client,
            ensureFailureMessage = "ensureWeapiSession retry failed",
            retryFailureMessage = "getLikedPlaylistId retry failed"
        ) { client.getLikedPlaylistId(0) } ?: return null
        return parseNeteaseLikedPlaylistId(retried).playlistId
    }
    return parseNeteaseLikedPlaylistId(raw).playlistId
}

private fun retryNeteaseSessionRequest(
    client: NeteaseClient,
    ensureFailureMessage: String,
    retryFailureMessage: String,
    request: () -> String
): String? {
    runCatching { client.ensureWeapiSession() }.onFailure {
        NPLogger.w("LocalPlaylistRepo", "$ensureFailureMessage: ${it.message}")
    }
    return runCatching(request).onFailure { error ->
        NPLogger.e("LocalPlaylistRepo", "$retryFailureMessage: ${error.message}", error)
    }.getOrNull()
}

internal fun buildNeteasePlaylistSyncPlan(
    client: NeteaseClient,
    targetPlaylistId: Long,
    totalSongs: Int,
    validatedSummary: NeteaseCandidateValidationResult,
    allSyncedMessage: () -> String?
): NeteaseRemotePlaylistSyncPlan {
    val targetSnapshot = fetchNeteasePlaylistTrackSnapshot(client, targetPlaylistId)
    if (!targetSnapshot.compareSucceeded) {
        return NeteaseRemotePlaylistSyncPlan(
            targetPlaylistId = targetPlaylistId,
            totalSongs = totalSongs,
            supportedSongs = validatedSummary.supportedSongs,
            skippedUnsupported = validatedSummary.skippedUnsupported,
            skippedExisting = validatedSummary.skippedExisting,
            candidates = emptyList(),
            compareSucceeded = false,
            message = targetSnapshot.message ?: NETEASE_COMPARE_FAILED_MESSAGE
        )
    }

    var skippedExisting = validatedSummary.skippedExisting
    val pendingCandidates = ArrayList<NeteaseResolvedCandidate>(validatedSummary.candidates.size)
    validatedSummary.candidates.forEach { candidate ->
        val fingerprint = candidate.song.toNeteaseFingerprint()
        if (candidate.neteaseId in targetSnapshot.trackIds ||
            (fingerprint != null && fingerprint in targetSnapshot.fingerprints)
        ) {
            skippedExisting += 1
        } else {
            pendingCandidates += candidate
        }
    }

    val message = if (pendingCandidates.isEmpty()) {
        allSyncedMessage()
    } else {
        null
    }

    return NeteaseRemotePlaylistSyncPlan(
        targetPlaylistId = targetPlaylistId,
        totalSongs = totalSongs,
        supportedSongs = validatedSummary.supportedSongs,
        skippedUnsupported = validatedSummary.skippedUnsupported,
        skippedExisting = skippedExisting,
        candidates = pendingCandidates,
        compareSucceeded = true,
        message = message
    )
}

internal fun reconcileNeteasePlaylistAddResult(
    client: NeteaseClient,
    targetPlaylistId: Long,
    result: NeteasePlaylistBatchAddResult
): NeteasePlaylistBatchAddResult {
    val addedIds = LinkedHashSet(result.addedIds)
    val failedIds = LinkedHashSet(result.failedIds)
    if (failedIds.isNotEmpty()) {
        val snapshot = fetchNeteasePlaylistTrackSnapshot(client, targetPlaylistId)
        if (snapshot.compareSucceeded) {
            val recovered = failedIds.filter { it in snapshot.trackIds }
            addedIds.addAll(recovered)
            failedIds.removeAll(recovered.toSet())
        }
    }
    return NeteasePlaylistBatchAddResult(addedIds = addedIds, failedIds = failedIds)
}
