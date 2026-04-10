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
            put("coverUrl", "old://cover")
            put("originalCoverUrl", "old://cover")
            put("lyricPath", "old://lyric")
            put("translatedLyricPath", "old://translated")
            put("mediaUri", "old://audio")
            put("stableKey", "42|__local_files__|old://audio")
        }.toString()

        val rewritten = ManagedDownloadStorage.rewriteManagedMetadataReferences(
            rawJson = raw,
            referenceMap = mapOf(
                "old://cover" to "new://cover",
                "old://lyric" to "new://lyric",
                "old://translated" to "new://translated",
                "old://audio" to "new://audio"
            )
        )
        val payload = JSONObject(rewritten)

        assertEquals("new://cover", payload.getString("coverPath"))
        assertEquals("new://cover", payload.getString("coverUrl"))
        assertEquals("new://cover", payload.getString("originalCoverUrl"))
        assertEquals("new://lyric", payload.getString("lyricPath"))
        assertEquals("new://translated", payload.getString("translatedLyricPath"))
        assertEquals("new://audio", payload.getString("mediaUri"))
        assertEquals("42|__local_files__|new://audio", payload.getString("stableKey"))
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
    fun `shouldTreatAudioAsManaged keeps buggy lrc txt sidecar audio in custom directory`() {
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = emptySet(),
                coverEntryNames = emptySet(),
                lyricEntryNames = setOf("Artist - Song.lrc.txt"),
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

    @Test
    fun `buildLyricCandidateNames keeps lrc txt compatibility after buggy migration`() {
        assertEquals(
            listOf(
                "42.lrc",
                "42.lrc.txt",
                "Artist - Song.lrc",
                "Artist - Song.lrc.txt"
            ),
            ManagedDownloadStorage.buildLyricCandidateNames(
                songId = 42L,
                candidateBaseNames = listOf("Artist - Song"),
                translated = false
            )
        )
    }

    @Test
    fun `matchesManagedSubdirectoryName keeps numbered sidecar directories compatible`() {
        assertTrue(ManagedDownloadStorage.matchesManagedSubdirectoryName("Covers", "Covers"))
        assertTrue(ManagedDownloadStorage.matchesManagedSubdirectoryName("Covers (1)", "Covers"))
        assertTrue(ManagedDownloadStorage.matchesManagedSubdirectoryName("Lyrics (12)", "Lyrics"))
        assertFalse(ManagedDownloadStorage.matchesManagedSubdirectoryName("Covers copy", "Covers"))
        assertFalse(ManagedDownloadStorage.matchesManagedSubdirectoryName("Covers(1)", "Covers"))
        assertFalse(ManagedDownloadStorage.matchesManagedSubdirectoryName("Lyrics (x)", "Lyrics"))
    }

    @Test
    fun `documentCreateMimeType preserves explicit lyric extensions`() {
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType("Artist - Song.lrc", "text/plain")
        )
        assertEquals(
            "text/plain",
            ManagedDownloadStorage.documentCreateMimeType("Artist - Song.txt", "text/plain")
        )
    }

    @Test
    fun `parseDownloadedAudioMetadataJson keeps embedded lyrics for local fallback`() {
        val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(
            JSONObject().apply {
                put("matchedLyric", "[00:00.00]原文")
                put("matchedTranslatedLyric", "[00:00.00]翻译")
                put("originalLyric", "[00:00.00]原始原文")
                put("originalTranslatedLyric", "[00:00.00]原始翻译")
                put("lyricPath", "/music/Lyrics/Artist - Song.lrc")
            }.toString()
        )

        assertEquals("[00:00.00]原文", metadata?.matchedLyric)
        assertEquals("[00:00.00]翻译", metadata?.matchedTranslatedLyric)
        assertEquals("[00:00.00]原始原文", metadata?.originalLyric)
        assertEquals("[00:00.00]原始翻译", metadata?.originalTranslatedLyric)
        assertEquals("/music/Lyrics/Artist - Song.lrc", metadata?.lyricPath)
    }

    @Test
    fun `parseDownloadedAudioMetadataJson keeps explicit cleared lyrics as blank string`() {
        val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(
            JSONObject().apply {
                put("matchedLyric", "")
                put("matchedTranslatedLyric", "")
                put("originalLyric", "")
                put("originalTranslatedLyric", "")
            }.toString()
        )

        assertEquals("", metadata?.matchedLyric)
        assertEquals("", metadata?.matchedTranslatedLyric)
        assertEquals("", metadata?.originalLyric)
        assertEquals("", metadata?.originalTranslatedLyric)
    }
}
