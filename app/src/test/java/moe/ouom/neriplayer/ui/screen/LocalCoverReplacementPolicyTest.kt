package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCoverReplacementPolicyTest {

    @Test
    fun `allows cover picker for pure local song`() {
        assertTrue(shouldAllowLocalCoverReplacement(pureLocalSong()))
    }

    @Test
    fun `rejects cover picker for remote song`() {
        val remoteSong = pureLocalSong().copy(
            album = "Netease",
            albumId = 123L,
            mediaUri = null,
            localFilePath = null,
            channelId = "netease",
            sourceStableKey = null
        )

        assertFalse(shouldAllowLocalCoverReplacement(remoteSong))
    }

    @Test
    fun `rejects cover picker for local copy with remote source identity`() {
        val downloadedRemoteSong = pureLocalSong().copy(
            channelId = "netease",
            audioId = "42",
            sourceStableKey = SongIdentity(
                id = 42L,
                album = "netease",
                mediaUri = null
            ).stableKey()
        )

        assertFalse(shouldAllowLocalCoverReplacement(downloadedRemoteSong))
    }

    @Test
    fun `keeps pending target when current song still matches`() {
        val targetSong = pureLocalSong()
        val currentSong = targetSong.copy(customCoverUrl = "content://cover/updated")

        assertEquals(
            targetSong,
            resolvePendingLocalCoverReplacementTarget(targetSong, currentSong)
        )
    }

    @Test
    fun `drops pending target when current song changes`() {
        val targetSong = pureLocalSong()
        val currentSong = targetSong.copy(
            id = 43L,
            mediaUri = "content://media/external/audio/media/43",
            audioId = "43"
        )

        assertNull(resolvePendingLocalCoverReplacementTarget(targetSong, currentSong))
    }

    @Test
    fun `drops pending target when current song is remote`() {
        val targetSong = pureLocalSong()
        val currentSong = targetSong.copy(
            album = "Netease",
            albumId = 123L,
            mediaUri = null,
            localFilePath = null,
            channelId = "netease",
            sourceStableKey = null
        )

        assertNull(resolvePendingLocalCoverReplacementTarget(targetSong, currentSong))
    }

    @Test
    fun `drops pending target when target has remote source identity`() {
        val downloadedRemoteSong = pureLocalSong().copy(
            channelId = "netease",
            audioId = "42",
            sourceStableKey = SongIdentity(
                id = 42L,
                album = "netease",
                mediaUri = null
            ).stableKey()
        )

        assertNull(
            resolvePendingLocalCoverReplacementTarget(
                downloadedRemoteSong,
                downloadedRemoteSong
            )
        )
    }

    private fun pureLocalSong(): SongItem {
        return SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/42",
            localFilePath = null,
            channelId = "local",
            audioId = "42"
        )
    }
}
