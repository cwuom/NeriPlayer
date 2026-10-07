package moe.ouom.neriplayer.data.local.audioimport

import moe.ouom.neriplayer.data.local.media.NearbyLyricReferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAudioImportLyricSidecarLookupOrderTest {
    @Test
    fun `root lyric folders win over nested and direct sidecars with the same name`() {
        val references = LocalAudioImportManager.resolveKnownSidecarReferences(
            directIndex = mapOf("night drive.lrc" to "direct-lrc", "night drive_trans.lrc" to "direct-trans"),
            nestedIndex = mapOf("night drive.lrc" to "nested-lrc", "night drive_trans.lrc" to "nested-trans"),
            rootLyricsIndex = mapOf("night drive.lrc" to "root-lrc"),
            displayName = "Night Drive.flac",
            baseName = "Night Drive",
            metadataIndex = mapOf("night drive.flac" to "indexed-metadata")
        )

        assertEquals(
            NearbyLyricReferences(original = "root-lrc", translated = "nested-trans", romanized = null),
            references.lyrics
        )
        assertEquals("indexed-metadata", references.metadata)
    }

    @Test
    fun `earlier sidecar names win before closer folders`() {
        val references = LocalAudioImportManager.resolveKnownSidecarReferences(
            directIndex = mapOf(
                "night drive.lrc" to "direct-lrc",
                "night drive_romanized.lrc.txt" to "direct-romanized"
            ),
            nestedIndex = mapOf("night drive.txt" to "nested-txt"),
            displayName = "Night Drive.flac",
            baseName = "Night Drive",
            metadataIndex = emptyMap()
        )

        assertEquals(
            NearbyLyricReferences(original = "direct-lrc", translated = null, romanized = "direct-romanized"),
            references.lyrics
        )
        assertNull(references.metadata)
    }
}
