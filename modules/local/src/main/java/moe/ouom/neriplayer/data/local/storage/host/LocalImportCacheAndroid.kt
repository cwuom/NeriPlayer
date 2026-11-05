package moe.ouom.neriplayer.data.local.storage.host

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.PLAYBACK_QUEUE_MAIN
import moe.ouom.neriplayer.data.local.database.entity.PLAYBACK_QUEUE_SHUFFLE_RESTORE
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.metadata.LocalMediaMetadataRecoveryStore
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.data.local.storage.cleanup.LocalImportCache
import moe.ouom.neriplayer.data.local.storage.source.LocalImportCacheAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsCaptureBarrier
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage

internal fun androidLocalImportCache(context: Context, additionalSongs: () -> List<SongItem>): LocalImportCacheAccess {
    val references = LocalImportCoverReferences(context, additionalSongs)
    return LocalImportCache(
        File(context.filesDir, "local_audio_covers"), references::read,
        referenceSnapshotValid = references::isSnapshotValid,
        fileAccessLock = LocalMediaSupport.embeddedCoverCacheLock,
        isCoverInUse = LocalMediaSupport::isEmbeddedCoverInUse,
        clearReadCaches = LocalMediaSupport::clearCoverLookupCache
    )
}

private class LocalImportCoverReferences(private val context: Context, private val additionalSongs: () -> List<SongItem>) {
    private val mutationStorage by lazy { SecureTokenStorage(context) }
    private var mutationVersion = -1L
    private var rootGeneration = -1L
    private var runtimeSongs = emptyList<SongItem>()

    suspend fun read(): Set<String> {
        requireNoMetadataRecovery()
        val playlists = LocalPlaylistRepository.getInstance(context)
        val favorites = FavoritePlaylistRepository.getInstance(context)
        val history = PlayHistoryRepository.getInstance(context)
        val statistics = PlaybackStatsRepository.getInstance(context)
        val usage = PlaylistUsageRepository.getInstance(context)
        check(playlists.awaitInitialized() && favorites.awaitInitialized() && history.awaitInitialized() &&
            statistics.awaitInitialized() && usage.awaitInitialized()) { "Local cover references are unavailable" }
        PlaybackStatsCaptureBarrier.await(context)
        statistics.flushPendingWrites()
        mutationVersion = mutationStorage.getSyncMutationVersion()
        rootGeneration = LocalStorageRootGeneration.current()
        runtimeSongs = additionalSongs()
        val references = mutableSetOf<String>()
        playlists.playlists.value.forEach { playlist ->
            references.addCover(playlist.customCoverUrl)
            playlist.songs.forEach { references.addSong(it) }
        }
        favorites.getSyncSnapshots().forEach { playlist ->
            references.addCover(playlist.coverUrl)
            playlist.songs.forEach { references.addSong(it) }
        }
        history.syncSnapshot().forEach { song ->
            references.addHistoricalSong(song)
        }
        usage.syncStatsAndDeletions()
        usage.frequentPlaylistsFlow.value.forEach { references.addCover(it.picUrl) }
        runtimeSongs.forEach { references.addSong(it) }
        readDatabaseReferences(references)
        readLegacyReferences(references)
        readDownloadReferences(references)
        currentCoroutineContext().ensureActive()
        check(isSnapshotValid()) { "Local cover references changed during cache inspection" }
        return references
    }

    fun isSnapshotValid(): Boolean {
        requireNoMetadataRecovery()
        requireNoPendingDownloads(NeriUserDataDatabase.getInstance(context))
        return mutationVersion == mutationStorage.getSyncMutationVersion() &&
            rootGeneration == LocalStorageRootGeneration.current() && runtimeSongs == additionalSongs()
    }

    private suspend fun readDatabaseReferences(references: MutableSet<String>) =
        readDatabaseCoverReferences(NeriUserDataDatabase.getInstance(context), references)

    private suspend fun readLegacyReferences(references: MutableSet<String>) {
        val roomPrimary = NeriUserDataDatabase.getInstance(context).syncMetadataDao()
            .getMigrationMetadata("playback_queue_cutover_state")?.value == "room_primary"
        val queueFiles = if (roomPrimary) emptyList() else listOf("last_playlist.json")
        (queueFiles + listOf("pending_download_queue_v1.json", "downloaded_song_catalog_v3.json", "downloaded_song_catalog_v4.json",
            "downloaded_song_catalog_v4.json.managed-v1.json"))
            .map { File(context.filesDir, it) }.filter { it.exists() }.forEach { file ->
                readLocalImportSnapshotReferences(file, references)
            }
    }

    private suspend fun readDownloadReferences(references: MutableSet<String>) {
        val snapshot = LocalMediaHostAccess.downloads.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        check(snapshot.rootEntriesComplete && snapshot.sidecarEntriesComplete && !snapshot.rootEmptyConfirmationPending) {
            "Download metadata listing is incomplete"
        }
        check(snapshot.metadataEntriesByAudioName.keys.all { it in snapshot.metadataByAudioName }) { "Download metadata cannot be decoded" }
        val metadata = snapshot.metadataByAudioName.values + snapshot.pendingMetadataByAudioName.values +
            snapshot.metadataByCanonicalAudioName.values + snapshot.pendingMetadataByCanonicalAudioName.values +
            snapshot.metadataByDeclaredAudioName.values
        metadata.forEach { references.addMetadata(it) }
        snapshot.coverEntriesByName.values.forEach { references.addCover(it.reference) }
    }

