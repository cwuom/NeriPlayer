package moe.ouom.neriplayer.data.local.playlist

import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.PlaylistLoadResult
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.ParsedPlaylistCandidate
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.audioimport.localSongNewestFirstComparator
import moe.ouom.neriplayer.data.local.audioimport.localSongSourceCreationComparator
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomShadowImportStatus
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.toSyncableRemoteSongOrNull
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.model.normalizedSyncCausalTokens
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest

internal fun LocalPlaylistRepository.completeInitialLoad() {
    try {
        loadFromDisk()
        _initializationReadyFlow.value = true
        initialLoad.complete(Unit)
    } catch (error: Exception) {
        NPLogger.e("LocalPlaylistRepo", "Failed to load playlists", error)
        initialLoadFailure = error
        initialLoad.complete(Unit)
    }
}

internal fun LocalPlaylistRepository.loadFromDisk() {
    val roomPrimary = readRoomPrimary()
    if (roomPrimary != null) {
        LegacyJsonCleanupScheduler.schedule(context, "local-playlist-room-load")
        recoverPendingSyncMutation(
            committedDomainDigest = LocalPlaylistRoomStore.domainDigest(roomPrimary)
        )
        val normalizedRoomPrimary = normalizeRoomPrimaryLocalFilesCover(roomPrimary)
        if (normalizedRoomPrimary != roomPrimary) {
            runCatching {
                runBlocking {
                    roomStore?.writeIncremental(
                        previous = roomPrimary,
                        next = normalizedRoomPrimary,
                        sourceDigest = LocalPlaylistRoomStore.domainDigest(normalizedRoomPrimary)
                    )
                }
            }.onFailure { error ->
                NPLogger.w(
                    "LocalPlaylistRepo",
                    "Failed to persist normalized Room playlists",
                    error
                )
            }
        }
        _playlists.value = normalizedRoomPrimary
        _playlistCount.value = normalizedRoomPrimary.size
        return
    }

    val loadResult = readStoredPlaylists()
    val committedPlaylists = loadResult.playlists
    val committedDomainDigest = LocalPlaylistRoomStore.domainDigest(committedPlaylists)
    recoverPendingSyncMutation(committedDomainDigest)

    var roomPromotedDuringLoad = false
    if (roomStorageEnabled && roomStore != null) {
        val activeRoomStore = roomStore
        val imported = runCatching {
            runBlocking {
                activeRoomStore.importLegacyAndPromote(
                    playlists = committedPlaylists,
                    sourceDigest = committedDomainDigest
                )
            }
        }.onFailure { error ->
            roomStorageEnabled = false
            NPLogger.e(
                "LocalPlaylistRepo",
                "Failed to promote legacy playlists to Room; JSON remains authoritative",
                error
            )
        }.getOrNull()
        if (imported?.status == LocalPlaylistRoomShadowImportStatus.SKIPPED_NOT_EQUIVALENT) {
            roomStorageEnabled = false
            NPLogger.w(
                "LocalPlaylistRepo",
                "Room mapper is not equivalent; keep legacy playlist storage"
            )
        } else if (imported?.status == LocalPlaylistRoomShadowImportStatus.IMPORTED) {
            roomPromotedDuringLoad = true
            LegacyJsonCleanupScheduler.schedule(context, "local-playlist-import")
        }
    }

    if (
        shouldRewriteLegacyPlaylistsAfterInitialLoad(
            migrationRequired = loadResult.migrationRequired,
            allowMigrationWrite = loadResult.allowMigrationWrite,
            roomPromotedDuringLoad = roomPromotedDuringLoad
        )
    ) {
        runCatching {
            persistToDisk(loadResult.playlists)
        }.onFailure { error ->
            NPLogger.e("LocalPlaylistRepo", "Failed to persist normalized playlists", error)
        }
    }
    _playlists.value = loadResult.playlists
    _playlistCount.value = loadResult.playlists.size
}

internal fun LocalPlaylistRepository.readRoomPrimary(): List<LocalPlaylist>? {
    if (!roomStorageEnabled || roomStore == null) {
        return null
    }
    val activeRoomStore = roomStore
    return runCatching {
        runBlocking {
            activeRoomStore.readIfRoomPrimary()
        }
    }.onFailure { error ->
        roomStorageEnabled = false
        NPLogger.e(
            "LocalPlaylistRepo",
            "Failed to read Room playlists; falling back to legacy storage",
            error
        )
    }.getOrNull()
}

