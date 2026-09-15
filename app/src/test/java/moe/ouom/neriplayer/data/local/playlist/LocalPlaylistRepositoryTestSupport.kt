package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.toPlaybackSongItem
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

abstract class LocalPlaylistRepositoryTestSupport {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUpCoverMapper() {
        CoverUrlMapper.installForTest(CoverUrlMapper.createForTest())
    }

    @After
    fun tearDownCoverMapper() {
        CoverUrlMapper.installForTest(null)
    }
























































    internal fun mockContext(): Context {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getString(R.string.playlist_create)).thenReturn("Playlist")
        `when`(context.getString(R.string.favorite_my_music)).thenReturn("Favorites")
        `when`(context.getString(R.string.local_files)).thenReturn("Local Files")
        return context
    }

    internal fun localSong(index: Int, name: String = "song-$index"): SongItem {
        val path = File(tempFolder.root, "song-$index.mp3").absolutePath
        return SongItem(
            id = index.toLong(),
            name = name,
            artist = "artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1000L + index,
            coverUrl = null,
            mediaUri = path,
            localFilePath = path
        )
    }

    internal fun legacyLocalSong(index: Long, name: String): SongItem {
        return SongItem(
            id = index,
            name = name,
            artist = "artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 269_000L,
            coverUrl = null,
            localFileName = "$name.mp3",
            channelId = "local"
        )
    }

    internal fun scannedAliasSong(
        id: Long,
        mediaUri: String,
        localFilePath: String? = null
    ): SongItem {
        return SongItem(
            id = id,
            name = "晴天",
            artist = "周杰伦",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 269_000L,
            coverUrl = null,
            mediaUri = mediaUri,
            localFileName = "周杰伦 - 晴天.mp3",
            localFilePath = localFilePath,
            channelId = "local",
            audioId = id.toString()
        )
    }

    internal fun remoteNeteaseSong(
        id: Long = 42L,
        name: String = "song",
        addedAt: Long = 0L
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "artist",
            album = "NeteaseAlbum",
            albumId = 7L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = id.toString(),
            addedAt = addedAt
        )
    }

    internal fun songJson(id: Long, name: String, addedAt: Long): String {
        return """
            {
              "id": $id,
              "name": "$name",
              "artist": "artist",
              "album": "NeteaseAlbum",
              "albumId": 7,
              "durationMs": 1000,
              "coverUrl": null,
              "channelId": "netease",
              "audioId": "$id",
              "addedAt": $addedAt
            }
        """.trimIndent()
    }

    internal fun downloadedLocalCopy(source: SongItem): SongItem {
        val path = File(tempFolder.root, "song.mp3").absolutePath
        return SongItem(
            id = 99L,
            name = source.name,
            artist = source.artist,
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = source.durationMs,
            coverUrl = null,
            mediaUri = path,
            localFileName = "song.mp3",
            localFilePath = path,
            channelId = "local",
            audioId = "99",
            sourceStableKey = source.stableKey()
        )
    }

    internal fun downloadedPlaybackCopy(
        source: SongItem,
        downloadedId: Long = source.id
    ): SongItem {
        val path = File(tempFolder.root, "downloaded-song.mp3").absolutePath
        return DownloadedSong(
            id = downloadedId,
            name = source.name,
            artist = source.artist,
            album = "Local Files",
            filePath = path,
            fileSize = 1L,
            downloadTime = 1L,
            coverUrl = source.coverUrl,
            durationMs = source.durationMs,
            stableKey = source.stableKey(),
            sourceIdentityAlbum = source.identity().album,
            sourceMediaUri = source.identity().mediaUri,
            sourceChannelId = source.channelId,
            sourceAudioId = source.audioId,
            sourceSubAudioId = source.subAudioId,
            sourcePlaylistContextId = source.playlistContextId
        ).toPlaybackSongItem()
    }

    internal fun playlistJson(
        id: Long,
        name: String,
        songs: List<SongItem> = emptyList()
    ): String {
        val songsJson = songs.joinToString(separator = ",") { song ->
            songJson(song.id, song.name, song.addedAt)
        }
        return """
            [
              {
                "id": $id,
                "name": "$name",
                "songs": [$songsJson],
                "modifiedAt": 1000,
                "customCoverUrl": null,
                "songOrderVersion": $DISPLAY_ORDER_SONG_ORDER_VERSION
              }
            ]
        """.trimIndent()
    }

    internal class FailingCommitStorage(
        var primary: String?
    ) : LocalPlaylistStorage {
        override fun readPrimary(): String? = primary

        override fun readBackup(): String? = null

        override fun commit(
            text: String,
            rotateBackup: Boolean,
            replaceBackupWithCommittedPrimary: Boolean
        ) {
            throw IOException("simulated write failure")
        }

        override fun quarantinePrimary(): File? = null
    }

    internal class BlockingReadStorage(
        internal var primary: String?
    ) : LocalPlaylistStorage {
        val primaryReadStarted = CountDownLatch(1)
        val allowPrimaryRead = CountDownLatch(1)
        val primaryReadThread = AtomicReference<Thread>()

        override fun readPrimary(): String? {
            primaryReadThread.set(Thread.currentThread())
            primaryReadStarted.countDown()
            check(allowPrimaryRead.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release playlist initialization"
            }
            return primary
        }

        override fun readBackup(): String? = null

        override fun commit(
            text: String,
            rotateBackup: Boolean,
            replaceBackupWithCommittedPrimary: Boolean
        ) {
            primary = text
        }

        override fun quarantinePrimary(): File? = null
    }

    internal class RecordingStorage(
        var primary: String?,
        internal val backup: String? = null,
        var failCommit: Boolean = false
    ) : LocalPlaylistStorage {
        var pendingSyncMutation: String? = null
        var commitCount: Int = 0

        override fun readPrimary(): String? = primary

        override fun readBackup(): String? = backup

        override fun commit(
            text: String,
            rotateBackup: Boolean,
            replaceBackupWithCommittedPrimary: Boolean
        ) {
            commitCount++
            if (failCommit) throw IOException("simulated write failure")
            primary = text
        }

        override fun quarantinePrimary(): File? = null

        override fun readPendingSyncMutation(): String? = pendingSyncMutation

        override fun writePendingSyncMutation(text: String) {
            pendingSyncMutation = text
        }

        override fun clearPendingSyncMutation() {
            pendingSyncMutation = null
        }
    }

    internal class RecordingSyncMutationStore(
        internal val failApply: Boolean = false
    ) : LocalPlaylistSyncMutationStore {
        val applied = mutableListOf<LocalPlaylistSyncMutation>()
        var allocatedTokenCount = 0
            internal set
        var mutationVersion = 0L
            internal set
        internal var nextCounter = 1L

        override fun getOrCreateDeviceId(): String = "test-device"

        override fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> {
            require(count >= 0)
            allocatedTokenCount += count
            return List(count) {
                SyncCausalToken(
                    deviceId = getOrCreateDeviceId(),
                    counter = nextCounter++
                )
            }
        }

        override fun getSyncMutationVersion(): Long = mutationVersion

        override fun markSyncMutation(): Long {
            mutationVersion += 1L
            return mutationVersion
        }

        override fun apply(mutation: LocalPlaylistSyncMutation) {
            if (failApply) throw IOException("simulated sync mutation failure")
            applied += mutation
        }
    }

}
