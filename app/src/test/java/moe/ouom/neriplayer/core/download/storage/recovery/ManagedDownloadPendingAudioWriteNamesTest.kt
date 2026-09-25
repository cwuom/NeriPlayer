package moe.ouom.neriplayer.core.download.storage.recovery

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadPendingAudioWriteNamesTest {
    private val names = ManagedDownloadPendingAudioWriteNames()

    @Test
    fun `terminal marker wins when original audio name already contains marker`() {
        val original = "artist${PENDING_AUDIO_WRITE_MARKER}title.mp3"
        val pending = "$original$PENDING_AUDIO_WRITE_MARKER.operation.pending"

        assertTrue(names.isPendingAudioWriteName(pending))
        assertEquals(original, names.logicalAudioName(pending))
    }

    @Test
    fun `marker in a normal audio title is not a pending artifact`() {
        val original = "artist${PENDING_AUDIO_WRITE_MARKER}title.mp3"

        assertFalse(names.isPendingAudioWriteName(original))
        assertEquals(original, names.logicalAudioName(original))
    }

    @Test
    fun `stored entry preserves embedded title marker when resolving its final audio name`() {
        val original = "artist.npdl_pendingtitle.mp3"
        val pending = "$original.npdl_pending.123e4567-e89b-12d3-a456-426614174000.pending"
        val entry = ManagedDownloadStorage.StoredEntry(
            name = pending,
            reference = "/downloads/.tmp/$pending",
            mediaUri = "file:///downloads/.tmp/$pending",
            localFilePath = "/downloads/.tmp/$pending",
            sizeBytes = 123L,
            lastModifiedMs = 1L
        )

        assertTrue(entry.isPendingAudioWrite)
        assertEquals(original, entry.logicalName)
        assertEquals("artist.npdl_pendingtitle", entry.nameWithoutExtension)
    }
}