internal fun LocalPlaylistRepository.readStoredPlaylists(): PlaylistLoadResult {
    val primaryRead = runCatching(storage::readPrimary)
    val primaryText = primaryRead.getOrNull()
    if (primaryRead.isSuccess && primaryText == null) {
        return recoverFromBackup(primaryWasCorrupt = false)
            ?: emptyPlaylistLoadResult(allowMigrationWrite = true)
    }

    if (primaryRead.isFailure) {
        NPLogger.e(
            "LocalPlaylistRepo",
            "Failed to read primary playlist storage",
            primaryRead.exceptionOrNull()
        )
        preserveBackupOnNextWrite = true
        return recoverFromBackup(primaryWasCorrupt = false)
            ?: emptyPlaylistLoadResult(allowMigrationWrite = false)
    }

    val primaryParsed = parsePlaylists(primaryText.orEmpty(), "primary")
    if (primaryParsed != null) {
        return PlaylistLoadResult(
            playlists = primaryParsed.normalized,
            migrationRequired = primaryParsed.normalized != primaryParsed.decoded,
            allowMigrationWrite = true
        )
    }

    return recoverFromBackup(primaryWasCorrupt = true)
        ?: emptyPlaylistLoadResult(allowMigrationWrite = false)
}

internal fun LocalPlaylistRepository.emptyPlaylistLoadResult(allowMigrationWrite: Boolean): PlaylistLoadResult {
    val normalized = normalizePlaylistOrder(emptyList())
    return PlaylistLoadResult(
        playlists = normalized,
        migrationRequired = normalized.isNotEmpty(),
        allowMigrationWrite = allowMigrationWrite
    )
}

internal fun LocalPlaylistRepository.recoverFromBackup(primaryWasCorrupt: Boolean): PlaylistLoadResult? {
    val backupRead = runCatching(storage::readBackup)
    val backupText = backupRead.getOrNull()
    if (backupRead.isFailure) {
        NPLogger.e(
            "LocalPlaylistRepo",
            "Failed to read playlist backup",
            backupRead.exceptionOrNull()
        )
    }

    val backupParsed = backupText?.let { parsePlaylists(it, "backup") }
    if (backupText != null && backupParsed == null) {
        replaceBackupOnNextWrite = true
    }
    val primaryReadyForRestore = if (primaryWasCorrupt) {
        corruptPrimaryNeedsQuarantine = true
        quarantineCorruptPrimary()
    } else {
        true
    }
    if (backupParsed == null) {
        return null
    }

    val repairSucceeded = primaryReadyForRestore &&
        runCatching {
            storage.commit(backupText, rotateBackup = false)
        }.onFailure { error ->
            preserveBackupOnNextWrite = true
            NPLogger.e("LocalPlaylistRepo", "Failed to restore playlist backup", error)
        }.isSuccess
    return PlaylistLoadResult(
        playlists = backupParsed.normalized,
        migrationRequired = backupParsed.normalized != backupParsed.decoded,
        allowMigrationWrite = repairSucceeded
    )
}

internal fun LocalPlaylistRepository.quarantineCorruptPrimary(): Boolean {
    return runCatching(storage::quarantinePrimary)
        .onSuccess { quarantine ->
            corruptPrimaryNeedsQuarantine = false
            if (quarantine != null) {
                NPLogger.w(
                    "LocalPlaylistRepo",
                    "Quarantined corrupt playlist storage: ${quarantine.name}"
                )
            }
        }
        .onFailure { error ->
            preserveBackupOnNextWrite = true
            NPLogger.e("LocalPlaylistRepo", "Failed to quarantine corrupt playlists", error)
        }
        .isSuccess
}

internal fun LocalPlaylistRepository.parsePlaylists(text: String, source: String): ParsedPlaylistCandidate? {
    return runCatching {
        validateLocalPlaylistJson(text, source)
        val type = object : TypeToken<List<LocalPlaylist>>() {}.type
        val decoded = requireNotNull(gson.fromJson<List<LocalPlaylist>>(text, type)) {
            "Playlist $source contains JSON null"
        }
        ParsedPlaylistCandidate(
            decoded = decoded,
            normalized = normalizePlaylistOrder(decoded)
        )
    }.onFailure { error ->
        NPLogger.e("LocalPlaylistRepo", "Failed to parse $source playlists", error)
    }.getOrNull()
}

