package moe.ouom.neriplayer.core.download.storage.naming

import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming.LyricKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageNamingTest {

    @Test
    fun `lyric candidates list the song id first and drop duplicate base names`() {
        assertEquals(
            listOf("42.lrc", "42.lrc.txt", "Song.lrc", "Song.lrc.txt"),
            ManagedDownloadStorageNaming.buildLyricCandidateNames(42L, listOf("Song", "Song"), translated = false)
        )
        assertEquals(
            listOf("Song_trans.lrc", "Song_trans.lrc.txt"),
            ManagedDownloadStorageNaming.buildLyricCandidateNames(0L, listOf("Song"), translated = true)
        )
        assertEquals(
            listOf(
                "Song_roma.lrc",
                "Song_roma.lrc.txt",
                "Song_romalrc.lrc",
                "Song_romalrc.lrc.txt",
                "Song_romanized.lrc",
                "Song_romanized.lrc.txt"
            ),
            ManagedDownloadStorageNaming.buildLyricCandidateNames(null, listOf("Song"), LyricKind.ROMANIZED)
        )
    }

    @Test
    fun `reserved names are numbered before the extension`() {
        val reserved = mutableSetOf("song.flac", "song (1).flac", "cover")

        assertEquals("Live.flac", ManagedDownloadStorageNaming.reserveUniqueName(reserved, "Live.flac"))
        assertEquals("SONG (2).flac", ManagedDownloadStorageNaming.reserveUniqueName(reserved, "SONG.flac"))
        assertEquals("Cover (1)", ManagedDownloadStorageNaming.reserveUniqueName(reserved, "Cover"))
        assertTrue(reserved.containsAll(listOf("live.flac", "song (2).flac", "cover (1)")))
    }

    @Test
    fun `reservation gives up after ten thousand numbered candidates`() {
        val reserved = (1 until 10_000).mapTo(mutableSetOf("x")) { "x ($it)" }

        assertEquals("x", ManagedDownloadStorageNaming.reserveUniqueName(reserved, "x"))
        assertEquals(10_000, reserved.size)
    }

    @Test
    fun `pending audio writes reserve their logical audio name`() {
        assertEquals(
            "Song (1).flac",
            ManagedDownloadStorageNaming.createUniqueAudioName(listOf("Song.flac.npdl_pending.ab12.pending"), "Song.flac")
        )
        assertEquals(
            "Song.flac",
            ManagedDownloadStorageNaming.createUniqueAudioName(listOf(".npdl_pending.ab12.pending"), "Song.flac")
        )
        assertEquals(
            " ",
            ManagedDownloadStorageNaming.createUniqueAudioName(listOf(" .npdl_pending.ab12.pending"), " ")
        )
    }
}
