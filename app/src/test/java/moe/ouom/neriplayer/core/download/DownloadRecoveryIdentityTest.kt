package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.manager.runtime.isMetadataOwnedBySong
import moe.ouom.neriplayer.core.download.manager.runtime.isRecoveryMetadataOwnedBySong
import moe.ouom.neriplayer.core.download.model.hasDownloadedAudioDurationMismatch
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRecoveryIdentityTest {
    private val song = SongItem(
        id = 6261281674865822304L, name = "红尘客栈", artist = "周杰伦",
        album = "bilibili", albumId = 0L, durationMs = 275000L, coverUrl = null
    )

    @Test
    fun `two absent uris never establish ownership`() {
        val foreign = ManagedDownloadStorage.DownloadedAudioMetadata(
            songId = 117111510797424L, album = "netease", name = "君が生まれた日"
        )
        assertFalse(GlobalDownloadManager.isMetadataOwnedBySong(foreign, song))
        assertFalse(GlobalDownloadManager.isRecoveryMetadataOwnedBySong(foreign, song, null))
        assertFalse(GlobalDownloadManager.isRecoveryMetadataOwnedBySong(
            ManagedDownloadStorage.DownloadedAudioMetadata(), song, null
        ))
    }

    @Test
    fun `strong foreign identity cannot be overridden by operation or numeric id`() {
        val foreign = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "another|netease|", songId = song.id, album = song.album,
            operationId = "same-operation"
        )
        assertFalse(GlobalDownloadManager.isMetadataOwnedBySong(foreign, song))
        assertFalse(GlobalDownloadManager.isRecoveryMetadataOwnedBySong(foreign, song, "same-operation"))
    }

    @Test
    fun `numeric ids from different providers never establish ownership`() {
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            songId = song.id, identityAlbum = "netease", album = "netease"
        )
        assertFalse(GlobalDownloadManager.isMetadataOwnedBySong(metadata, song))
    }

    @Test
    fun `matching stable identity or legacy canonical identity remains recoverable`() {
        assertTrue(GlobalDownloadManager.isMetadataOwnedBySong(
            ManagedDownloadStorage.DownloadedAudioMetadata(stableKey = song.stableKey()), song
        ))
        assertTrue(GlobalDownloadManager.isMetadataOwnedBySong(
            ManagedDownloadStorage.DownloadedAudioMetadata(songId = song.id, identityAlbum = song.identity().album), song
        ))
    }

    @Test
    fun `identityless receipt requires the exact nonempty operation`() {
        val receipt = ManagedDownloadStorage.DownloadedAudioMetadata(operationId = "original")
        assertTrue(GlobalDownloadManager.isRecoveryMetadataOwnedBySong(receipt, song, "original"))
        assertFalse(GlobalDownloadManager.isRecoveryMetadataOwnedBySong(receipt, song, "another"))
        assertFalse(GlobalDownloadManager.isRecoveryMetadataOwnedBySong(receipt, song, ""))
    }

    @Test
    fun `logged wrong audio duration is rejected without rejecting codec padding`() {
        assertTrue(hasDownloadedAudioDurationMismatch(275000L, 216216L))
        assertFalse(hasDownloadedAudioDurationMismatch(216870L, 216921L))
        assertFalse(hasDownloadedAudioDurationMismatch(275000L, null))
    }
}