internal fun LocalPlaylistRepository.parseFastPlaylistPreview(text: String, playlistId: Long): LocalPlaylist? {
    return runCatching {
        JsonReader(StringReader(text)).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                    continue
                }
                reader.beginObject()
                var candidateId: Long? = null
                var targetObject: JsonObject? = null
                val fieldsBeforeId = LinkedHashMap<String, com.google.gson.JsonElement>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name == "id" && reader.peek() == JsonToken.NUMBER) {
                        candidateId = reader.nextLong()
                        if (candidateId == playlistId) {
                            targetObject = JsonObject().apply {
                                addProperty("id", candidateId)
                                fieldsBeforeId.forEach { (key, value) -> add(key, value) }
                            }
                        } else {
                            fieldsBeforeId.clear()
                        }
                        continue
                    }
                    if (candidateId == playlistId) {
                        targetObject?.add(name, JsonParser.parseReader(reader))
                    } else if (candidateId == null) {
                        fieldsBeforeId[name] = JsonParser.parseReader(reader)
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
                if (candidateId == playlistId && targetObject != null) {
                    val playlist = gson.fromJson(targetObject, LocalPlaylist::class.java)
                    return@use playlist.copy(
                    songs = playlist.songs
                        .filterNotNull()
                        .toMutableList()
                    )
                }
            }
            reader.endArray()
            null
        }
    }.onFailure { error ->
        NPLogger.w(
            "LocalPlaylistRepo",
            "Failed to parse fast playlist preview: ${error.message}"
        )
    }.getOrNull()
}

internal fun LocalPlaylistRepository.migratePlaylistSongOrder(playlists: List<LocalPlaylist>): List<LocalPlaylist> {
    if (playlists.isEmpty()) return playlists

    var changed = false
    val migrated = playlists.map { playlist ->
        if (playlist.songOrderVersion >= DISPLAY_ORDER_SONG_ORDER_VERSION) {
            val displaySongs = sortSongsForDisplay(playlist, playlist.songs)
            if (displaySongs == playlist.songs) {
                playlist
            } else {
                changed = true
                playlist.copy(songs = displaySongs)
            }
        } else {
            changed = true
            playlist.copy(
                songs = migrateLegacySongsToDisplayOrder(
                    playlist.songs,
                    playlist.modifiedAt,
                    preserveLogicalCreatedAt = isLocalFilesPlaylist(
                        playlist.id,
                        playlist.name
                    )
                ),
                songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
            )
        }
    }
    return if (changed) migrated else playlists
}

internal fun LocalPlaylistRepository.normalizePlaylistOrder(playlists: List<LocalPlaylist>): List<LocalPlaylist> {
    val normalizedRemoteSources = normalizeRemoteSourcePlaylistEntries(playlists)
    val normalizedMemberships = normalizeSongMembershipTokens(normalizedRemoteSources)
    val migrated = migratePlaylistSongOrder(normalizedMemberships)
    return migratePlaylistSongOrder(
        normalizeSongMembershipTokens(normalizePlaylists(migrated))
    )
}

internal fun LocalPlaylistRepository.normalizeRoomPrimaryLocalFilesCover(
    playlists: List<LocalPlaylist>
): List<LocalPlaylist> {
    var changed = false
    val normalized = playlists.map { playlist ->
        if (isLocalFilesPlaylist(playlist.id, playlist.name) &&
            playlist.customCoverUrl != null
        ) {
            changed = true
            playlist.copy(customCoverUrl = null)
        } else {
            playlist
        }
    }
    return if (changed) normalized else playlists
}

internal fun LocalPlaylistRepository.normalizeRemoteSourcePlaylistEntries(
    playlists: List<LocalPlaylist>
): List<LocalPlaylist> {
    var changed = false
    val normalized = playlists.map { playlist ->
        if (isLocalFilesPlaylist(playlist.id, playlist.name)) {
            playlist
        } else {
            val projectedSongs = projectRemoteSourcePlaylistEntries(playlist.songs)
            if (projectedSongs == playlist.songs) {
                playlist
            } else {
                changed = true
                playlist.copy(songs = projectedSongs)
            }
        }
    }
    return if (changed) normalized else playlists
}

