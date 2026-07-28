package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.validation.sanitizeListenTogetherNicknameOrNull
import moe.ouom.neriplayer.listentogether.validation.validateListenTogetherJoinSecret
import moe.ouom.neriplayer.listentogether.validation.validateListenTogetherNickname
import moe.ouom.neriplayer.listentogether.validation.validateListenTogetherRoomCreation
import moe.ouom.neriplayer.listentogether.playback.hasShareableListenTogetherTrackAt
import moe.ouom.neriplayer.listentogether.playback.isShareableForListenTogether
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherValidationTest {

    @Test
    fun `nickname validation accepts han letters and digits without regex init crash`() {
        assertNull(validateListenTogetherNickname("灵梦Alice123"))
    }

    @Test
    fun `nickname validation rejects unsupported punctuation`() {
        assertNotNull(validateListenTogetherNickname("Alice_123"))
    }

    @Test
    fun `nickname sanitizer trims valid nickname`() {
        assertEquals("测试123", sanitizeListenTogetherNicknameOrNull("  测试123  "))
    }

    @Test
    fun `join secret validation requires a bounded nonblank value`() {
        assertNotNull(validateListenTogetherJoinSecret(null))
        assertNotNull(validateListenTogetherJoinSecret("   "))
        assertNotNull(validateListenTogetherJoinSecret("a".repeat(257)))
        assertNull(validateListenTogetherJoinSecret("secret-value"))
    }

    @Test
    fun `room creation rejects local current song even with remote queue entries`() {
        val local = songItem(channelId = "local", audioId = "1")
        val remote = songItem(channelId = "netease", audioId = "2")

        assertNotNull(
            validateListenTogetherRoomCreation(
                queue = listOf(local, remote),
                currentIndex = 0,
                currentSong = local
            )
        )
        assertNull(
            validateListenTogetherRoomCreation(
                queue = listOf(remote),
                currentIndex = 0,
                currentSong = remote
            )
        )
    }

    @Test
    fun `room creation reports a missing current song separately`() {
        val remote = songItem(channelId = "netease", audioId = "1")

        assertNotNull(
            validateListenTogetherRoomCreation(
                queue = listOf(remote),
                currentIndex = 0,
                currentSong = null
            )
        )
    }

    @Test
    fun `shareability policy rejects local tracks regardless of channel case`() {
        val remote = songItem(channelId = "netease", audioId = "1")
        val local = songItem(channelId = "LOCAL", audioId = "2")

        assertNull(validateListenTogetherRoomCreation(listOf(remote), 0, remote.copy(streamUrl = "https://cdn.example.com/1.m4a")))
        assertTrue(remote.isShareableForListenTogether())
        assertFalse(local.isShareableForListenTogether())
        assertFalse(listOf(remote, local).hasShareableListenTogetherTrackAt(1))
    }

    private fun songItem(channelId: String, audioId: String): SongItem {
        return SongItem(
            id = audioId.toLong(),
            name = "Song $audioId",
            artist = "Artist",
            album = "",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            channelId = channelId,
            audioId = audioId
        )
    }
}
