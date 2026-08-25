package moe.ouom.neriplayer.core.download.metadata

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class DownloadedAudioMetadataStoreTest {

    @Test
    fun `newly written managed cover is inspected without a second materialization`() = runBlocking {
        val reference = "content://managed/Covers/Artist-Song-12345678.jpg"
        val expected = ManagedDownloadCoverAssetStore.MaterializedCover(
            reference = reference,
            assetHash = "a".repeat(64),
            fileName = "Artist-Song-12345678.jpg"
        )
        var inspectCalls = 0
        var materializeCalls = 0

        val resolved = resolveDownloadedMetadataCoverAsset(
            sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences(
                coverReference = reference,
                createdCover = true
            ),
            coverReference = reference,
            inspect = {
                inspectCalls++
                expected
            },
            materialize = {
                materializeCalls++
                error("new managed cover must not be materialized again")
            }
        )

        assertEquals(expected, resolved)
        assertEquals(1, inspectCalls)
        assertEquals(0, materializeCalls)
    }

    @Test
    fun `unowned cover still uses materialization before metadata persistence`() = runBlocking {
        val reference = "file:///external/custom-cover.jpg"
        val expected = ManagedDownloadCoverAssetStore.MaterializedCover(
            reference = "content://managed/Covers/custom-cover-12345678.jpg",
            assetHash = "b".repeat(64),
            fileName = "custom-cover-12345678.jpg"
        )
        var inspectCalls = 0
        var materializeCalls = 0

        val resolved = resolveDownloadedMetadataCoverAsset(
            sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences(
                coverReference = reference,
                createdCover = false
            ),
            coverReference = reference,
            inspect = {
                inspectCalls++
                error("external cover should be materialized")
            },
            materialize = {
                materializeCalls++
                expected
            }
        )

        assertEquals(expected, resolved)
        assertEquals(0, inspectCalls)
        assertEquals(1, materializeCalls)
    }

    @Test
    fun `metadata writes preserve the original download time`() {
        assertEquals(
            123L,
            resolveDownloadedAudioTime(existingTimeMs = 123L, fallbackTimeMs = 999L)
        )
        assertEquals(
            999L,
            resolveDownloadedAudioTime(existingTimeMs = null, fallbackTimeMs = 999L)
        )
        assertNull(resolveDownloadedAudioTime(existingTimeMs = 0L, fallbackTimeMs = 0L))
    }

    @Test
    fun `restorable offset keeps an existing value when incoming metadata omits it`() {
        assertEquals(
            -321L,
            resolveDownloadedUserLyricOffset(existingOffsetMs = -321L, incomingOffsetMs = 0L)
        )
        assertEquals(
            120L,
            resolveDownloadedUserLyricOffset(existingOffsetMs = -321L, incomingOffsetMs = 120L)
        )
        assertEquals(
            -120L,
            resolveDownloadedUserLyricOffset(existingOffsetMs = null, incomingOffsetMs = -120L)
        )
    }

    @Test
    fun `restoring a custom cover clears the stale downloaded sidecar path`() {
        val customCover = "file:///data/user/0/app/files/custom_song_covers/custom.jpg"
        val staleDownloadedCover = "content://downloads/Covers/song-custom.jpg"
        val restoredSong = testSong().copy(
            coverUrl = "https://example.com/original.jpg",
            originalCoverUrl = "https://example.com/original.jpg"
        )

        assertNull(
            resolveDownloadedMetadataCoverReference(
                existingCoverReference = staleDownloadedCover,
                song = restoredSong,
                previousCustomCoverReference = customCover
            )
        )
    }

    @Test
    fun `restoring keeps a locally preserved original cover ahead of stale metadata`() {
        val originalCover = "file:///data/user/0/app/files/original_song_covers/original.jpg"
        val restoredSong = testSong().copy(
            coverUrl = originalCover,
            originalCoverUrl = originalCover
        )

        assertEquals(
            originalCover,
            resolveDownloadedMetadataCoverReference(
                existingCoverReference = "file:///data/user/0/app/files/custom_song_covers/custom.jpg",
                song = restoredSong,
                previousCustomCoverReference = "file:///data/user/0/app/files/custom_song_covers/custom.jpg"
            )
        )
    }

    @Test
    fun `patching cover reference preserves the other metadata fields`() {
        val patched = patchDownloadedMetadataCoverReference(
            rawMetadata = """
                {
                  "stableKey": "song-key",
                  "lyricPath": "lyrics.lrc",
                  "restorableMetadata": {
                    "baseline": {},
                    "overrides": {},
                    "assetRefs": {}
                  }
                }
            """.trimIndent(),
            coverReference = "content://downloads/Covers/song.jpg"
        )

        assertNotNull(patched)
        val payload = JSONObject(patched.orEmpty())
        assertEquals("song-key", payload.getString("stableKey"))
        assertEquals("lyrics.lrc", payload.getString("lyricPath"))
        assertEquals(
            "content://downloads/Covers/song.jpg",
            payload.getString("coverPath")
        )
    }

    @Test
    fun `patch without restorable metadata requests full persistence`() {
        assertNull(
            patchDownloadedMetadataCoverReference(
                rawMetadata = "{\"stableKey\":\"song-key\"}",
                coverReference = "content://downloads/Covers/song.jpg"
            )
        )
    }

    @Test
    fun `patching invalid metadata returns no payload`() {
        assertNull(
            patchDownloadedMetadataCoverReference(
                rawMetadata = "not-json",
                coverReference = "content://downloads/Covers/song.jpg"
            )
        )
    }

    @Test
    fun `patching cover reference records the managed short file identity`() {
        val hash = "a".repeat(64)
        val fileName = "Artist-Song-12345678.jpg"
        val patched = patchDownloadedMetadataCoverReference(
            rawMetadata = """
                {
                  "restorableMetadata": {
                    "baseline": {},
                    "overrides": {},
                    "assetRefs": {}
                  }
                }
            """.trimIndent(),
            coverReference = "content://downloads/Covers/$fileName",
            coverAssetHash = hash,
            coverAssetFileName = fileName
        )

        val assets = JSONObject(patched.orEmpty())
            .getJSONObject("restorableMetadata")
            .getJSONObject("assetRefs")
        assertEquals(hash, assets.getString("currentCoverHash"))
        assertEquals(fileName, assets.getString("currentCoverFileName"))
        assertEquals(hash, assets.getString("baselineCoverHash"))
        assertEquals(fileName, assets.getString("baselineCoverFileName"))
    }

    @Test
    fun `patching matching baseline hash backfills only its short file name`() {
        val hash = "a".repeat(64)
        val fileName = "Artist-Song-12345678.jpg"
        val patched = patchDownloadedMetadataCoverReference(
            rawMetadata = """
                {
                  "restorableMetadata": {
                    "baseline": {},
                    "overrides": {},
                    "assetRefs": {"baselineCoverHash": "$hash"}
                  }
                }
            """.trimIndent(),
            coverReference = "content://downloads/Covers/$fileName",
            coverAssetHash = hash,
            coverAssetFileName = fileName
        )

        val assets = JSONObject(patched.orEmpty())
            .getJSONObject("restorableMetadata")
            .getJSONObject("assetRefs")
        assertEquals(fileName, assets.getString("baselineCoverFileName"))
    }

    @Test
    fun `patching different baseline hash does not bind current file name to it`() {
        val originalHash = "a".repeat(64)
        val currentHash = "b".repeat(64)
        val patched = patchDownloadedMetadataCoverReference(
            rawMetadata = """
                {
                  "restorableMetadata": {
                    "baseline": {},
                    "overrides": {},
                    "assetRefs": {"baselineCoverHash": "$originalHash"}
                  }
                }
            """.trimIndent(),
            coverReference = "content://downloads/Covers/Artist-Song-12345678.jpg",
            coverAssetHash = currentHash,
            coverAssetFileName = "Artist-Song-12345678.jpg"
        )

        val assets = JSONObject(patched.orEmpty())
            .getJSONObject("restorableMetadata")
            .getJSONObject("assetRefs")
        assertEquals(originalHash, assets.getString("baselineCoverHash"))
        assertTrue(!assets.has("baselineCoverFileName"))
    }

    @Test
    fun `restoring metadata keeps lyric content when the incoming song has no lyrics`() {
        val restored = preserveMissingDownloadedMetadataLyrics(
            song = testSong(),
            metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                matchedLyric = "[00:01.00]stored lyric",
                matchedTranslatedLyric = "stored translation",
                matchedRomanizedLyric = "stored romanization",
                originalLyric = "stored original"
            )
        )

        assertEquals("[00:01.00]stored lyric", restored.matchedLyric)
        assertEquals("stored translation", restored.matchedTranslatedLyric)
        assertEquals("stored romanization", restored.matchedRomanizedLyric)
        assertEquals("stored original", restored.originalLyric)
    }

    @Test
    fun `metadata persistence keeps existing edits when incoming song omits them`() {
        val previous = ManagedDownloadRestorableMetadata.Overrides(
            title = "Edited title",
            artist = "Edited artist",
            coverReference = "content://managed/Covers/edited.jpg",
            userLyricOffsetMs = -321L,
            originalLyric = "edited lyric",
            translatedLyric = "edited translation",
            romanizedLyric = "edited romanization"
        )

        val merged = mergeRestorableOverrides(
            previous = previous,
            song = testSong()
        )

        assertEquals(previous, merged)
    }

    @Test
    fun `metadata persistence applies explicit non-null edits over the baseline`() {
        val merged = mergeRestorableOverrides(
            previous = ManagedDownloadRestorableMetadata.Overrides(),
            song = testSong().copy(
                customName = "Edited title",
                customArtist = "Edited artist",
                customCoverUrl = "content://managed/Covers/edited.jpg",
                matchedLyric = "edited lyric",
                matchedTranslatedLyric = "edited translation",
                matchedRomanizedLyric = "edited romanization",
                userLyricOffsetMs = -321L
            )
        )

        assertEquals("Edited title", merged.title)
        assertEquals("Edited artist", merged.artist)
        assertEquals("content://managed/Covers/edited.jpg", merged.coverReference)
        assertEquals(-321L, merged.userLyricOffsetMs)
        assertEquals("edited lyric", merged.originalLyric)
        assertEquals("edited translation", merged.translatedLyric)
        assertEquals("edited romanization", merged.romanizedLyric)
    }

    @Test
    fun `restoring the baseline clears title artist cover and lyric overrides`() {
        val merged = mergeRestorableOverrides(
            previous = ManagedDownloadRestorableMetadata.Overrides(
                title = "Edited title",
                artist = "Edited artist",
                coverReference = "content://managed/Covers/edited.jpg",
                originalLyric = "edited lyric",
                translatedLyric = "edited translation",
                romanizedLyric = "edited romanization"
            ),
            song = testSong().copy(
                matchedLyric = "original lyric",
                matchedTranslatedLyric = "original translation",
                matchedRomanizedLyric = "original romanization"
            ),
            clearRestorableOverrides = RestorableMetadataClearPolicy(
                title = true,
                artist = true,
                cover = true,
                lyrics = true
            )
        )

        assertNull(merged.title)
        assertNull(merged.artist)
        assertNull(merged.coverReference)
        assertNull(merged.originalLyric)
        assertNull(merged.translatedLyric)
        assertNull(merged.romanizedLyric)
    }

    @Test
    fun `restorable baseline is backfilled from downloaded sidecar lyrics`() {
        val baseline = mergeRestorableBaseline(
            existing = ManagedDownloadRestorableMetadata.Baseline(
                title = "Original title",
                artist = "Original artist"
            ),
            song = testSong(),
            coverReference = "content://managed/Covers/base.jpg",
            sidecarOriginalLyric = "[00:00.00]sidecar lyric",
            sidecarTranslatedLyric = "[00:00.00]sidecar translation",
            sidecarRomanizedLyric = "[00:00.00]sidecar romanization"
        )

        assertEquals("[00:00.00]sidecar lyric", baseline.originalLyric)
        assertEquals("[00:00.00]sidecar translation", baseline.translatedLyric)
        assertEquals("[00:00.00]sidecar romanization", baseline.romanizedLyric)
    }

    @Test
    fun `restorable baseline preserves remote source separately from managed cover`() {
        val baseline = mergeRestorableBaseline(
            existing = ManagedDownloadRestorableMetadata.Baseline(
                coverReference = "https://example.com/original.jpg"
            ),
            song = testSong().copy(
                coverUrl = "https://example.com/original.jpg",
                originalCoverUrl = "https://example.com/original.jpg"
            ),
            coverReference = "content://managed/Covers/Artist-Song-12345678.jpg"
        )

        assertEquals(
            "https://example.com/original.jpg",
            baseline.coverReference
        )
    }

    @Test
    fun `restorable baseline uses current source URL when original URL is absent`() {
        val baseline = mergeRestorableBaseline(
            existing = null,
            song = testSong().copy(
                coverUrl = "https://example.com/source.jpg",
                originalCoverUrl = null,
                customCoverUrl = null
            ),
            coverReference = "content://managed/Covers/Artist-Song-12345678.jpg"
        )

        assertEquals("https://example.com/source.jpg", baseline.coverReference)
    }

    @Test
    fun `restorable baseline keeps source cover when a custom cover is active`() {
        val baseline = mergeRestorableBaseline(
            existing = null,
            song = testSong().copy(
                coverUrl = "https://example.com/source.jpg",
                originalCoverUrl = null,
                customCoverUrl = "content://managed/Covers/custom.jpg"
            ),
            coverReference = "content://managed/Covers/custom.jpg"
        )

        assertEquals("https://example.com/source.jpg", baseline.coverReference)
    }

    @Test
    fun `custom cover replaces only current asset identity`() {
        val shortName = "Artist-Song-12345678.jpg"
        val originalHash = "a".repeat(64)
        val customHash = "b".repeat(64)
        val existing = ManagedDownloadRestorableMetadata(
            sourceStableKey = "1|netease|",
            baseline = ManagedDownloadRestorableMetadata.Baseline(),
            overrides = ManagedDownloadRestorableMetadata.Overrides(),
            baselineCoverAssetHash = originalHash,
            currentCoverAssetHash = originalHash,
            baselineCoverAssetFileName = shortName,
            currentCoverAssetFileName = shortName
        )

        val merged = mergeRestorableCoverAssetRefs(
            existing = existing,
            hasCustomCover = true,
            coverAssetHash = customHash,
            coverAssetFileName = shortName,
            clearCoverOverride = false
        )

        assertEquals(originalHash, merged.baselineHash)
        assertEquals(shortName, merged.baselineFileName)
        assertEquals(customHash, merged.currentHash)
        assertEquals(shortName, merged.currentFileName)
    }

    @Test
    fun `restoring cover reuses baseline asset identity when no new file is available`() {
        val shortName = "Artist-Song-12345678.jpg"
        val originalHash = "a".repeat(64)
        val existing = ManagedDownloadRestorableMetadata(
            sourceStableKey = "1|netease|",
            baseline = ManagedDownloadRestorableMetadata.Baseline(),
            overrides = ManagedDownloadRestorableMetadata.Overrides(),
            baselineCoverAssetHash = originalHash,
            currentCoverAssetHash = "b".repeat(64),
            baselineCoverAssetFileName = shortName,
            currentCoverAssetFileName = shortName
        )

        val merged = mergeRestorableCoverAssetRefs(
            existing = existing,
            hasCustomCover = false,
            coverAssetHash = null,
            coverAssetFileName = null,
            clearCoverOverride = true
        )

        assertEquals(originalHash, merged.currentHash)
        assertEquals(shortName, merged.currentFileName)
    }

    @Test
    fun `restorable baseline never replaces an existing lyric with sidecar content`() {
        val baseline = mergeRestorableBaseline(
            existing = ManagedDownloadRestorableMetadata.Baseline(
                originalLyric = "[00:00.00]authoritative lyric"
            ),
            song = testSong(),
            coverReference = null,
            sidecarOriginalLyric = "[00:00.00]stale sidecar lyric"
        )

        assertEquals("[00:00.00]authoritative lyric", baseline.originalLyric)
    }

    private fun testSong(): SongItem {
        return SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Netease",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null
        )
    }
}