internal fun LocalPlaylistRepository.normalizeSongMembershipTokens(
    playlists: List<LocalPlaylist>
): List<LocalPlaylist> {
    var changed = false
    val normalized = playlists.map { playlist ->
        var playlistChanged = false
        val songs = playlist.songs.mapTo(mutableListOf()) { song ->
            val normalizedTokens = song.syncMembershipTokens.normalizedSyncCausalTokens()
            if (normalizedTokens == song.syncMembershipTokens) {
                song
            } else {
                changed = true
                playlistChanged = true
                song.copy(syncMembershipTokens = normalizedTokens)
            }
        }
        if (playlistChanged) playlist.copy(songs = songs) else playlist
    }
    return if (changed) normalized else playlists
}

internal fun LocalPlaylistRepository.migrateLegacySongsToDisplayOrder(
    songs: List<SongItem>,
    playlistModifiedAt: Long,
    preserveLogicalCreatedAt: Boolean = false
): MutableList<SongItem> {
    if (songs.isEmpty()) return mutableListOf()

    if (preserveLogicalCreatedAt && songs.any { it.logicalCreatedAtMs != null }) {
        return songs.sortedWith(localSongNewestFirstComparator())
            .toMutableList()
    }

    val newestAddedAt = maxOf(
        System.currentTimeMillis(),
        playlistModifiedAt,
        songs.maxOfOrNull { it.addedAt } ?: 0L
    )
    return songs
        .asReversed()
        .mapIndexed { index, song ->
            val displayAddedAt = (newestAddedAt - index).coerceAtLeast(1L)
            song.copy(addedAt = displayAddedAt)
        }
        .toMutableList()
}

internal fun LocalPlaylistRepository.sortSongsByAddedAtForDisplay(songs: List<SongItem>): MutableList<SongItem> {
    if (songs.size < 2) return songs.toMutableList()
    return songs
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<SongItem>> { it.value.addedAt }
                .thenBy { it.index }
        )
        .mapTo(mutableListOf()) { it.value }
}

internal fun LocalPlaylistRepository.sortSongsForDisplay(
    playlist: LocalPlaylist,
    songs: List<SongItem>
): MutableList<SongItem> {
    return if (isLocalFilesPlaylist(playlist.id, playlist.name)) {
        // 当前版本的列表顺序就是用户可见顺序，创建时间只用于首次
        // 导入和旧数据迁移，加载时再次排序会覆盖用户手动拖动
        songs.toMutableList()
    } else {
        sortSongsByAddedAtForDisplay(songs)
    }
}

internal fun LocalPlaylistRepository.persistToDisk(playlists: List<LocalPlaylist>, serialized: String = gson.toJson(playlists)) {
    if (corruptPrimaryNeedsQuarantine && !quarantineCorruptPrimary()) {
        throw IOException("Corrupt playlist storage could not be quarantined")
    }
    storage.commit(
        text = serialized,
        rotateBackup = !preserveBackupOnNextWrite,
        replaceBackupWithCommittedPrimary = replaceBackupOnNextWrite
    )
    preserveBackupOnNextWrite = false
    replaceBackupOnNextWrite = false
}

internal suspend fun <T> LocalPlaylistRepository.commitPlaylistMutation(block: suspend () -> T): T {
    return withContext(Dispatchers.IO) {
        requireInitialized()
        playlistCommitMutex.lock()
        try {
            block()
        } finally {
            playlistCommitMutex.unlock()
        }
    }
}

