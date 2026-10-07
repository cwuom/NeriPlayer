package moe.ouom.neriplayer.data.local.audioimport

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAudioImportSidecarIndexCandidateTest {
    @Test
    fun `lyric, image and metadata json files are indexed regardless of case`() {
        val names = listOf("Night Drive.LRC", "notes.txt", "cover.JPEG", "folder.webp", "song.npmeta.json")

        assertEquals(names, names.filter { LocalAudioImportManager.isLocalSidecarIndexCandidate(it) })
    }

    @Test
    fun `audio files, extensionless names and lookalike extensions are not indexed`() {
        val names = listOf("Night Drive.flac", "README", "json", "lyrics.lrc.bak", "cover.jpg.part")

        assertEquals(emptyList<String>(), names.filter { LocalAudioImportManager.isLocalSidecarIndexCandidate(it) })
    }
}
