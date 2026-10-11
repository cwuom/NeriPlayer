package moe.ouom.neriplayer.data.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.sync.identity.stableRemoteIdentityId
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongIdentityExtensionsTest {
    @Test
    fun `songs without a remote source fall back to their own identity or local metadata`() {
        val stream = song(id = 5, album = "Some Album", mediaUri = "https://example.com/a.mp3")
        val named = localSong(localFileName = " A.MP3 ", originalName = " Tïtle ", artist = "Some   Artist", channelId = "local", audioId = "0")
        val fromUri = localSong(localFilePath = "/music/dir/", mediaUri = "content://media/external/audio/Caf%C3%A9.mp3", name = "x")
        val mediaOnly = localSong(localFilePath = null, mediaUri = "content://media/external/audio/media/42")
        val authorityOnly = localSong(localFilePath = "/music/dir/", mediaUri = "content://media")
        val emptyPath = localSong(localFilePath = "/music/dir/", mediaUri = "file:///")
        val anonymous = localSong(localFilePath = "/", name = " ", artist = " ")

        assertEquals("song:${stream.stableKey()}", stream.playbackVisualKey())
        assertEquals(listOf("song:${stream.stableKey()}"), stream.playbackVisualKeyAliases())
        assertEquals("local:a.mp3|tïtle|some artist", named.playbackVisualKey())
        assertEquals(listOf("local:a.mp3|tïtle|some artist"), named.playbackVisualKeyAliases())
        assertEquals("local:café.mp3|x|artist", fromUri.playbackVisualKey())
        assertEquals("local:42|song|artist", mediaOnly.playbackVisualKey())
        assertEquals("local:media|song|artist", authorityOnly.playbackVisualKey())
        assertEquals("local:song|artist", emptyPath.playbackVisualKey())
        assertEquals("local-id:9", anonymous.playbackVisualKey())
        assertEquals(listOf("local-id:9"), anonymous.playbackVisualKeyAliases())
    }

    @Test
    fun `download identity is rebuilt from the channel and audio fields of a local copy`() {
        val netease = localSong(channelId = "netease", audioId = " 99 ")
        val bilibili = localSong(id = 7, channelId = "bilibili", subAudioId = " 3 ")

        assertEquals(SongIdentity(99, "netease", null), netease.remoteDownloadIdentityOrNull())
        assertEquals(listOf("remote:99|netease|"), netease.playbackVisualKeyAliases())
        assertEquals(SongIdentity(stableRemoteIdentityId("bilibili", "7", "3"), "bilibili", null), bilibili.remoteDownloadIdentityOrNull())
        assertNull(localSong(channelId = "local", audioId = "1").remoteDownloadIdentityOrNull())
        assertNull(localSong(id = 0, channelId = "bilibili").remoteDownloadIdentityOrNull())
        assertNull(localSong(channelId = " ").remoteDownloadIdentityOrNull())
    }

    @Test
    fun `sync copies of local songs point back at their remote source`() {
        val remote = song(id = 5, album = "Netease", channelId = "netease", audioId = "5")
        val neteaseCopy = localSong(
            sourceStableKey = "12|netease|", channelId = "local", audioId = "77", coverUrl = "file:///cover.jpg",
            originalCoverUrl = "https://img.example/c.jpg", customCoverUrl = "https://img.example/custom.jpg", streamUrl = "file:///a.mp3"
        )

        assertSame(remote, remote.toSyncableRemoteSongOrNull())
        assertNull(localSong().toSyncableRemoteSongOrNull())
        assertEquals(
            neteaseCopy.copy(
                id = 12, album = "netease", albumId = 0, mediaUri = null, localFileName = null, localFilePath = null,
                coverUrl = "https://img.example/c.jpg", originalCoverUrl = "https://img.example/c.jpg", channelId = "netease",
                audioId = "12", subAudioId = null, streamUrl = null
            ),
            neteaseCopy.toSyncableRemoteSongOrNull()
        )
    }

    @Test
    fun `sync copies keep their own address unless the source is a netease track`() {
        val bilibili = localSong(
            id = 300, sourceStableKey = "5|bilibili|", channelId = "bilibili", audioId = "BV1xx", subAudioId = " 2 ",
            coverUrl = "https://img.example/b.jpg"
        ).toSyncableRemoteSongOrNull()
        val unaddressed = localSong(id = 300, sourceStableKey = "5|bilibili|", channelId = "bilibili").toSyncableRemoteSongOrNull()
        val neteaseChannel = localSong(id = 500, sourceStableKey = "12|netease|", channelId = "netease").toSyncableRemoteSongOrNull()
        val sourceOnly = localSong(id = 500, sourceStableKey = "12|bilibili|", channelId = null).toSyncableRemoteSongOrNull()

        assertEquals(listOf<Any?>(300L, "bilibili", "BV1xx", "2"), listOf(bilibili?.id, bilibili?.channelId, bilibili?.audioId, bilibili?.subAudioId))
        assertEquals("https://img.example/b.jpg", bilibili?.coverUrl)
        assertEquals(listOf<Any?>(5L, "bilibili", null), listOf(unaddressed?.id, unaddressed?.channelId, unaddressed?.audioId))
        assertEquals(listOf<Any?>(12L, "netease", "12", null), listOf(neteaseChannel?.id, neteaseChannel?.channelId, neteaseChannel?.audioId, neteaseChannel?.subAudioId))
        assertEquals(listOf<Any?>(12L, "bilibili", null, null), listOf(sourceOnly?.id, sourceOnly?.channelId, sourceOnly?.audioId, sourceOnly?.subAudioId))
    }

    @Test
    fun `remote identities collapse youtube addresses and ignore channels without audio`() {
        val videoUri = buildYouTubeMusicMediaUri("dQw4w9WgXcQ")
        val youtube = SongIdentity(stableYouTubeMusicId("dQw4w9WgXcQ"), "youtube_music", videoUri)
        val unaddressed = song(id = 0, album = "Album", channelId = "netease", audioId = " ")

        assertEquals(youtube, song(id = 1, album = "Album", mediaUri = videoUri).identity())
        assertEquals(youtube, song(id = 1, album = "Album", channelId = "ytmusic", audioId = "dQw4w9WgXcQ").identity())
        assertEquals(SongIdentity(77, "netease", null), song(id = 77, album = "Album", channelId = "netease").identity())
        assertEquals(unaddressed.copy(channelId = null).identity(), unaddressed.identity())
    }

    @Test
    fun `only stale local copies of netease tracks recover their remote source`() {
        val stale = localSong(sourceStableKey = " 12|netease| ", streamUrl = "file:///a.mp3")

        assertEquals(
            stale.copy(
                id = 12, album = "Netease", albumId = 0, mediaUri = null, localFileName = null, localFilePath = null,
                channelId = "netease", audioId = "12", subAudioId = null, sourceStableKey = null, streamUrl = null
            ),
            stale.recoverNeteaseRemoteSourceFromStaleLocalCopy()
        )
        assertNull(song(id = 5, album = "Netease", sourceStableKey = "12|netease|").recoverNeteaseRemoteSourceFromStaleLocalCopy())
        assertNull(localSong().recoverNeteaseRemoteSourceFromStaleLocalCopy())
        assertNull(localSong(sourceStableKey = "12|bilibili|").recoverNeteaseRemoteSourceFromStaleLocalCopy())
        assertNull(localSong(sourceStableKey = "12|netease|https://cdn.example.com/a").recoverNeteaseRemoteSourceFromStaleLocalCopy())
    }

    private fun song(
        id: Long,
        album: String,
        mediaUri: String? = null,
        channelId: String? = null,
        audioId: String? = null,
        sourceStableKey: String? = null
    ) = SongItem(
        id = id, name = "Song", artist = "Artist", album = album, albumId = 0, durationMs = 1_000, coverUrl = null,
        mediaUri = mediaUri, channelId = channelId, audioId = audioId, sourceStableKey = sourceStableKey
    )

    private fun localSong(
        id: Long = 9,
        name: String = "Song",
        artist: String = "Artist",
        originalName: String? = null,
        localFileName: String? = null,
        localFilePath: String? = "/music/song.mp3",
        mediaUri: String? = null,
        coverUrl: String? = null,
        originalCoverUrl: String? = null,
        customCoverUrl: String? = null,
        channelId: String? = null,
        audioId: String? = null,
        subAudioId: String? = null,
        sourceStableKey: String? = null,
        streamUrl: String? = null
    ) = SongItem(
        id = id, name = name, artist = artist, album = "Album", albumId = 3, durationMs = 1_000, coverUrl = coverUrl,
        mediaUri = mediaUri, customCoverUrl = customCoverUrl, originalName = originalName, originalCoverUrl = originalCoverUrl,
        localFileName = localFileName, localFilePath = localFilePath, channelId = channelId, audioId = audioId,
        subAudioId = subAudioId, sourceStableKey = sourceStableKey, streamUrl = streamUrl
    )
}