internal suspend fun LocalPlaylistRepository.publishLocked(
    playlists: List<LocalPlaylist>,
    triggerSync: Boolean = true,
    syncMutation: LocalPlaylistSyncMutation = LocalPlaylistSyncMutation(),
    markLocalMutation: Boolean = triggerSync
) {
    val normalized = normalizePlaylistOrder(playlists)
    val stateChanged = normalized != _playlists.value
    if (!stateChanged && syncMutation.isEmpty) {
        if (markLocalMutation) {
            syncMutationStore.markSyncMutation()
        }
        if (triggerSync && autoSyncEnabled) {
            scheduleAutoSync()
        }
        return
    }

    val currentDomainDigest = LocalPlaylistRoomStore.domainDigest(_playlists.value)
    val nextDomainDigest = LocalPlaylistRoomStore.domainDigest(normalized)
    val legacyPrimaryText = if (!roomStorageEnabled) {
        storage.readPrimary()
    } else {
        null
    }
    val pendingOutbox = preparePendingSyncMutationUpdate(
        currentDomainDigest = currentDomainDigest,
        legacyPrimaryText = legacyPrimaryText,
        nextDomainDigest = nextDomainDigest,
        syncMutation = syncMutation
    )
    // 没有墓碑要提交时可以先推进版本, 含墓碑的变更由存储层和版本一起提交
    if (markLocalMutation && syncMutation.isEmpty && pendingOutbox == null) {
        syncMutationStore.markSyncMutation()
    }
    val roomWasEnabledBeforeOutbox = roomStorageEnabled
    if (pendingOutbox != null || !syncMutation.isEmpty) {
        writePendingSyncMutation(pendingOutbox)
    }
    var committedToRoom = false
    var roomFallbackRequired = roomWasEnabledBeforeOutbox && !roomStorageEnabled
    if (stateChanged && roomStorageEnabled && roomStore != null) {
        val activeRoomStore = roomStore
        runCatching {
            activeRoomStore.writeIncremental(
                previous = _playlists.value,
                next = normalized,
                sourceDigest = nextDomainDigest
            )
        }.onSuccess {
            committedToRoom = true
        }.onFailure { error ->
            roomFallbackRequired = true
            roomStorageEnabled = false
            NPLogger.e(
                "LocalPlaylistRepo",
                "Room playlist commit failed; falling back to legacy JSON",
                error
            )
        }
    }
    if (stateChanged && !committedToRoom) {
        persistToDisk(normalized)
        val fallbackRoomStore = roomStore
        if (roomFallbackRequired && fallbackRoomStore != null) {
            runCatching {
                fallbackRoomStore.markLegacyJsonPrimary(nextDomainDigest)
            }.onFailure { error ->
                NPLogger.e(
                    "LocalPlaylistRepo",
                    "Failed to mark legacy JSON fallback state in Room",
                    error
                )
            }
        }
    }
    if (stateChanged) {
        _playlists.value = normalized
        _playlistCount.value = normalized.size
    }
    if (pendingOutbox != null) {
        val settled = runCatching {
            settlePendingSyncMutation(pendingOutbox, triggerSync)
        }.onFailure { error ->
            _syncMutationPending.value = true
            NPLogger.e(
                "LocalPlaylistRepo",
                "Playlist saved; sync mutation will be retried",
                error
            )
        }.isSuccess
        if (!settled) return
    } else if (triggerSync && autoSyncEnabled) {
        scheduleAutoSync()
    }
}

internal fun LocalPlaylistRepository.recoverPendingSyncMutation(committedDomainDigest: String): Boolean {
    return runCatching {
        runBlocking {
            flushPendingSyncMutation(committedDomainDigest)
        }
    }.onFailure { error ->
        NPLogger.e("LocalPlaylistRepo", "Failed to replay playlist sync mutation", error)
    }.isSuccess
}

internal suspend fun LocalPlaylistRepository.flushPendingSyncMutation(committedDomainDigest: String): Boolean {
    val committedOutbox = readPendingSyncMutationOutbox(
        committedDomainDigest = committedDomainDigest,
        legacyPrimaryText = storage.readPrimary()
    )
    if (committedOutbox == null) {
        clearPendingSyncMutation()
        _syncMutationPending.value = false
        return false
    }
    return settlePendingSyncMutation(committedOutbox, triggerSync = false)
}

internal suspend fun LocalPlaylistRepository.preparePendingSyncMutationUpdate(
    currentDomainDigest: String,
    legacyPrimaryText: String?,
    nextDomainDigest: String,
    syncMutation: LocalPlaylistSyncMutation
): LocalPlaylistSyncMutationOutbox? {
    val committedMutations = readPendingSyncMutationOutbox(
        committedDomainDigest = currentDomainDigest,
        legacyPrimaryText = legacyPrimaryText
    )
        ?.mutations
        .orEmpty()
    if (committedMutations.isEmpty() && syncMutation.isEmpty) {
        return null
    }

    val nextMutation = syncMutation.withExpectedPrimaryDigest(nextDomainDigest)
    return LocalPlaylistSyncMutationOutbox(committedMutations + nextMutation)
}

