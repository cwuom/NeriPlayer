package moe.ouom.neriplayer.core.download.manager.batch

import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalDownloadManagerBatchEntryRulesTest {
    private val manager = GlobalDownloadManager

    @Test
    fun `initial downloaded audio must be a formal local file with content`() {
        assertTrue(manager.isUsableInitialDownloadedAudio(entry("Song.mp3")))
        assertTrue(manager.isUsableInitialDownloadedAudio(entry("Song.mp3", size = 0L, sizeKnown = false)))
        assertFalse(manager.isUsableInitialDownloadedAudio(entry("Song.mp3", size = 0L)))
        assertFalse(manager.isUsableInitialDownloadedAudio(entry(PENDING_NAME)))
        assertFalse(manager.isUsableInitialDownloadedAudio(entry("Song.mp3", reference = "https://cdn/Song.mp3")))
    }

    @Test
    fun `finalization audio may be pending but must be a local non directory file`() {
        assertTrue(manager.isUsableFinalizationAudioEntry(entry(PENDING_NAME)))
        assertTrue(manager.isUsableFinalizationAudioEntry(entry("Song.mp3", size = 0L, sizeKnown = false)))
        assertFalse(manager.isUsableFinalizationAudioEntry(entry("Song.mp3", size = 0L)))
        assertFalse(manager.isUsableFinalizationAudioEntry(entry("Song.mp3").copy(isDirectory = true)))
        assertFalse(manager.isUsableFinalizationAudioEntry(entry("Song.mp3", reference = "https://cdn/Song.mp3")))
    }

    @Test
    fun `finalization names accept exact and provider numbered copies only`() {
        assertTrue(manager.matchesFinalizationStoredName("Song.mp3", "Song.mp3"))
        assertTrue(manager.matchesFinalizationStoredName("Song (1).mp3", "Song.mp3"))
        assertFalse(manager.matchesFinalizationStoredName("Other.mp3", "Song.mp3"))

        assertTrue(manager.matchesFinalizationCandidateName(entry("Song (2).mp3"), listOf("Other", "Song")))
        assertFalse(manager.matchesFinalizationCandidateName(entry("Song (2).mp3"), listOf("Other")))
    }

    private fun entry(
        name: String,
        size: Long = 5L,
        sizeKnown: Boolean = true,
        reference: String = "/music/$name"
    ): StoredEntry {
        return StoredEntry(name, reference, reference, null, size, 1L, sizeKnown = sizeKnown)
    }

    private companion object {
        const val PENDING_NAME = "Song.mp3.npdl_pending.op-1.pending"
    }
}