    private fun requireNoMetadataRecovery() {
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context)
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("Metadata recovery directory is a symbolic link")
        if (!directory.exists()) return
        val entries = directory.listFiles() ?: throw IOException("Metadata recovery directory is unavailable")
        check(entries.isEmpty()) { "Local metadata recovery still owns cover references" }
    }
}

internal fun readLocalImportSnapshotReferences(file: File, references: MutableSet<String>) {
    if (!file.isFile || Files.isSymbolicLink(file.toPath())) throw IOException("Local metadata snapshot is not a regular file")
    val value = file.reader().use(JsonParser::parseReader)
    check(value.isJsonObject || value.isJsonArray) { "Local metadata snapshot has an invalid root" }
    if (file.name == "last_playlist.json") {
        check(value.isJsonObject && value.asJsonObject.get("playlist")?.isJsonArray == true) { "Playback queue snapshot is incomplete" }
        listOf("playlist", "shuffleRestorePlaylist").forEach { field ->
            val songs = value.asJsonObject.get(field)
            if (songs != null && !songs.isJsonNull) {
                check(songs.isJsonArray && songs.asJsonArray.all { it.isJsonObject }) { "Playback queue contains invalid song entries" }
            }
        }
    }
    collectJsonCoverReferences(value, references)
}

private fun collectJsonCoverReferences(value: JsonElement, references: MutableSet<String>) {
    when {
        value.isJsonObject -> value.asJsonObject.entrySet().forEach { (name, child) ->
            if (name in COVER_REFERENCE_FIELDS && !child.isJsonNull) {
                check(child.isJsonPrimitive && child.asJsonPrimitive.isString) { "Invalid cover reference in local snapshot" }
                references.addCover(child.asString)
            } else {
                collectJsonCoverReferences(child, references)
            }
        }
        value.isJsonArray -> value.asJsonArray.forEach { collectJsonCoverReferences(it, references) }
    }
}

internal fun MutableSet<String>.addSong(song: SongItem) = addCovers(song.coverUrl, song.customCoverUrl, song.originalCoverUrl)

internal fun MutableSet<String>.addHistoricalSong(song: PlayedEntry) {
    addCovers(song.coverUrl, song.customCoverUrl, song.originalCoverUrl)
}

internal fun MutableSet<String>.addMetadata(metadata: DownloadedAudioMetadata) {
    addCovers(metadata.coverPath, metadata.coverUrl, metadata.customCoverUrl, metadata.originalCoverUrl)
    metadata.restorableMetadata?.let { restorable ->
        addCovers(restorable.baseline.coverReference, restorable.overrides.coverReference)
        restorable.legacyCoverRecoveryReferences.forEach(::addCover)
    }
}

private fun MutableSet<String>.addCovers(vararg references: String?) = references.forEach(::addCover)

private fun MutableSet<String>.addCover(reference: String?) {
    reference?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
}

private val COVER_REFERENCE_FIELDS = setOf("coverUrl", "customCoverUrl", "originalCoverUrl", "coverPath", "coverReference")

internal suspend fun readDatabaseCoverReferences(database: NeriUserDataDatabase, references: MutableSet<String>) {
    database.withTransaction {
        requireNoPendingDownloads(database)
        check(database.playbackStatsSnapshotDao().pendingDeltaCount() == 0L) { "Playback statistics are still pending" }
        readStatisticsCoverReferences(database, references)
        listOf(PLAYBACK_QUEUE_MAIN, PLAYBACK_QUEUE_SHUFFLE_RESTORE).forEach { queue ->
            database.playbackQueueDao().getSongs(queue).forEach {
                references.addCovers(it.coverUrl, it.customCoverUrl, it.originalCoverUrl)
            }
        }
    }
}

private suspend fun readStatisticsCoverReferences(database: NeriUserDataDatabase, references: MutableSet<String>) {
    val statistics = database.playbackStatsDao()
    var afterIdentity: String? = null
    while (true) {
        val page = statistics.identityPage(afterIdentity, 256)
        if (page.isEmpty()) break
        page.forEach { references.addCovers(it.coverUrl, it.customCoverUrl) }
        afterIdentity = page.last().identityKey
    }
    var afterDay: Long? = null
    afterIdentity = null
    while (true) {
        val page = statistics.bucketPage(afterDay, afterIdentity, 256)
        if (page.isEmpty()) break
        page.forEach { references.addCovers(it.coverUrl, it.customCoverUrl) }
        afterDay = page.last().dayStartAt
        afterIdentity = page.last().identityKey
    }
}

private fun requireNoPendingDownloads(database: NeriUserDataDatabase) {
    val pending = database.openHelper.readableDatabase.query(
        "SELECT EXISTS(SELECT 1 FROM download_operation WHERE state NOT IN " +
            "('COMPLETED', 'CANCELLED', 'INVALID'))"
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0) != 0
    }
    check(!pending) { "Pending downloads still own cover references" }
}
