package moe.ouom.neriplayer.core.download.storage.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadPendingAudioWriteNamesTest {
    private val names = ManagedDownloadPendingAudioWriteNames()

    @Test
    fun `built pending names resolve back to the audio name`() {
        val pendingName = names.buildPendingAudioWriteName("Song.flac")

        assertTrue(pendingName, Regex("""Song\.flac\.npdl_pending\.[0-9a-f-]{36}\.pending""").matches(pendingName))
        assertTrue(names.isPendingAudioWriteName(pendingName))
        assertEquals("Song.flac", names.logicalAudioName(pendingName))
    }

    @Test
    fun `logical names keep titles that only contain the marker`() {
        assertEquals("Song.flac", names.logicalAudioName("Song.flac"))
        assertEquals(".npdl_pending.ab12", names.logicalAudioName(".npdl_pending.ab12"))
        assertEquals("Song.npdl_pending live.flac", names.logicalAudioName("Song.npdl_pending live.flac"))
        assertEquals("Song.mp3", names.logicalAudioName("Song.mp3.npdl_pending.ab12"))
        assertEquals("Song.npdl_pending.ab12", names.logicalAudioName("Song.npdl_pending.ab12"))
    }
}
