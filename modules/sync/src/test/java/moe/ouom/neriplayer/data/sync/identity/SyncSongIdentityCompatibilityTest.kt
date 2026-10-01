package moe.ouom.neriplayer.data.sync.identity

import moe.ouom.neriplayer.api.youtube.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSongIdentityCompatibilityTest {
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
