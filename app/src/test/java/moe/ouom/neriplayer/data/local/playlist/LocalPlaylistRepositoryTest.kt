package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.toPlaybackSongItem
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.github.SyncPlaylistDeletionPolicy
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.SyncSong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference


class LocalPlaylistRepositoryTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `async initial load does not publish or overwrite before persisted state is ready`() = runTest {
        val storage = BlockingReadStorage(
            primary = playlistJson(id = 201L, name = "persisted")
        )
        val normalizationThread = AtomicReference<Thread>()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "async_initial_load.json"),
            normalizePlaylists = {
                normalizationThread.set(Thread.currentThread())
                it
            },
            autoSyncEnabled = false,
            loadSynchronously = false,
            storage = storage
        )
        assertTrue(storage.primaryReadStarted.await(5, TimeUnit.SECONDS))
        assertNotSame(Thread.currentThread(), storage.primaryReadThread.get())
        assertTrue(repository.playlists.value.isEmpty())
        assertFalse(repository.initializationReadyFlow.value)

        val createPlaylist = async(Dispatchers.Default) {
            repository.createPlaylist("new")
        }
        try {
            assertFalse(createPlaylist.isCompleted)
        } finally {
            storage.allowPrimaryRead.countDown()
        }
        createPlaylist.await()

        assertNotSame(Thread.currentThread(), normalizationThread.get())
        assertTrue(repository.initializationReadyFlow.value)
        assertEquals(
            setOf("persisted", "new"),
            repository.playlists.value.mapTo(mutableSetOf(), LocalPlaylist::name)
        )
    }

    @Test
    fun `fast playlist preview reads only the requested playlist object`() = runTest {
        val first = playlistJson(id = 301L, name = "first")
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
        val second = playlistJson(id = 302L, name = "target")
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
        val storage = RecordingStorage(primary = "[$first,$second]")
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "fast_preview.json"),
            autoSyncEnabled = false,
            storage = storage
        )

        val preview = repository.readFastPlaylist(302L)

        assertEquals("target", preview?.name)
        assertEquals(302L, preview?.id)
    }

    @Test
    fun `failed async initial load remains read only`() = runTest {
        val storage = RecordingStorage(
            primary = playlistJson(id = 202L, name = "persisted")
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "failed_async_initial_load.json"),
            normalizePlaylists = { throw IOException("simulated normalization failure") },
            autoSyncEnabled = false,
            loadSynchronously = false,
            storage = storage
        )

        assertFalse(repository.awaitInitialized())
        var mutationRejected = false
        try {
            repository.createPlaylist("new")
        } catch (_: IOException) {
            mutationRejected = true
        }

        assertTrue(mutationRejected)
        assertEquals(0, storage.commitCount)
        assertTrue(repository.playlists.value.isEmpty())
        assertFalse(repository.initializationReadyFlow.value)
    }

    @Test
    fun `concurrent prepared adds keep every distinct song`() = runTest {
        val playlistId = 42L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(listOf(LocalPlaylist(id = playlistId, name = "并发歌单")))

        val songs = (1..40).map(::localSong)
        val addResults = songs.map { song ->
            async(Dispatchers.Default) {
                repository.addPreparedSongsToPlaylistAndCount(playlistId, listOf(song))
            }
        }.awaitAll()

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(songs.size, addResults.sum())
        assertEquals(
            songs.map { it.localFilePath }.toSet(),
            playlist.songs.map { it.localFilePath }.toSet()
        )
    }

    @Test
    fun `scanned adds keep distinct files with matching local metadata`() = runTest {
        val playlistId = 43L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(listOf(LocalPlaylist(id = playlistId, name = "扫描歌单")))

        val contentAlias = scannedAliasSong(
            id = 1L,
            mediaUri = "content://media/external/audio/media/100"
        )
        val pathAlias = scannedAliasSong(
            id = 2L,
            mediaUri = File(tempFolder.root, "周杰伦 - 晴天.mp3").absolutePath,
            localFilePath = File(tempFolder.root, "周杰伦 - 晴天.mp3").absolutePath
        )

        val firstAdd = repository.addScannedSongsToPlaylistAndCount(playlistId, listOf(contentAlias))
        val secondAdd = repository.addScannedSongsToPlaylistAndCount(playlistId, listOf(pathAlias))
        val playlist = repository.playlists.value.single { it.id == playlistId }

        assertEquals(1, firstAdd)
        assertEquals(1, secondAdd)
        assertEquals(2, playlist.songs.size)
        assertEquals(
            setOf(contentAlias.mediaUri, pathAlias.mediaUri),
            playlist.songs.map { it.mediaUri }.toSet()
        )
    }

    @Test
    fun `scanned adds to a regular playlist retain source creation time`() = runTest {
        val playlistId = 44L
        val sourceSong = localSong(index = 9, name = "source-time").copy(
            addedAt = 123L,
            logicalCreatedAtMs = 123L,
            createdAtSource = "FILESYSTEM_BIRTH",
            createdAtConfidence = "EXACT"
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "regular_scanned_creation_time.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(
            listOf(LocalPlaylist(id = playlistId, name = "普通歌单"))
        )

        assertEquals(
            1,
            repository.addScannedSongsToPlaylistAndCount(playlistId, listOf(sourceSong))
        )

        val stored = repository.playlists.value
            .single { it.id == playlistId }
            .songs
            .single()
        assertEquals(123L, stored.addedAt)
        assertEquals(123L, stored.logicalCreatedAtMs)
        assertTrue((stored.membershipAddedAtMs ?: 0L) > 123L)
    }

    @Test
    fun `adding downloaded local copy to favorites keeps original favorite order`() = runTest {
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        val remoteSong = remoteNeteaseSong(addedAt = 11L)
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = FavoritesPlaylist.SYSTEM_ID,
                    name = "我喜欢的音乐",
                    songs = mutableListOf(remoteSong),
                    modifiedAt = 10L
                )
            )
        )

        repository.addToFavorites(downloadedLocalCopy(remoteSong))

        val favorites = repository.playlists.value.single()
        assertEquals(1, favorites.songs.size)
        assertEquals(remoteSong, favorites.songs.single())
        assertEquals(11L, favorites.songs.single().addedAt)
    }

    @Test
    fun `downloaded favorite removal and readd retain the remote source`() = runTest {
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "downloaded_favorite_sync.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
        val remoteSong = remoteNeteaseSong(id = 45L)
        val downloadedCopy = downloadedPlaybackCopy(
            source = remoteSong,
            downloadedId = 9_045L
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = FavoritesPlaylist.SYSTEM_ID,
                    name = "我喜欢的音乐"
                )
            )
        )

        repository.addToFavorites(downloadedCopy)
        val firstFavorite = repository.playlists.value.single().songs.single()
        assertEquals(remoteSong.id, firstFavorite.id)
        assertEquals(remoteSong.identity(), firstFavorite.identity())
        assertEquals("netease", firstFavorite.channelId)
        assertNull(firstFavorite.mediaUri)
        assertNull(firstFavorite.localFilePath)
        assertFalse(LocalSongSupport.isLocalSong(firstFavorite, null))

        repository.removeFromFavorites(downloadedCopy)

        val deletion = syncStore.applied
            .flatMap(LocalPlaylistSyncMutation::addedSongDeletions)
            .single()
        assertEquals(remoteSong.identity(), deletion.identity())

        repository.addToFavorites(downloadedCopy)
        val readdedFavorite = repository.playlists.value.single().songs.single()
        assertEquals(remoteSong.id, readdedFavorite.id)
        assertEquals(remoteSong.identity(), readdedFavorite.identity())
        assertEquals("netease", readdedFavorite.channelId)
        assertNull(readdedFavorite.mediaUri)
        assertNull(readdedFavorite.localFilePath)
        assertFalse(LocalSongSupport.isLocalSong(readdedFavorite, null))
        assertEquals(
            listOf(remoteSong.id),
            repository.filterNeteaseLikeSyncCandidates(listOf(readdedFavorite)).map { it.id }
        )

        val removal = syncStore.applied
            .flatMap(LocalPlaylistSyncMutation::removedSongDeletions)
            .last()
        assertEquals(listOf(remoteSong.identity()), removal.identities)
    }

    @Test
    fun `bulk netease candidate filtering preserves duplicate original rows`() {
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "bulk_netease_candidates.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        val first = remoteNeteaseSong(id = 46L, name = "first")
        val duplicate = first.copy(name = "edited duplicate")
        val unsupported = localSong(index = 47)

        assertEquals(
            listOf(first, duplicate),
            repository.filterNeteaseLikeSyncCandidatesPreservingDuplicates(
                listOf(first, duplicate, unsupported)
            )
        )
    }

    @Test
    fun `adding downloaded copy to regular playlist retains remote source and sync identity`() = runTest {
        val playlistId = 46L
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "downloaded_regular_playlist_source.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
        val remoteSong = remoteNeteaseSong(id = 47L)
        val downloadedCopy = downloadedPlaybackCopy(
            source = remoteSong,
            downloadedId = 9_047L
        )
        repository.updatePlaylists(
            listOf(LocalPlaylist(id = playlistId, name = "普通歌单"))
        )

        val addedCount = repository.addPreparedSongsToPlaylistAndCount(
            playlistId = playlistId,
            songs = listOf(downloadedCopy)
        )

        val playlistSong = repository.playlists.value.single().songs.single()
        assertEquals(1, addedCount)
        assertEquals(remoteSong.id, playlistSong.id)
        assertEquals(remoteSong.identity(), playlistSong.identity())
        assertEquals("netease", playlistSong.channelId)
        assertNull(playlistSong.mediaUri)
        assertNull(playlistSong.localFilePath)
        assertFalse(LocalSongSupport.isLocalSong(playlistSong, null))
        assertEquals(
            listOf(remoteSong.id),
            repository.filterNeteaseLikeSyncCandidates(listOf(playlistSong)).map { it.id }
        )
        val removal = syncStore.applied
            .flatMap(LocalPlaylistSyncMutation::removedSongDeletions)
            .single()
        assertEquals(listOf(remoteSong.identity()), removal.identities)
    }

    @Test
    fun `metadata update from downloaded copy keeps favorite remote playback source`() = runTest {
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "downloaded_metadata_source.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        val remoteSong = remoteNeteaseSong().copy(
            coverUrl = "https://example.com/original-cover.jpg"
        )
        val downloadedCopy = downloadedLocalCopy(remoteSong).copy(
            coverUrl = "content://downloads/covers/downloaded-cover.jpg",
            customCoverUrl = "file:///cache/custom-cover.jpg",
            customName = "Edited title",
            matchedLyric = "[00:01.00]lyrics"
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = FavoritesPlaylist.SYSTEM_ID,
                    name = "我喜欢的音乐",
                    songs = mutableListOf(remoteSong)
                )
            )
        )

        repository.updateSongMetadata(
            originalSong = downloadedCopy,
            newSongInfo = downloadedCopy,
            triggerSync = true
        )

        val updatedFavorite = repository.playlists.value.single().songs.single()
        assertEquals(remoteSong.id, updatedFavorite.id)
        assertEquals(remoteSong.album, updatedFavorite.album)
        assertEquals(remoteSong.coverUrl, updatedFavorite.coverUrl)
        assertNull(updatedFavorite.mediaUri)
        assertNull(updatedFavorite.localFilePath)
        assertEquals(remoteSong.channelId, updatedFavorite.channelId)
        assertEquals(remoteSong.audioId, updatedFavorite.audioId)
        assertEquals("file:///cache/custom-cover.jpg", updatedFavorite.customCoverUrl)
        assertEquals("Edited title", updatedFavorite.customName)
        assertEquals("[00:01.00]lyrics", updatedFavorite.matchedLyric)
        assertFalse(LocalSongSupport.isLocalSong(updatedFavorite, null))
    }

    @Test
    fun `legacy playlist migration preserves previous display order and rewrites added time`() = runTest {
        val playlistId = 44L
        val storageFile = File(tempFolder.root, "legacy_local_playlists.json")
        storageFile.writeText(
            """
            [
              {
                "id": $playlistId,
                "name": "旧歌单",
                "songs": [
                  ${songJson(1L, "oldest", 11L)},
                  ${songJson(2L, "middle", 22L)},
                  ${songJson(3L, "newest", 33L)}
                ],
                "modifiedAt": 1000
              }
            ]
            """.trimIndent()
        )

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, playlist.songOrderVersion)
        assertEquals(listOf("newest", "middle", "oldest"), playlist.songs.map { it.name })
        assertEquals(
            playlist.songs.map { it.addedAt },
            playlist.songs.map { it.addedAt }.sortedDescending()
        )
    }

    @Test
    fun `room promotion suppresses legacy playlist normalization rewrite`() {
        assertFalse(
            shouldRewriteLegacyPlaylistsAfterInitialLoad(
                migrationRequired = true,
                allowMigrationWrite = true,
                roomPromotedDuringLoad = true
            )
        )
        assertTrue(
            shouldRewriteLegacyPlaylistsAfterInitialLoad(
                migrationRequired = true,
                allowMigrationWrite = true,
                roomPromotedDuringLoad = false
            )
        )
    }

    @Test
    fun `adding songs to regular playlist places newest songs first`() = runTest {
        val playlistId = 45L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "普通歌单",
                    songs = mutableListOf(localSong(index = 1, name = "old")),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        repository.addPreparedSongsToPlaylistAndCount(
            playlistId,
            listOf(
                localSong(index = 2, name = "new-a"),
                localSong(index = 3, name = "new-b")
            )
        )

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(listOf("new-a", "new-b", "old"), playlist.songs.map { it.name })
        assertEquals(
            playlist.songs.take(2).map { it.addedAt },
            playlist.songs.take(2).map { it.addedAt }.sortedDescending()
        )
    }

    @Test
    fun `adding new favorite places it before existing favorites`() = runTest {
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = FavoritesPlaylist.SYSTEM_ID,
                    name = "我喜欢的音乐",
                    songs = mutableListOf(localSong(index = 1, name = "old")),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        repository.addToFavorites(localSong(index = 2, name = "new"))

        val favorites = repository.playlists.value.single()
        assertEquals(listOf("new", "old"), favorites.songs.map { it.name })
    }

    @Test
    fun `manual reorder keeps user order after adding another song`() = runTest {
        val playlistId = 46L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        val first = localSong(index = 1, name = "first")
        val second = localSong(index = 2, name = "second")
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "手动排序",
                    songs = mutableListOf(first, second),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        repository.reorderSongs(playlistId, listOf(second.identity(), first.identity()))
        repository.addPreparedSongsToPlaylistAndCount(
            playlistId,
            listOf(localSong(index = 3, name = "new"))
        )

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(listOf("new", "second", "first"), playlist.songs.map { it.name })
    }

    @Test
    fun `corrupt primary is quarantined without being replaced by empty state`() {
        val storageFile = File(tempFolder.root, "corrupt_local_playlists.json")
        val corruptJson = "{not-valid-json"
        storageFile.writeText(corruptJson)

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        val quarantines = tempFolder.root.listFiles().orEmpty()
            .filter { it.name.startsWith("${storageFile.name}.corrupt-") }
        assertTrue(repository.playlists.value.isEmpty())
        assertFalse(storageFile.exists())
        assertEquals(1, quarantines.size)
        assertEquals(corruptJson, quarantines.single().readText())
    }

    @Test
    fun `valid backup restores corrupt primary before publishing playlists`() {
        val storageFile = File(tempFolder.root, "recover_local_playlists.json")
        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        val corruptJson = "[broken"
        val backupJson = playlistJson(id = 71L, name = "备份歌单")
        storageFile.writeText(corruptJson)
        backupFile.writeText(backupJson)

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        assertEquals("备份歌单", repository.playlists.value.single().name)
        assertEquals(backupJson, storageFile.readText())
        assertEquals(backupJson, backupFile.readText())
        assertTrue(
            tempFolder.root.listFiles().orEmpty().any {
                it.name.startsWith("${storageFile.name}.corrupt-") &&
                    it.readText() == corruptJson
            }
        )
    }

    @Test
    fun `write failure propagates without publishing uncommitted playlists`() = runTest {
        val initialJson = playlistJson(id = 81L, name = "已落盘")
        val storage = FailingCommitStorage(initialJson)
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "write_failure.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage
        )

        val failure = runCatching {
            repository.updatePlaylists(listOf(LocalPlaylist(id = 82L, name = "未落盘")))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(listOf("已落盘"), repository.playlists.value.map(LocalPlaylist::name))
        assertEquals(1, repository.playlistCount.value)
        assertEquals(initialJson, storage.primary)
    }

    @Test
    fun `successful replacement keeps previous primary as stable backup`() = runTest {
        val storageFile = File(tempFolder.root, "backup_rotation.json")
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        repository.updatePlaylists(listOf(LocalPlaylist(id = 91L, name = "first")))
        repository.updatePlaylists(listOf(LocalPlaylist(id = 92L, name = "second")))

        val backupText = File(tempFolder.root, "${storageFile.name}.bak").readText()
        assertTrue(backupText.contains("first"))
        assertFalse(backupText.contains("second"))
        assertTrue(storageFile.readText().contains("second"))
    }

    @Test
    fun `first successful commit seeds a recoverable backup`() = runTest {
        val storageFile = File(tempFolder.root, "first_commit_backup.json")
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        repository.updatePlaylists(listOf(LocalPlaylist(id = 101L, name = "first")))

        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        assertTrue(backupFile.exists())
        assertEquals(storageFile.readText(), backupFile.readText())
    }

    @Test
    fun `invalid song entry falls back to valid backup`() {
        val storageFile = File(tempFolder.root, "invalid_song.json")
        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        storageFile.writeText(
            """
            [
              {
                "id": 111,
                "name": "broken",
                "songs": [null],
                "songOrderVersion": 0
              }
            ]
            """.trimIndent()
        )
        backupFile.writeText(playlistJson(id = 112L, name = "backup"))

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        assertEquals("backup", repository.playlists.value.single().name)
        assertTrue(storageFile.readText().contains("backup"))
    }

    @Test
    fun `first commit after both copies are corrupt replaces invalid backup`() = runTest {
        val storageFile = File(tempFolder.root, "replace_invalid_backup.json")
        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        storageFile.writeText("[broken-primary")
        backupFile.writeText("[broken-backup")
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        repository.updatePlaylists(listOf(LocalPlaylist(id = 113L, name = "recovered")))

        assertEquals(storageFile.readText(), backupFile.readText())
        assertTrue(backupFile.readText().contains("recovered"))
    }

    @Test
    fun `normalization failure falls back to valid backup`() {
        val storageFile = File(tempFolder.root, "normalization_failure.json")
        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        storageFile.writeText(playlistJson(id = 121L, name = "bad"))
        backupFile.writeText(playlistJson(id = 122L, name = "good"))

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { playlists ->
                check(playlists.none { it.name == "bad" })
                playlists
            },
            autoSyncEnabled = false
        )

        assertEquals("good", repository.playlists.value.single().name)
        assertTrue(storageFile.readText().contains("good"))
    }

    @Test
    fun `failed playlist commit does not apply sync tombstone early`() = runTest {
        val song = remoteNeteaseSong(id = 131L)
        val initialJson = playlistJson(
            id = FavoritesPlaylist.SYSTEM_ID,
            name = "favorites",
            songs = listOf(song)
        )
        val storage = RecordingStorage(primary = initialJson, failCommit = true)
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "tombstone_commit_failure.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = syncStore
        )

        val failure = runCatching { repository.removeFromFavorites(song) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(syncStore.applied.isEmpty())
        assertEquals(song.id, repository.playlists.value.single().songs.single().id)

        val recoveredSyncStore = RecordingSyncMutationStore()
        LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "tombstone_commit_failure.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = recoveredSyncStore
        )

        assertTrue(recoveredSyncStore.applied.isEmpty())
        assertTrue(storage.pendingSyncMutation == null)
    }

    @Test
    fun `pending sync mutation replays after playlist commit succeeds`() = runTest {
        val song = remoteNeteaseSong(id = 141L)
        val storage = RecordingStorage(
            primary = playlistJson(
                id = FavoritesPlaylist.SYSTEM_ID,
                name = "favorites",
                songs = listOf(song)
            )
        )
        val failingSyncStore = RecordingSyncMutationStore(failApply = true)
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "pending_sync_replay.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = failingSyncStore
        )

        repository.removeFromFavorites(song)
        assertTrue(repository.playlists.value.single().songs.isEmpty())
        assertTrue(repository.syncMutationPending.value)
        assertTrue(storage.pendingSyncMutation != null)

        val recoveredSyncStore = RecordingSyncMutationStore()
        LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "pending_sync_replay.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage,
            syncMutationStore = recoveredSyncStore
        )

        assertEquals(1, recoveredSyncStore.applied.size)
        assertTrue(storage.pendingSyncMutation == null)
    }

    @Test
    fun `new membership token is captured by later deletion`() = runTest {
        val playlistId = 145L
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "membership_token_delete.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
        repository.updatePlaylists(listOf(LocalPlaylist(id = playlistId, name = "tokens")))

        val song = remoteNeteaseSong(id = 146L)
        repository.addPreparedSongsToPlaylist(playlistId, listOf(song))
        val membershipToken = repository.playlists.value
            .single()
            .songs
            .single()
            .syncMembershipTokens
            .orEmpty()
            .single()

        repository.removeSongsFromPlaylistByIdentity(playlistId, listOf(song))

        val deletion = syncStore.applied
            .flatMap(LocalPlaylistSyncMutation::addedSongDeletions)
            .single()
        assertEquals(listOf(membershipToken), deletion.removedMembershipTokens)
    }

    @Test
    fun `automatic metadata update stays local without advancing sync version`() = runTest {
        val playlistId = 146L
        val membershipToken = SyncCausalToken(deviceId = "existing", counter = 9L)
        val original = remoteNeteaseSong(id = 147L).copy(
            addedAt = 500L,
            syncMembershipTokens = listOf(membershipToken)
        )
        val syncStore = RecordingSyncMutationStore()
        var autoSyncTriggerCount = 0
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "metadata_membership.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = true,
            syncMutationStore = syncStore,
            autoSyncTrigger = { autoSyncTriggerCount++ }
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "metadata",
                    songs = mutableListOf(original),
                    modifiedAt = 10L
                )
            )
        )

        repository.updateSongMetadata(
            originalSong = original,
            newSongInfo = original.copy(
                name = "Hydrated",
                addedAt = 100L,
                syncMembershipTokens = emptyList()
            )
        )

        val updated = repository.playlists.value.single().songs.single()
        assertEquals("Hydrated", updated.name)
        assertEquals(500L, updated.addedAt)
        assertEquals(listOf(membershipToken), updated.syncMembershipTokens)
        assertEquals(10L, repository.playlists.value.single().modifiedAt)
        assertEquals(0L, syncStore.mutationVersion)
        assertEquals(0, autoSyncTriggerCount)
    }

    @Test
    fun `scanned metadata clears invalid covers without restoring them from persistence merge`() = runTest {
        val playlistId = 160L
        val storage = RecordingStorage(primary = null)
        val audioFile = tempFolder.newFile("invalid-cover-song.mp3")
        val invalidCover = tempFolder.newFile("invalid-cover.jpg").apply {
            writeText("not an image")
        }
        val song = SongItem(
            id = 161L,
            name = "Local song",
            artist = "Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = invalidCover.toURI().toString(),
            originalCoverUrl = invalidCover.toURI().toString(),
            mediaUri = audioFile.toURI().toString(),
            localFilePath = audioFile.absolutePath,
            channelId = "local"
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "invalid-cover-refresh.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage
        )
        repository.updatePlaylists(
            listOf(LocalPlaylist(id = playlistId, name = "Local", songs = mutableListOf(song)))
        )

        repository.refreshScannedLocalSongMetadata(listOf(song))

        val refreshed = repository.playlists.value.single().songs.single()
        assertNull(refreshed.coverUrl)
        assertNull(refreshed.originalCoverUrl)

        val restoredRepository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "invalid-cover-refresh.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            storage = storage
        )
        val restored = restoredRepository.playlists.value.single().songs.single()
        assertNull(restored.coverUrl)
        assertNull(restored.originalCoverUrl)
    }

    @Test
    fun `scanned alias clears invalid covers while keeping the existing playback source`() = runTest {
        val playlistId = 164L
        val existingAudio = tempFolder.newFile("retained-source.mp3")
        val scannedAliasAudio = tempFolder.newFile("scanned-alias.mp3")
        val invalidExistingCover = tempFolder.newFile("invalid-existing-cover.jpg").apply {
            writeText("not an image")
        }
        val invalidScannedCover = tempFolder.newFile("invalid-scanned-cover.jpg").apply {
            writeText("not an image")
        }
        val existing = SongItem(
            id = 165L,
            name = "Local song",
            artist = "Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = invalidExistingCover.toURI().toString(),
            originalCoverUrl = invalidExistingCover.toURI().toString(),
            mediaUri = existingAudio.toURI().toString(),
            localFilePath = existingAudio.absolutePath,
            channelId = "local",
            audioId = "shared-local-audio-id"
        )
        val scannedAlias = existing.copy(
            id = 166L,
            coverUrl = invalidScannedCover.toURI().toString(),
            originalCoverUrl = invalidScannedCover.toURI().toString(),
            mediaUri = scannedAliasAudio.toURI().toString(),
            localFilePath = scannedAliasAudio.absolutePath
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "invalid-cover-alias-refresh.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(
            listOf(LocalPlaylist(id = playlistId, name = "Local", songs = mutableListOf(existing)))
        )

        repository.refreshScannedLocalSongMetadata(listOf(scannedAlias))

        val refreshed = repository.playlists.value.single().songs.single()
        assertEquals(existing.mediaUri, refreshed.mediaUri)
        assertEquals(existing.localFilePath, refreshed.localFilePath)
        assertNull(refreshed.coverUrl)
        assertNull(refreshed.originalCoverUrl)
    }

    @Test
    fun `ordinary metadata update still preserves existing covers when new values are absent`() = runTest {
        val playlistId = 162L
        val original = localSong(163).copy(
            coverUrl = "file:///persisted-cover.jpg",
            originalCoverUrl = "file:///persisted-original-cover.jpg"
        )
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "preserve-cover-update.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(
            listOf(LocalPlaylist(id = playlistId, name = "Local", songs = mutableListOf(original)))
        )

        repository.updateSongMetadata(
            originalSong = original,
            newSongInfo = original.copy(coverUrl = null, originalCoverUrl = null)
        )

        val updated = repository.playlists.value.single().songs.single()
        assertEquals(original.coverUrl, updated.coverUrl)
        assertEquals(original.originalCoverUrl, updated.originalCoverUrl)
    }

    @Test
    fun `user metadata update schedules auto sync when requested`() = runTest {
        val playlistId = 156L
        val original = remoteNeteaseSong(id = 157L)
        val syncStore = RecordingSyncMutationStore()
        var autoSyncTriggerCount = 0
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "metadata_user_sync.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = true,
            syncMutationStore = syncStore,
            autoSyncTrigger = { autoSyncTriggerCount++ }
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "metadata",
                    songs = mutableListOf(original),
                    modifiedAt = 10L
                )
            )
        )

        repository.updateSongMetadata(
            originalSong = original,
            newSongInfo = original.copy(customName = "User title"),
            triggerSync = true
        )

        assertEquals("User title", repository.playlists.value.single().songs.single().customName)
        assertTrue(repository.playlists.value.single().modifiedAt > 10L)
        assertEquals(1L, syncStore.mutationVersion)
        assertEquals(1, autoSyncTriggerCount)
    }

    @Test
    fun `lyric offset rebase updates playlist timestamp and schedules sync`() = runTest {
        val playlistId = 158L
        val original = remoteNeteaseSong(id = 159L).copy(
            matchedLyricSource = MusicPlatform.QQ_MUSIC,
            userLyricOffsetMs = 300L
        )
        val syncStore = RecordingSyncMutationStore()
        var autoSyncTriggerCount = 0
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "offset_rebase_sync.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = true,
            syncMutationStore = syncStore,
            autoSyncTrigger = { autoSyncTriggerCount++ }
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "offset",
                    songs = mutableListOf(original),
                    modifiedAt = 10L
                )
            )
        )

        repository.rebaseLyricOffsetsForSource(
            targetSource = MusicPlatform.QQ_MUSIC,
            previousDefaultOffsetMs = 100L,
            newDefaultOffsetMs = 50L
        )

        val playlist = repository.playlists.value.single()
        assertEquals(350L, playlist.songs.single().userLyricOffsetMs)
        assertTrue(playlist.modifiedAt > 10L)
        assertEquals(1L, syncStore.mutationVersion)
        assertEquals(1, autoSyncTriggerCount)
    }

    @Test
    fun `same playlist state still commits restored playlist mutation`() = runTest {
        val playlist = LocalPlaylist(id = 148L, name = "restored")
        val syncStore = RecordingSyncMutationStore()
        var autoSyncTriggerCount = 0
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "same_state_restore.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = true,
            syncMutationStore = syncStore,
            autoSyncTrigger = { autoSyncTriggerCount++ }
        )
        repository.updatePlaylists(listOf(playlist))

        repository.updatePlaylists(
            playlists = listOf(playlist),
            triggerSync = true,
            restoredPlaylistIds = setOf(playlist.id)
        )

        assertEquals(listOf(playlist.id), syncStore.applied.single().restoredPlaylistIds)
        assertEquals(1L, syncStore.mutationVersion)
        assertEquals(1, autoSyncTriggerCount)
    }

    @Test
    fun `sync apply is rejected after local mutation epoch changes`() = runTest {
        val initial = LocalPlaylist(id = 149L, name = "initial")
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "guarded_sync_apply.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            syncMutationStore = syncStore
        )
        repository.updatePlaylists(listOf(initial))
        val expectedMutationVersion = syncStore.mutationVersion
        repository.updatePlaylists(
            playlists = listOf(initial.copy(name = "local")),
            triggerSync = true
        )

        val applied = repository.applySyncedPlaylistsIfUnchanged(
            playlists = listOf(initial.copy(name = "remote")),
            expectedMutationVersion = expectedMutationVersion
        )

        assertFalse(applied)
        assertEquals("local", repository.playlists.value.single().name)
    }
}
