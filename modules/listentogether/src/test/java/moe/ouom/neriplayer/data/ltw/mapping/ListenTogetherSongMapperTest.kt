package moe.ouom.neriplayer.data.ltw.mapping

import android.net.Uri
import moe.ouom.neriplayer.data.ltw.testing.TestSongMapper
import moe.ouom.neriplayer.data.ltw.testing.testRoom
import moe.ouom.neriplayer.data.ltw.testing.testSong
import moe.ouom.neriplayer.data.ltw.testing.testTrack
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ListenTogetherSongMapperTest {
    @Test
    fun `explicit source identity wins while legacy songs infer channel and identifiers`() = with(TestSongMapper) {
        assertEquals("custom", testSong(channel = "custom").resolvedChannelId())
        assertEquals("local", testSong().copy(channelId = null, localFilePath = "/song.flac").resolvedChannelId())
        assertEquals("bilibili", testSong().copy(channelId = "", audioId = " ", album = "Bilibili|55|extra").resolvedChannelId())
        val bili = testSong().copy(channelId = null, audioId = null, subAudioId = " ", album = "Bilibili|55|extra")
        assertEquals("1", bili.resolvedAudioId())
        assertEquals("55", bili.resolvedSubAudioId())
        assertNull(bili.copy(album = "Bilibili").resolvedSubAudioId())
        assertEquals("netease", testSong().copy(channelId = null).resolvedChannelId())
        val youtube = testSong().copy(channelId = null, audioId = null, mediaUri = "ytmusic://video/video42")
        assertEquals("youtubeMusic", youtube.resolvedChannelId())
        assertEquals("video42", youtube.resolvedAudioId())
        assertNull(testSong(channel = "youtubeMusic").copy(audioId = null).resolvedAudioId())
        assertEquals("explicit", bili.copy(subAudioId = "explicit").resolvedSubAudioId())
    }

    @Test
    fun `playlist context is explicit or read only for youtube`() = with(TestSongMapper) {
        assertEquals("playlist", testSong().copy(playlistContextId = "playlist").resolvedPlaylistContextId())
        assertNull(testSong().copy(playlistContextId = " ").resolvedPlaylistContextId())
        assertNull(testSong(channel = "youtubeMusic").resolvedPlaylistContextId())
        val uri = mock(Uri::class.java)
        `when`(uri.getQueryParameter("playlistId")).thenReturn("parsed")
        mockStatic(Uri::class.java).use { parser ->
            parser.`when`<Uri> { Uri.parse("ytmusic://video/1?playlistId=parsed") }.thenReturn(uri)
            assertEquals("parsed", testSong(channel = "youtubeMusic").copy(mediaUri = "ytmusic://video/1?playlistId=parsed").resolvedPlaylistContextId())
        }
    }

    @Test
    fun `inbound mapping retains source metadata while excluding shared stream from resolver input`() = with(TestSongMapper) {
        for (channel in listOf("netease", "bilibili", "local", "youtubeMusic", "unknown")) {
            for (id in listOf("42", "nonnumeric")) {
                val track = testTrack(id, channel).copy(streamUrl = "https://m701.music.126.net/audio", subAudioId = "55", playlistContextId = "playlist")
                val song = track.toSongItem()
                assertEquals(id, song.audioId)
                assertNull(song.streamUrl)
                assertEquals("Song $id", song.name)
                assertEquals(if (channel == "unknown") "netease" else channel, song.channelId)
            }
        }
        assertEquals("Local", testTrack(channel = "local").toSongItem().album)
        assertEquals("Bilibili", testTrack(channel = "bilibili").toSongItem().album)
        assertEquals("Bilibili|55", testTrack(channel = "bilibili").copy(subAudioId = "55").toSongItem().album)
        assertEquals("ytmusic://video/1", testTrack(channel = "youtubeMusic").toSongItem().mediaUri)
        val supplied = testTrack(channel = "youtubeMusic").copy(mediaUri = "kept", album = "Album", playlistContextId = " ").toSongItem()
        assertEquals("kept", supplied.mediaUri)
        assertEquals("Album", supplied.album)
    }

    @Test
    fun `outbound mapping preserves overrides and rejects unshareable identities`() = with(TestSongMapper) {
        val source = testSong().copy(customName = "Custom", customArtist = "Singer", customCoverUrl = "cover")
        val track = requireNotNull(source.toListenTogetherTrackOrNull())
        assertEquals("Custom", track.name)
        assertEquals("Singer", track.artist)
        assertEquals("cover", track.coverUrl)
        assertNull(testSong(channel = "LOCAL").toListenTogetherTrackOrNull())
        assertNotNull(testSong(channel = "local").toListenTogetherTrackOrNull(includeLocal = true))
        assertNull(testSong(channel = "youtubeMusic").copy(audioId = null).toListenTogetherTrackOrNull())
        assertNull(testRoom(emptyList()).targetSongItem())
        assertEquals("1", testRoom().targetSongItem()?.audioId)
    }

    @Test
    fun `media identity compares each component without using presentation metadata`() = with(TestSongMapper) {
        val song = testSong()
        assertTrue(song.sameTrackAs(song.copy(name = "Changed")))
        assertFalse(song.sameTrackAs(song.copy(channelId = "bilibili")))
        assertFalse(song.sameTrackAs(song.copy(audioId = "2")))
        assertFalse(song.sameTrackAs(song.copy(subAudioId = "55")))
        assertFalse(song.sameTrackAs(song.copy(playlistContextId = "playlist")))
        assertFalse(shouldApplyListenTogetherQueueUpdateWithoutReload("PLAY", listOf(song), song, listOf(song), 0))
        assertFalse(shouldApplyListenTogetherQueueUpdateWithoutReload("SET_QUEUE", emptyList(), song, listOf(song), 0))
        assertFalse(shouldApplyListenTogetherQueueUpdateWithoutReload("SET_QUEUE", listOf(song), null, listOf(song), 0))
        assertFalse(shouldApplyListenTogetherQueueUpdateWithoutReload("SET_QUEUE", listOf(song), song, listOf(song), 1))
        assertFalse(shouldApplyListenTogetherQueueUpdateWithoutReload("SET_QUEUE", listOf(song), song, listOf(testSong("2")), 0))
    }

    @Test
    fun `shareable snapshot filters local songs and publishes verified urls only on selected track`() = with(TestSongMapper) {
        val songs = listOf(testSong("1"), testSong("2", "local"), testSong("3"))
        val raw = "https://m701.music.126.net/unverified"
        val verified = "https://m701.music.126.net/verified"
        val (queue, index) = songs.map { it.copy(streamUrl = raw) }.toShareableQueueSnapshot(2, ListenTogetherRoomSettings(shareAudioLinks = true), resolvedCurrentStreamUrls = listOf(verified))
        assertEquals(listOf("netease:1", "netease:3"), queue.map { it.stableKey })
        assertEquals(1, index)
        assertTrue(queue[0].streamUrls.isEmpty())
        assertEquals(listOf(verified), queue[1].streamUrls)
        assertTrue(songs.toShareableQueueSnapshot(1).first.all { it.streamUrls.isEmpty() })
        assertTrue(listOf(testSong(channel = "local")).toShareableQueueSnapshot(0).first.isEmpty())
        assertEquals(0, emptyList<moe.ouom.neriplayer.data.model.SongItem>().toShareableQueueSnapshot(99).second)
        assertEquals(0, songs.toShareableQueueSnapshot(-99).second)
        assertEquals(1, songs.toShareableQueueSnapshot(99).second)
    }

    @Test
    fun `stable keys distinguish source subtracks and playlist contexts`() {
        assertEquals("bilibili:1:55", buildStableTrackKey("bilibili", "1", "55"))
        assertEquals("bilibili:1", buildStableTrackKey("bilibili", "1", " "))
        assertEquals("youtubeMusic:1:playlist", buildStableTrackKey("youtubeMusic", "1", playlistContextId = "playlist"))
        assertEquals("youtubeMusic:1", buildStableTrackKey("youtubeMusic", "1"))
        assertEquals("netease:1", buildStableTrackKey("netease", "1", "ignored"))
    }
}
