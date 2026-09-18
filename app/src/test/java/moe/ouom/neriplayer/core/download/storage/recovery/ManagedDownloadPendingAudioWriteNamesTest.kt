package moe.ouom.neriplayer.core.download.storage.recovery

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
}
