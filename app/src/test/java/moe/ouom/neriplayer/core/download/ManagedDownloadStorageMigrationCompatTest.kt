package moe.ouom.neriplayer.core.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageMigrationCompatTest {

    @Test
    fun `rewriteManagedMetadataReferences remaps migrated sidecar references`() {
        val raw = JSONObject().apply {
            put("coverPath", "old://cover")
            put("lyricPath", "old://lyric")
            put("translatedLyricPath", "old://translated")
            put("mediaUri", "https://example.com/source")
        }.toString()

        val rewritten = ManagedDownloadStorage.rewriteManagedMetadataReferences(
            rawJson = raw,
            referenceMap = mapOf(
                "old://cover" to "new://cover",
                "old://lyric" to "new://lyric",
                "old://translated" to "new://translated"
            )
        )
        val payload = JSONObject(rewritten)

        assertEquals("new://cover", payload.getString("coverPath"))
        assertEquals("new://lyric", payload.getString("lyricPath"))
        assertEquals("new://translated", payload.getString("translatedLyricPath"))
        assertEquals("https://example.com/source", payload.getString("mediaUri"))
    }

    @Test
    fun `shouldTreatAudioAsManaged keeps metadata backed audio in custom directory`() {
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = setOf("Artist - Song.mp3"),
                coverEntryNames = emptySet(),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `shouldTreatAudioAsManaged keeps legacy sidecar backed audio in custom directory`() {
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = emptySet(),
                coverEntryNames = setOf("Artist - Song.jpg"),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `shouldTreatAudioAsManaged skips foreign audio in custom directory`() {
        assertFalse(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = emptySet(),
                coverEntryNames = emptySet(),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = false
            )
        )
    }
}
