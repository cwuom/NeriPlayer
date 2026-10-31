package moe.ouom.neriplayer.data.sync.identity

import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSongIdentityCompatibilityTest {
    @Test
    fun `server references survive display changes and restore from either reversible field`() {
        val ref = ServerSongRef("00000000-0000-0000-0000-000000000001", "song:中文/01")
        val expected = SongIdentity(ref.numericId, ServerSongRef.CHANNEL, ref.mediaUri)
        val wire = SyncSong(id = 123, album = "renamed", channelId = ServerSongRef.CHANNEL,
            audioId = ref.audioId, mediaUri = ref.mediaUri)
        assertEquals(expected, wire.identity())
        assertEquals(expected, wire.copy(mediaUri = null).identity())
        assertEquals(expected, wire.copy(audioId = null, channelId = null).identity())
        assertNotEquals(expected, wire.copy(mediaUri = null, channelId = "netease").identity())
        assertEquals(SongIdentity(0, "other", "neri-server://invalid"),
            SyncSong(id = 0, album = "other", mediaUri = "neri-server://invalid").identity())
    }

    @Test
    fun `explicit aliases and legacy source hints retain their identity`() {
        for (alias in listOf("youtube", "ytmusic", "youtubemusic", "youtube_music")) {
            assertEquals("youtube_music", normalizedChannelId(" $alias ", "", null, false))
        }
        assertEquals("custom", normalizeChannelAlias("custom"))
        assertEquals("netease", normalizedChannelId(null, "Netease songs", "opaque", false))
        assertEquals("bilibili", normalizedChannelId(" ", "Bilibili|9", null, false))
        assertEquals("netease", normalizedChannelId(null, "", " ", true))
        assertNull(normalizedChannelId(null, "other", "opaque", true))
        assertNull(normalizedChannelId(null, "", null, false))
        val media = buildYouTubeMusicMediaUri("video")
        assertEquals("youtube_music", normalizedChannelId(null, "", media, false))
        assertEquals("explicit", normalizedChannelId("EXPLICIT", "", media, false))
    }

    @Test
    fun `audio fallback and multipart identity preserve source distinctions`() {
        val numeric = SyncSong(id = 99, channelId = "netease", audioId = " 42 ")
        assertEquals(SongIdentity(42, "netease", null), numeric.identity())
        assertEquals(SongIdentity(99, "netease", null), numeric.copy(audioId = " ").identity())
        val invalid = SyncSong(id = 0, album = "other", mediaUri = "opaque", audioId = "")
        assertEquals(SongIdentity(0, "other", "opaque"), invalid.identity())
        assertEquals("", normalizedSubAudioId("netease", "9", "Bilibili|8"))
        assertEquals("9", normalizedSubAudioId("bilibili", " 9 ", "Bilibili|8"))
        assertEquals("8", normalizedSubAudioId("bilibili", " ", "Bilibili|8|title"))
        assertEquals("", normalizedSubAudioId("bilibili", null, "Bilibili"))
        assertEquals("", normalizedSubAudioId("bilibili", null, "Bilibili| |title"))
        val bili = numeric.copy(channelId = "bilibili", audioId = "BVabc", subAudioId = "8")
        assertNotEquals(bili.identity(), bili.copy(subAudioId = "9").identity())
        val youtube = numeric.copy(channelId = "ytmusic", audioId = "video")
        assertEquals(youtube.identity(), numeric.copy(mediaUri = buildYouTubeMusicMediaUri("video")).identity())
        assertFalse(numeric.sameIdentityAs(null))
        assertTrue(numeric.sameIdentityAs(numeric.copy(album = "renamed")))
        assertFalse(numeric.sameIdentityAs(numeric.copy(audioId = "43")))
    }
}
