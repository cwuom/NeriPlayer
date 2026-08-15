package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationFinalizer
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitIo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

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
    fun `shouldTreatAudioAsManaged keeps romanized lyric sidecar audio in custom directory`() {
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = emptySet(),
                coverEntryNames = emptySet(),
                lyricEntryNames = setOf("Artist - Song_roma.lrc"),
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
    fun `buildLyricCandidateNames recognizes romanized lyric compatibility names`() {
        assertEquals(
            listOf(
                "42_roma.lrc",
                "42_roma.lrc.txt",
                "42_romalrc.lrc",
                "42_romalrc.lrc.txt",
                "42_romanized.lrc",
                "42_romanized.lrc.txt",
                "Artist - Song_roma.lrc",
                "Artist - Song_roma.lrc.txt",
                "Artist - Song_romalrc.lrc",
                "Artist - Song_romalrc.lrc.txt",
                "Artist - Song_romanized.lrc",
                "Artist - Song_romanized.lrc.txt"
            ),
            ManagedDownloadStorage.buildLyricCandidateNames(
                songId = 42L,
                candidateBaseNames = listOf("Artist - Song"),
                kind = ManagedDownloadStorage.LyricKind.ROMANIZED
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
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType(
                "Artist - Song.flac.npmeta.json",
                "application/json"
            )
        )
        assertEquals(
            "application/json",
            ManagedDownloadStorage.documentCreateMimeType("downloads-export.json", "application/json")
        )
    }

    @Test
    fun `documentCreateMimeType keeps exact audio name on SAF providers`() {
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType("Artist - Song.flac", "audio/flac")
        )
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType(
                "Artist - Song.flac.npdl_pending.7",
                "audio/flac"
            )
        )
    }

    @Test
    fun `documentCreateMimeType keeps exact cover name on SAF providers`() {
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType("Artist - Song.jpg", "image/jpeg")
        )
    }

    @Test
    fun `resolveTreeStoredName prefers actual SAF display name`() {
        assertEquals(
            "Artist - Song (1).flac",
            ManagedDownloadStorage.resolveTreeStoredName(
                actualName = "Artist - Song (1).flac",
                expectedName = "Artist - Song.flac"
            )
        )
    }

    @Test
    fun `resolveTreeStoredName falls back when SAF display name is missing`() {
        assertEquals(
            "Artist - Song.flac",
            ManagedDownloadStorage.resolveTreeStoredName(
                actualName = null,
                expectedName = "Artist - Song.flac"
            )
        )
        assertEquals(
            "Artist - Song.flac",
            ManagedDownloadStorage.resolveTreeStoredName(
                actualName = "",
                expectedName = "Artist - Song.flac"
            )
        )
    }

    @Test
    fun `createUniqueName keeps desired name when no conflict exists`() {
        assertEquals(
            "Artist - Song.flac",
            ManagedDownloadStorage.createUniqueName(
                existingNames = setOf("Other.flac"),
                desiredName = "Artist - Song.flac"
            )
        )
    }

    @Test
    fun `createUniqueName increments numbered suffix on conflict`() {
        assertEquals(
            "Artist - Song (2).flac",
            ManagedDownloadStorage.createUniqueName(
                existingNames = setOf(
                    "Artist - Song.flac",
                    "Artist - Song (1).flac"
                ),
                desiredName = "Artist - Song.flac"
            )
        )
    }

    @Test
    fun `createUniqueName supports extensionless names`() {
        assertEquals(
            "Artist - Song (1)",
            ManagedDownloadStorage.createUniqueName(
                existingNames = setOf("Artist - Song"),
                desiredName = "Artist - Song"
            )
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

    @Test
    fun `shouldKeepSourceForSizeMismatch keeps source when copied size is unknown or empty`() {
        // #D3 回归: 目标尺寸为 0 (SAF 对新建文档常返回 length=0) 时必须保留源, 避免误删导致数据丢失
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 0L
            )
        )
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 0L,
                copiedSize = 0L
            )
        )
        // 防御性: 负数 (不可知) 同样保留源
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = -1L
            )
        )
    }

    @Test
    fun `shouldKeepSourceForSizeMismatch keeps source when target is truncated or size mismatches`() {
        // 目标非空但明显小于源 (截断/损坏) 时保留源
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 1L
            )
        )
        // 源尺寸不可知(0) 但目标非空且远超容差, 视为不一致, 保留源
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 0L,
                copiedSize = 100L
            )
        )
    }

    @Test
    fun `shouldKeepSourceForSizeMismatch allows deleting source when copy faithfully matches`() {
        // 源/目标尺寸一致 (容差内) 时确认拷贝可信, 允许删源
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 100L
            )
        )
        // 容差为 1 字节, 相差 1 仍视为一致
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 101L
            )
        )
        // 源本就为空(0), 目标落在容差内(1), 视为一致, 允许删源 (源本就为空才可删)
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 0L,
                copiedSize = 1L
            )
        )
    }

    @Test
    fun `migration verifies content with streaming sha256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ManagedDownloadMigrationFinalizer.sha256Hex(
                ByteArrayInputStream("abc".toByteArray())
            )
        )
    }

    @Test
    fun `migration requires matching remote stable identity before source cleanup`() {
        assertTrue(
            ManagedDownloadMigrationFinalizer.hasCompatibleStableKeys(
                sourceStableKey = "netease:track-a",
                targetStableKey = "netease:track-a",
                sourceReferences = setOf("/old/track-a.flac"),
                targetReferences = setOf("/new/track-a.flac")
            )
        )
        assertFalse(
            ManagedDownloadMigrationFinalizer.hasCompatibleStableKeys(
                sourceStableKey = "netease:track-a",
                targetStableKey = "netease:track-b",
                sourceReferences = setOf("/old/track-a.flac"),
                targetReferences = setOf("/new/track-b.flac")
            )
        )
    }

    @Test
    fun `migration accepts rewritten local stable references only for matching local paths`() {
        assertTrue(
            ManagedDownloadMigrationFinalizer.hasCompatibleStableKeys(
                sourceStableKey = "1|local|/old/track.flac",
                targetStableKey = "1|local|/new/track.flac",
                sourceReferences = setOf("/old/track.flac"),
                targetReferences = setOf("/new/track.flac")
            )
        )
        assertFalse(
            ManagedDownloadMigrationFinalizer.hasCompatibleStableKeys(
                sourceStableKey = "1|local|/old/track.flac",
                targetStableKey = "1|local|different-target",
                sourceReferences = setOf("/old/track.flac"),
                targetReferences = setOf("/new/track.flac")
            )
        )
    }

    @Test
    fun `migration hashes source when its provider does not report a size`() {
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForMigrationSize(
                sourceSize = 0L,
                copiedSize = 100L
            )
        )
    }

    @Test
    fun `migration cannot switch directory while source cleanup remains incomplete`() {
        assertFalse(
            ManagedDownloadStorage.MigrationResult(
                movedFiles = 2,
                skippedFiles = 0,
                cleanupFailedFiles = 1
            ).canSwitchDirectory
        )
    }

    @Test
    fun `atomic migration copy removes partial file after source failure`() {
        val directory = Files.createTempDirectory("neriplayer-migration-test").toFile()
        try {
            val failingInput = object : InputStream() {
                private var readCalls = 0

                override fun read(): Int {
                    throw IOException("single-byte read should not be used")
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (readCalls++ == 0) {
                        buffer[offset] = 1
                        return 1
                    }
                    throw IOException("injected source failure")
                }
            }
            runCatching {
                ManagedDownloadCommitIo.copyFileAtomically(
                    parent = directory,
                    targetName = "song.mp3",
                    input = failingInput,
                    bufferSizeBytes = 8,
                    onProgress = {}
                )
            }
            assertFalse(File(directory, "song.mp3").exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".partial") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
