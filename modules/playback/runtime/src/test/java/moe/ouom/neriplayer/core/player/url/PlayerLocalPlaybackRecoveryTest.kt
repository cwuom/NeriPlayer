package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerLocalPlaybackRecoveryTest {
    private val selectedReference = "content://provider/tree/Download2/document/Download2/song.flac"

    @Test
    fun `missing manually selected local audio cannot recover a remote source`() {
        assertNull(recoverNeteaseSourceAfterMissingLocal(song(), selectedReference) { true })
    }

    @Test
    fun `unknown local membership cannot recover a remote source`() {
        assertNull(recoverNeteaseSourceAfterMissingLocal(song(), selectedReference) { null })
    }

    @Test
    fun `confirmed non manual legacy download still recovers its remote source`() {
        assertEquals(expectedRemoteSong(), recoverNeteaseSourceAfterMissingLocal(song(), selectedReference) { false })
    }

    @Test
    fun `membership evidence belongs to the exact failed reference rather than a song alias`() {
        val failedReference = "content://provider/tree/old-download/document/old-download/song.flac"
        val queriedReferences = mutableListOf<String>()

        val recovered = recoverNeteaseSourceAfterMissingLocal(song(), failedReference) { reference ->
            queriedReferences += reference
            reference == selectedReference
        }

        assertEquals(expectedRemoteSong(), recovered)
        assertEquals(listOf(failedReference), queriedReferences)
    }

    @Test
    fun `missing reference cannot establish permission for remote recovery`() {
        assertNull(recoverNeteaseSourceAfterMissingLocal(song(), null) { false })
    }

    private fun song() = SongItem(
        id = 859470L,
        name = "Scanned Song",
        artist = "Artist",
        album = "Album",
        albumId = 17L,
        durationMs = 180_000L,
        coverUrl = null,
        mediaUri = selectedReference,
        localFileName = "song.flac",
        channelId = "netease",
        audioId = "859470",
        sourceStableKey = "859470|netease|"
    )

    private fun expectedRemoteSong() = song().copy(
        album = "Netease",
        albumId = 0L,
        mediaUri = null,
        localFileName = null,
        localFilePath = null,
        sourceStableKey = null,
        streamUrl = null,
        subAudioId = null
    )
}