internal fun LocalPlaylistRepository.decodeCommittedSyncMutationOutbox(
    text: String,
    committedDomainDigest: String,
    legacyPrimaryText: String?
): LocalPlaylistSyncMutationOutbox? {
    val outbox = runCatching {
        val root = JSONObject(text)
        if (root.has("mutations")) {
            requireNotNull(gson.fromJson(text, LocalPlaylistSyncMutationOutbox::class.java))
        } else {
            LocalPlaylistSyncMutationOutbox(
                mutations = listOf(
                    requireNotNull(gson.fromJson(text, LocalPlaylistSyncMutation::class.java))
                )
            )
        }
    }.getOrElse { error ->
        NPLogger.e("LocalPlaylistRepo", "Discarding corrupt playlist sync mutation", error)
        return null
    }
    return trimCommittedSyncMutationOutbox(
        outbox = outbox,
        committedDomainDigest = committedDomainDigest,
        legacyPrimaryText = legacyPrimaryText
    )
}

internal fun LocalPlaylistRepository.trimCommittedSyncMutationOutbox(
    outbox: LocalPlaylistSyncMutationOutbox,
    committedDomainDigest: String,
    legacyPrimaryText: String?
): LocalPlaylistSyncMutationOutbox? {
    if (outbox.mutations.isEmpty()) return null
    val legacyDigest = legacyPrimaryText?.let(::primaryDigest)
    val committedIndex = outbox.mutations.indexOfLast { mutation ->
        mutation.expectedPrimaryDigest == committedDomainDigest ||
            mutation.expectedPrimaryDigest == legacyDigest
    }
    if (committedIndex < 0) return null
    return LocalPlaylistSyncMutationOutbox(
        mutations = outbox.mutations.take(committedIndex + 1)
    )
}

internal suspend fun LocalPlaylistRepository.readPendingSyncMutationOutbox(
    committedDomainDigest: String,
    legacyPrimaryText: String?
): LocalPlaylistSyncMutationOutbox? {
    if (roomStorageEnabled && roomStore != null) {
        val activeRoomStore = roomStore
        val roomOutbox = runCatching {
            activeRoomStore.readPendingSyncMutationOutbox()
        }.onFailure { error ->
            roomStorageEnabled = false
            NPLogger.e(
                "LocalPlaylistRepo",
                "Failed to read Room sync outbox; falling back to legacy outbox",
                error
            )
        }.getOrNull()
        if (roomOutbox != null) {
            return trimCommittedSyncMutationOutbox(
                outbox = roomOutbox,
                committedDomainDigest = committedDomainDigest,
                legacyPrimaryText = legacyPrimaryText
            )
        }
    }

    val pendingText = storage.readPendingSyncMutation() ?: return null
    return decodeCommittedSyncMutationOutbox(
        text = pendingText,
        committedDomainDigest = committedDomainDigest,
        legacyPrimaryText = legacyPrimaryText
    )
}

internal suspend fun LocalPlaylistRepository.writePendingSyncMutation(outbox: LocalPlaylistSyncMutationOutbox?) {
    if (roomStorageEnabled && roomStore != null) {
        val activeRoomStore = roomStore
        val roomWriteSucceeded = runCatching {
            if (outbox == null) {
                activeRoomStore.clearPendingSyncMutationOutbox()
            } else {
                activeRoomStore.writePendingSyncMutationOutbox(outbox)
            }
        }.onFailure { error ->
            roomStorageEnabled = false
            NPLogger.e(
                "LocalPlaylistRepo",
                "Failed to write Room sync outbox; falling back to legacy outbox",
                error
            )
        }.isSuccess
        if (roomWriteSucceeded) {
            return
        }
    }
    if (outbox == null) {
        storage.clearPendingSyncMutation()
    } else {
        storage.writePendingSyncMutation(gson.toJson(outbox))
    }
}

internal suspend fun LocalPlaylistRepository.clearPendingSyncMutation() {
    if (roomStorageEnabled && roomStore != null) {
        val activeRoomStore = roomStore
        try {
            activeRoomStore.clearPendingSyncMutationOutbox()
        } catch (error: Exception) {
            roomStorageEnabled = false
            NPLogger.e("LocalPlaylistRepo", "Failed to clear Room sync outbox", error)
            throw IOException("Failed to clear Room sync outbox", error)
        }
    }
    storage.clearPendingSyncMutation()
}

