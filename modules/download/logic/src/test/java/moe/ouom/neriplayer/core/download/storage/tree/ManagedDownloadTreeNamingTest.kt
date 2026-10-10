package moe.ouom.neriplayer.core.download.storage.tree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadTreeNamingTest {

    @Test
    fun `stored names fall back to the expected name when the provider omits one`() {
        assertEquals("song.flac", ManagedDownloadTreeNaming.resolveTreeStoredName(null, "song.flac"))
        assertEquals("song.flac", ManagedDownloadTreeNaming.resolveTreeStoredName("  ", "song.flac"))
        assertEquals("song (1).flac", ManagedDownloadTreeNaming.resolveTreeStoredName("song (1).flac", "song.flac"))
    }

    @Test
    fun `logical audio names drop only real pending write suffixes`() {
        assertEquals("song.mp3", ManagedDownloadTreeNaming.logicalAudioName("song.mp3"))
        assertEquals(".npdl_pending.flac", ManagedDownloadTreeNaming.logicalAudioName(".npdl_pending.flac"))
        assertEquals("song.mp3", ManagedDownloadTreeNaming.logicalAudioName("song.mp3.npdl_pending"))
        assertEquals("song.npdl_pending live.mp3", ManagedDownloadTreeNaming.logicalAudioName("song.npdl_pending live.mp3"))
    }

    @Test
    fun `only cover and temporary directories get a no media marker`() {
        assertTrue(ManagedDownloadTreeNaming.shouldCreateNoMediaMarker("covers"))
        assertTrue(ManagedDownloadTreeNaming.shouldCreateNoMediaMarker(".TMP"))
        assertFalse(ManagedDownloadTreeNaming.shouldCreateNoMediaMarker("Lyrics"))
    }

    @Test
    fun `managed subdirectories accept provider numbered copies`() {
        assertTrue(ManagedDownloadTreeNaming.matchesManagedSubdirectoryName("COVERS", "Covers"))
        assertTrue(ManagedDownloadTreeNaming.matchesManagedSubdirectoryName("Covers (2)", "Covers"))
        assertFalse(ManagedDownloadTreeNaming.matchesManagedSubdirectoryName("Lyrics", "Covers"))

        assertEquals(0, ManagedDownloadTreeNaming.managedSubdirectoryOrdinal("covers", "Covers"))
        assertEquals(3, ManagedDownloadTreeNaming.managedSubdirectoryOrdinal("Covers (3)", "Covers"))
        listOf("Lyrics", "Covers (", "Covers (2", "Covers (x)").forEach { actualName ->
            assertEquals(actualName, Int.MAX_VALUE, ManagedDownloadTreeNaming.managedSubdirectoryOrdinal(actualName, "Covers"))
        }
    }

    @Test
    fun `provider numbered names need a usable extension split`() {
        assertEquals(2, ManagedDownloadTreeNaming.providerNumberedNameOrdinal("song.flac (2)", "song.flac"))
        assertEquals(5, ManagedDownloadTreeNaming.providerNumberedNameOrdinal("Song (5).FLAC", "song.flac"))
        assertNull(ManagedDownloadTreeNaming.providerNumberedNameOrdinal("song (5).mp3", "song.flac"))
        assertNull(ManagedDownloadTreeNaming.providerNumberedNameOrdinal(" (5).nomedia", ".nomedia"))
        assertNull(ManagedDownloadTreeNaming.providerNumberedNameOrdinal("song (5).", "song."))
    }

    @Test
    fun `metadata ordinals rank final names before pending names`() {
        fun ordinal(actualName: String) = ManagedDownloadTreeNaming.metadataNameOrdinal(actualName, "song.mp3")

        assertEquals(0, ordinal("Song.mp3.npmeta.json"))
        assertEquals(2, ordinal("song.mp3.npmeta (2).json"))
        assertEquals(1, ordinal("song.mp3.npmeta.pending.json"))
        assertEquals(4, ordinal("song.mp3.npmeta.pending (3).json"))
        assertNull(ordinal("other.mp3.npmeta.json"))
    }

    @Test
    fun `pending metadata names match exact and numbered pending sidecars`() {
        assertTrue(ManagedDownloadTreeNaming.isPendingMetadataName("Song.mp3.npmeta.pending.json", "song.mp3"))
        assertTrue(ManagedDownloadTreeNaming.isPendingMetadataName("song.mp3.npmeta.pending (2).json", "song.mp3"))
        assertFalse(ManagedDownloadTreeNaming.isPendingMetadataName("song.mp3.npmeta.json", "song.mp3"))
    }
}
