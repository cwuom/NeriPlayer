package moe.ouom.neriplayer.data.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.identity.stableRemoteIdentityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadedSongSourceProjectionTest {
    @Test
    fun `netease downloads project every stored field onto the playback item`() {
        val song = downloaded(
            stableKey = "12|netease|", coverPath = "/covers/12.jpg", coverUrl = "https://img.example/12.jpg",
            matchedLyricSource = "QQ_MUSIC", downloadTime = 1_234L, sourcePlaylistContextId = "playlist:1"
        )

        assertEquals(
            SongItem(
                id = 12, name = "Song", artist = "Artist", album = "Album", albumId = 0, durationMs = 90,
                coverUrl = "/covers/12.jpg", mediaUri = "/music/Song.flac", matchedLyric = "lyric",
                matchedLyricSource = MusicPlatform.QQ_MUSIC, matchedSongId = "m1", userLyricOffsetMs = 30,
                customName = "Custom", originalName = "Original", originalCoverUrl = "https://img.example/12.jpg",
                localFileName = "Song.flac", localFilePath = "/music/Song.flac", channelId = "netease", audioId = "12",
                playlistContextId = "playlist:1", sourceStableKey = "12|netease|", logicalCreatedAtMs = 1_234L,
                createdAtSource = "MANAGED_COMMIT", createdAtConfidence = "EXACT"
            ),
            song.toPlaybackSongItem()
        )
    }

    @Test
    fun `untimed downloads without a source stay local and keep unknown lyric sources out`() {
        val item = downloaded(
            coverUrl = "content://media/external/images/1", originalCoverUrl = null,
            matchedLyricSource = "nope", downloadTime = 0L
        ).toPlaybackSongItem(
            playbackUri = "content://downloads/5", localFileName = null, localFilePath = null, resolvedDurationMs = -1L
        )

        assertEquals(
            listOf<Any?>(5L, LocalSongSupport.LOCAL_ALBUM_IDENTITY, 0L, "content://media/external/images/1", null, null, null, null, null, null),
            listOf(
                item.id, item.album, item.durationMs, item.coverUrl, item.originalCoverUrl, item.matchedLyricSource,
                item.channelId, item.logicalCreatedAtMs, item.createdAtSource, item.createdAtConfidence
            )
        )
    }

    @Test
    fun `stored source fields rebuild the remote identity when the stable key is missing`() {
        val bilibili = downloaded(sourceChannelId = "bilibili", sourceAudioId = " BV1 ", sourceSubAudioId = " 7 ")
        val neteaseAlbum = downloaded(sourceIdentityAlbum = " netease ", sourceAudioId = "99")
        val biliIdentity = SongIdentity(stableRemoteIdentityId("bilibili", "BV1", "7"), "bilibili", null)

        assertEquals(biliIdentity, bilibili.remoteSourceIdentityOrNull())
        assertEquals(
            listOf<Any?>(5L, "Album", "bilibili", "BV1", "7", biliIdentity.stableKey()),
            bilibili.toPlaybackSongItem().sourceFields()
        )
        assertEquals(SongIdentity(99, "netease", null), neteaseAlbum.remoteSourceIdentityOrNull())
        assertEquals(listOf<Any?>(99L, "Album", "netease", "99", null, "99|netease|"), neteaseAlbum.toPlaybackSongItem().sourceFields())
    }

    @Test
    fun `source fields without a remote address or with local albums do not rebuild an identity`() {
        assertNull(downloaded(id = 0, sourceChannelId = "bilibili", sourceMediaUri = "content://media/1").remoteSourceIdentityOrNull())
        assertNull(downloaded(sourceChannelId = "local", sourceAudioId = "1").remoteSourceIdentityOrNull())
        assertNull(downloaded(sourceIdentityAlbum = LocalSongSupport.LOCAL_ALBUM_IDENTITY, sourceAudioId = "1").remoteSourceIdentityOrNull())
        assertNull(downloaded(sourceIdentityAlbum = "Local Files", sourceAudioId = "1").remoteSourceIdentityOrNull())
        assertEquals(
            downloaded(sourceIdentityAlbum = "bilibili", sourceAudioId = "5").remoteSourceIdentityOrNull(),
            downloaded(sourceIdentityAlbum = "bilibili", sourceMediaUri = " https://cdn.example.com/a.mp3 ").remoteSourceIdentityOrNull()
        )
    }

    @Test
    fun `legacy bilibili albums and raw local channels fill the playback address`() {
        val legacy = downloaded(id = 77, album = "Bilibili|123|part").toPlaybackSongItem()
        val rawLocal = downloaded(sourceChannelId = "local", sourceAudioId = " ").toPlaybackSongItem()
        val noChannel = downloaded(album = "Other").toPlaybackSongItem()

        assertEquals(listOf<Any?>(77L, LocalSongSupport.LOCAL_ALBUM_IDENTITY, "bilibili", "77", "123", null), legacy.sourceFields())
        assertEquals(listOf<Any?>(5L, LocalSongSupport.LOCAL_ALBUM_IDENTITY, "local", " ", null, null), rawLocal.sourceFields())
        assertEquals(listOf<Any?>(5L, LocalSongSupport.LOCAL_ALBUM_IDENTITY, null, null, null, null), noChannel.sourceFields())
        assertEquals(null, downloaded(id = 0, album = "Bilibili").toPlaybackSongItem().audioId)
    }

    @Test
    fun `netease song ids only replace the download id for plain positive netease identities`() {
        val withMedia = downloaded(stableKey = "12|netease|https://cdn.example.com/a").toPlaybackSongItem()
        val zero = downloaded(stableKey = "0|netease|").toPlaybackSongItem()
        val otherChannel = downloaded(stableKey = "12|netease|", sourceChannelId = "bilibili", sourceAudioId = "BV2").toPlaybackSongItem()
        val bilibiliKey = downloaded(stableKey = "12|bilibili|").toPlaybackSongItem()

        assertEquals(listOf<Any?>(5L, "Album", "netease", "12"), withMedia.sourceFields().take(4))
        assertEquals(listOf<Any?>(5L, "Album", "netease", "0"), zero.sourceFields().take(4))
        assertEquals(listOf<Any?>(5L, "Album", "bilibili", "BV2"), otherChannel.sourceFields().take(4))
        assertEquals(listOf<Any?>(5L, "Album", "bilibili", "5"), bilibiliKey.sourceFields().take(4))
    }

    @Test
    fun `blank or placeholder albums fall back to the remote identity album`() {
        assertEquals("netease", downloaded(album = " ", stableKey = "12|netease|").toPlaybackSongItem().album)
        assertEquals("netease", downloaded(album = LocalSongSupport.LOCAL_ALBUM_IDENTITY, stableKey = "12|netease|").toPlaybackSongItem().album)
    }

    @Test
    fun `file references only expose names for absolute paths and file uris`() {
        assertEquals("a.mp3", localFileNameFromFileReference("/music/a.mp3"))
        assertEquals("b c.mp3", localFileNameFromFileReference("FILE:///music/b%20c.mp3"))
        assertNull(localFileNameFromFileReference(null))
        assertNull(localFileNameFromFileReference(" "))
        assertNull(localFileNameFromFileReference("content://media/1"))
        assertNull(localFileNameFromFileReference("file:bad path"))
        assertNull(localFileNameFromFileReference("file://host"))
        assertNull(localFileNameFromFileReference("/"))
    }

    private fun SongItem.sourceFields() = listOf<Any?>(id, album, channelId, audioId, subAudioId, sourceStableKey)

    private fun downloaded(
        id: Long = 5,
        album: String = "Album",
        stableKey: String? = null,
        coverPath: String? = null,
        coverUrl: String? = null,
        originalCoverUrl: String? = null,
        matchedLyricSource: String? = null,
        downloadTime: Long = 1L,
        sourceIdentityAlbum: String? = null,
        sourceMediaUri: String? = null,
        sourceChannelId: String? = null,
        sourceAudioId: String? = null,
        sourceSubAudioId: String? = null,
        sourcePlaylistContextId: String? = null
    ) = DownloadedSong(
        id = id, name = "Song", artist = "Artist", album = album, filePath = "/music/Song.flac", fileSize = 1L,
        downloadTime = downloadTime, coverPath = coverPath, coverUrl = coverUrl, matchedLyric = "lyric",
        matchedLyricSource = matchedLyricSource, matchedSongId = "m1", userLyricOffsetMs = 30, customName = "Custom",
        originalName = "Original", originalCoverUrl = originalCoverUrl, durationMs = 90, stableKey = stableKey,
        sourceIdentityAlbum = sourceIdentityAlbum, sourceMediaUri = sourceMediaUri, sourceChannelId = sourceChannelId,
        sourceAudioId = sourceAudioId, sourceSubAudioId = sourceSubAudioId, sourcePlaylistContextId = sourcePlaylistContextId
    )
}