internal suspend fun LocalPlaylistRepository.settlePendingSyncMutation(
    outbox: LocalPlaylistSyncMutationOutbox,
    triggerSync: Boolean
): Boolean {
    val hasSyncMutation = outbox.mutations.any { mutation -> !mutation.isEmpty }
    try {
        outbox.mutations.forEach { mutation ->
            if (!mutation.isEmpty) {
                syncMutationStore.applyAndMarkMutation(mutation)
            }
        }
        if ((triggerSync || hasSyncMutation) && autoSyncEnabled && !scheduleAutoSync()) {
            throw IOException("Failed to schedule playlist sync mutation")
        }
        clearPendingSyncMutation()
        _syncMutationPending.value = false
        return hasSyncMutation
    } catch (error: Exception) {
        _syncMutationPending.value = true
        throw IOException("Playlist saved but sync mutation is pending", error)
    }
}

internal fun LocalPlaylistRepository.primaryDigest(text: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun LocalPlaylistRepository.scheduleAutoSync(): Boolean {
    return try {
        val autoSyncTrigger = providedAutoSyncTrigger
        if (autoSyncTrigger != null) {
            autoSyncTrigger()
        } else {
            if (!syncStorage.isAutoSyncEnabled()) {
                NPLogger.d("LocalPlaylistRepo", "Auto sync disabled, skip")
            }
            GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false)
            WebDavSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false)
        }
        true
    } catch (e: Exception) {
        NPLogger.e("LocalPlaylistRepo", "Failed to schedule sync", e)
        false
    }
}

internal fun LocalPlaylistRepository.sanitizePlaylistName(name: String, excludedPlaylistId: Long? = null): String {
    val defaultName = context.getString(R.string.playlist_create)
    // 限制歌单名长度，保证重名处理时也不会超出最大字数
    val base = name.trim().ifBlank { defaultName }.take(LocalPlaylistRepository.MAX_PLAYLIST_NAME_LENGTH)
    val occupiedNames = _playlists.value
        .asSequence()
        .filter { playlist -> excludedPlaylistId == null || playlist.id != excludedPlaylistId }
        .map { it.name.lowercase() }
        .toSet()

    var candidate = base
    var index = 2
    while (
        SystemLocalPlaylists.matchesReservedName(candidate, context) ||
        candidate.lowercase() in occupiedNames
    ) {
        val suffix = "_$index"
        val allowed = (LocalPlaylistRepository.MAX_PLAYLIST_NAME_LENGTH - suffix.length).coerceAtLeast(0)
        candidate = (base.take(allowed) + suffix).take(LocalPlaylistRepository.MAX_PLAYLIST_NAME_LENGTH)
        index++
    }
    return candidate
}

internal fun LocalPlaylistRepository.projectRemoteSourcePlaylistEntries(
    songs: List<SongItem>
): MutableList<SongItem> {
    return songs.mapTo(mutableListOf()) { song ->
        song.toSyncableRemoteSongOrNull(context) ?: song
    }
}

internal fun LocalPlaylistRepository.stampSongsForPlaylistInsert(
    songs: List<SongItem>,
    addedAt: Long,
    preserveScannedSourceAddedAt: Boolean = false
): List<SongItem> {
    if (songs.isEmpty()) return emptyList()

    // 同一批本地文件按来源创建时间排列，单首加入仍由 membershipAddedAtMs 决定
    val songsForInsert = if (
        songs.size > 1 && songs.any { LocalSongSupport.isLocalSong(it, context) }
            && !preserveScannedSourceAddedAt
    ) {
        songs.sortedWith(localSongSourceCreationComparator())
    } else {
        songs
    }

    val membershipTokens = syncMutationStore.nextSyncCausalTokens(songsForInsert.size)
    check(membershipTokens.size == songsForInsert.size) {
        "Expected ${songsForInsert.size} sync membership tokens, got ${membershipTokens.size}"
    }
    return songsForInsert.mapIndexed { index, song ->
        val membershipAddedAt = (addedAt - index).coerceAtLeast(1L)
        song.copy(
            // membership 时间记录 NeriPlayer 导入歌曲的时刻
            addedAt = resolvePlaylistSongAddedAt(
                song = song,
                membershipAddedAt = addedAt,
                index = index,
                preserveScannedSourceAddedAt = preserveScannedSourceAddedAt
            ),
            membershipAddedAtMs = membershipAddedAt,
            syncMembershipTokens = listOf(membershipTokens[index])
        )
    }
}
