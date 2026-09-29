package moe.ouom.neriplayer.core.download.storage.metadata.codec

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.model.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadedAudioMetadataDecoderTest {
    @Test
    fun `missing fields retain metadata defaults`() {
        assertEquals(DownloadedAudioMetadata(), decode(JSONObject()))
    }

    @Test
    fun `nonblank fields preserve whitespace literal null and JSON coercion`() {
        val cases = listOf(
            null to null,
            JSONObject.NULL to null,
            "" to null,
            " \t " to null,
            "null" to "null",
            " padded " to " padded ",
            42 to "42",
            true to "true"
        )
        nonblankFields.forEach { field ->
            cases.forEach { (raw, expected) ->
                val metadata = decode(JSONObject().put(field.name, raw))
                assertEquals("${field.name}: $raw", expected, field.read(metadata))
            }
        }
    }

    @Test
    fun `lyric fields distinguish missing and JSON null from explicit cleared text`() {
        val cases = listOf(
            null to null,
            JSONObject.NULL to null,
            "" to "",
            " \t " to " \t ",
            "null" to "null",
            "[00:01]lyric" to "[00:01]lyric",
            42 to "42"
        )
        lyricFields.forEach { field ->
            cases.forEach { (raw, expected) ->
                val metadata = decode(JSONObject().put(field.name, raw))
                assertEquals("${field.name}: $raw", expected, field.read(metadata))
            }
        }
    }

    @Test
    fun `positive numeric fields discard missing null malformed zero and negative values`() {
        val cases = listOf(
            null to null,
            JSONObject.NULL to null,
            "null" to null,
            "invalid" to null,
            0L to null,
            -42L to null,
            42L to 42L,
            "43" to 43L
        )
        positiveFields.forEach { field ->
            cases.forEach { (raw, expected) ->
                val metadata = decode(JSONObject().put(field.name, raw))
                assertEquals("${field.name}: $raw", expected, field.read(metadata))
            }
        }
    }

    @Test
    fun `duration keeps signed values and uses zero for unreadable values`() {
        val cases = listOf(
            null to 0L,
            JSONObject.NULL to 0L,
            "invalid" to 0L,
            0L to 0L,
            -42L to -42L,
            "43" to 43L
        )
        cases.forEach { (raw, expected) ->
            assertEquals(expected, decode(JSONObject().put("durationMs", raw)).durationMs)
        }
    }

    @Test
    fun `flat and nested metadata keep their field specific precedence`() {
        val nested = restorable()
        val root = JSONObject()
            .put("restorableMetadata", nested.toJson())
            .put("stableKey", "flat-key")
            .put("name", "flat-title")
            .put("artist", "flat-artist")
            .put("album", "flat-album")
            .put("customName", "flat-custom-title")
            .put("customArtist", "flat-custom-artist")
            .put("originalName", "flat-original-title")
            .put("originalArtist", "flat-original-artist")
            .put("originalCoverUrl", "flat-original-cover")
            .put("coverPath", "flat-cover")
            .put("matchedLyric", "flat-matched")
            .put("matchedTranslatedLyric", "flat-translated")
            .put("matchedRomanizedLyric", "flat-romanized")
            .put("originalLyric", "flat-original")
            .put("originalTranslatedLyric", "flat-original-translated")
            .put("originalRomanizedLyric", "flat-original-romanized")
            .put("createdAtMs", 123L)

        val metadata = decode(root)

        assertEquals("flat-key", metadata.stableKey)
        assertEquals("flat-title", metadata.name)
        assertEquals("flat-artist", metadata.artist)
        assertEquals("flat-album", metadata.album)
        assertEquals("flat-custom-title", metadata.customName)
        assertEquals("flat-custom-artist", metadata.customArtist)
        assertEquals("flat-original-title", metadata.originalName)
        assertEquals("flat-original-artist", metadata.originalArtist)
        assertEquals("flat-original-cover", metadata.originalCoverUrl)
        assertEquals("flat-cover", metadata.coverPath)
        assertEquals("override-original", metadata.matchedLyric)
        assertEquals("override-translated", metadata.matchedTranslatedLyric)
        assertEquals("override-romanized", metadata.matchedRomanizedLyric)
        assertEquals("flat-original", metadata.originalLyric)
        assertEquals("flat-original-translated", metadata.originalTranslatedLyric)
        assertEquals("flat-original-romanized", metadata.originalRomanizedLyric)
        assertEquals(123L, metadata.createdAtMs)
        assertEquals(nested, metadata.restorableMetadata)
    }

    @Test
    fun `missing or blank flat fields restore nested identity baseline and overrides`() {
        val nested = restorable()
        val cases = listOf(null, JSONObject.NULL, "", " \t ")
        cases.forEach { raw ->
            val root = JSONObject().put("restorableMetadata", nested.toJson())
            listOf(
                "stableKey", "name", "artist", "customName", "customArtist",
                "originalName", "originalArtist", "originalCoverUrl", "coverPath"
            ).forEach { root.put(it, raw) }

            val metadata = decode(root)

            assertEquals("nested-key", metadata.stableKey)
            assertEquals("baseline-title", metadata.name)
            assertEquals("baseline-artist", metadata.artist)
            assertNull(metadata.album)
            assertNull(metadata.coverUrl)
            assertNull(metadata.customCoverUrl)
            assertEquals("override-title", metadata.customName)
            assertEquals("override-artist", metadata.customArtist)
            assertEquals("baseline-title", metadata.originalName)
            assertEquals("baseline-artist", metadata.originalArtist)
            assertEquals("baseline-cover", metadata.originalCoverUrl)
            assertEquals("override-cover", metadata.coverPath)
            assertEquals("baseline-original", metadata.originalLyric)
            assertEquals("baseline-translated", metadata.originalTranslatedLyric)
            assertEquals("baseline-romanized", metadata.originalRomanizedLyric)
            assertEquals(456L, metadata.createdAtMs)
        }
    }

    @Test
    fun `empty baseline lyrics survive while empty override lyrics fall back to flat fields`() {
        val nested = restorable().copy(
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                coverReference = "baseline-cover",
                originalLyric = "",
                translatedLyric = " ",
                romanizedLyric = ""
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(
                originalLyric = "",
                translatedLyric = " ",
                romanizedLyric = ""
            )
        )
        val root = JSONObject()
            .put("restorableMetadata", nested.toJson())
            .put("matchedLyric", "flat-original")
            .put("matchedTranslatedLyric", "")
            .put("matchedRomanizedLyric", "flat-romanized")

        val metadata = decode(root)

        assertEquals("flat-original", metadata.matchedLyric)
        assertEquals("", metadata.matchedTranslatedLyric)
        assertEquals("flat-romanized", metadata.matchedRomanizedLyric)
        assertEquals("", metadata.originalLyric)
        assertEquals(" ", metadata.originalTranslatedLyric)
        assertEquals("", metadata.originalRomanizedLyric)
        assertEquals("baseline-cover", metadata.coverPath)
        lyricFields.filter { it.name.startsWith("original") }.forEach { field ->
            assertEquals("", field.read(decode(JSONObject(root.toString()).put(field.name, ""))))
        }
    }

    @Test
    fun `offset uses nonzero flat value before nested primary or legacy value`() {
        val cases = listOf(
            null to -321L,
            JSONObject.NULL to -321L,
            "invalid" to -321L,
            0L to -321L,
            -120L to -120L,
            "120" to 120L
        )
        listOf("userLyricOffsetMs", "lyricOffsetMs").forEach { nestedField ->
            cases.forEach { (raw, expected) ->
                val root = JSONObject()
                    .put("userLyricOffsetMs", raw)
                    .put(
                        "restorableMetadata",
                        JSONObject().put("overrides", JSONObject().put(nestedField, -321L))
                    )
                assertEquals("$nestedField: $raw", expected, decode(root).userLyricOffsetMs)
            }
        }
    }

    @Test
    fun `invalid flat timestamps restore nested created time without filling other times`() {
        listOf(null, JSONObject.NULL, "invalid", 0L, -1L).forEach { raw ->
            val metadata = decode(
                JSONObject()
                    .put("createdAtMs", raw)
                    .put("restorableMetadata", restorable().toJson())
            )
            assertEquals(456L, metadata.createdAtMs)
            assertNull(metadata.downloadTimeMs)
            assertNull(metadata.libraryAddedAtMs)
            assertNull(metadata.sourceCreatedAtMs)
            assertNull(metadata.sourceModifiedAtMs)
        }
    }

    @Test
    fun `legacy nested stable key is used only when source identity is absent`() {
        val nested = JSONObject().put("sourceStableKey", "legacy-key")
        assertEquals("legacy-key", decode(JSONObject().put("restorableMetadata", nested)).stableKey)
        nested.put("sourceIdentity", JSONObject().put("stableKey", "current-key"))
        assertEquals("current-key", decode(JSONObject().put("restorableMetadata", nested)).stableKey)
        assertNull(decode(JSONObject().put("restorableMetadata", "invalid")).restorableMetadata)
    }

    @Test
    fun `legacy encoding retains original baseline edited values and creation fallback`() {
        val original = DownloadedAudioMetadata(
            stableKey = "legacy-key",
            name = "title",
            artist = "artist",
            album = "album",
            coverUrl = "remote-cover",
            matchedLyric = "current-original",
            matchedTranslatedLyric = "current-translated",
            matchedRomanizedLyric = "current-romanized",
            customName = "custom-title",
            customArtist = "custom-artist",
            customCoverUrl = "custom-cover",
            originalName = "original-title",
            originalArtist = "original-artist",
            originalCoverUrl = "original-cover",
            originalLyric = "original-lyric",
            originalTranslatedLyric = "original-translated",
            originalRomanizedLyric = "original-romanized",
            coverPath = "local-cover",
            downloadTimeMs = 123L
        )
        val encoded = ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(original)
        val decoded = decode(encoded)
        val expectedNested = ManagedDownloadRestorableMetadata(
            sourceStableKey = "legacy-key",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                title = "original-title",
                artist = "original-artist",
                album = "album",
                coverReference = "original-cover",
                originalLyric = "original-lyric",
                translatedLyric = "original-translated",
                romanizedLyric = "original-romanized"
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(
                title = "custom-title",
                artist = "custom-artist",
                coverReference = "local-cover",
                originalLyric = "current-original",
                translatedLyric = "current-translated",
                romanizedLyric = "current-romanized"
            ),
            createdAtMs = 123L,
            updatedAtMs = 123L
        )

        assertEquals(6, encoded.getInt("schemaVersion"))
        assertEquals(original.copy(createdAtMs = 123L, restorableMetadata = expectedNested), decoded)
    }

    @Test
    fun `completion distinguishes absent nullable booleans and coerced values`() {
        val cases = listOf(
            null to null,
            JSONObject.NULL to null,
            "invalid" to false,
            1 to false,
            false to false,
            "FALSE" to false,
            true to true,
            "TRUE" to true
        )
        cases.forEach { (raw, expected) ->
            val root = JSONObject().put("downloadFinalized", raw).put("audioPublicationPending", raw)
            val metadata = decode(root)
            assertEquals("finalized: $raw", expected, metadata.downloadFinalized)
            assertEquals("publication: $raw", expected == true, metadata.audioPublicationPending)
            assertEquals(
                "embedding: $raw",
                if (expected == true) DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED else null,
                metadata.metadataEmbeddingState
            )
        }
    }

    @Test
    fun `declared embedding states normalize independently of text fields`() {
        DownloadedAudioEmbeddingState.entries.forEach { state ->
            listOf(false, true).forEach { finalized ->
                val metadata = decode(
                    JSONObject()
                        .put("downloadFinalized", finalized)
                        .put("metadataEmbeddingState", " ${state.name.lowercase()} ")
                )
                assertEquals(state, metadata.metadataEmbeddingState)
                assertEquals(finalized, metadata.downloadFinalized)
            }
        }
        listOf(null, JSONObject.NULL, "", " ", "null", "future-state").forEach { raw ->
            val root = JSONObject().put("metadataEmbeddingState", raw)
            assertNull(decode(root).metadataEmbeddingState)
            root.put("downloadFinalized", true)
            assertEquals(DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED, decode(root).metadataEmbeddingState)
        }
    }

    @Test
    fun `downgraded legacy completion accepts only known terminal artifact states`() {
        listOf(null, JSONObject.NULL, "", " \t ", "FINALIZED", "finalized", "COMPLETE", "complete")
            .forEach { state ->
                val root = downgradedLegacy().put("artifactState", state)
                val before = root.toString()
                val metadata = decode(root)
                assertEquals("artifactState: $state", true, metadata.downloadFinalized)
                assertEquals(DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED, metadata.metadataEmbeddingState)
                assertEquals(before, root.toString())
            }
    }

    @Test
    fun `downgraded legacy completion requires every identity and lifecycle precondition`() {
        val rejected = listOf(
            "downloadFinalized" to null,
            "downloadFinalized" to JSONObject.NULL,
            "metadataEmbeddingState" to null,
            "metadataEmbeddingState" to "EMBEDDED_VERIFIED",
            "createdAtSource" to null,
            "createdAtSource" to " LEGACY_V15 ",
            "createdAtSource" to "MANAGED_COMMIT",
            "stableKey" to null,
            "stableKey" to " ",
            "audioFileName" to null,
            "audioFileName" to " ",
            "downloadTimeMs" to null,
            "downloadTimeMs" to 0L,
            "downloadTimeMs" to -1L,
            "operationId" to "operation",
            "operationId" to "null",
            "artifactState" to "PENDING",
            "artifactState" to " FINALIZED ",
            "artifactState" to "null"
        )
        rejected.forEach { (field, raw) ->
            val metadata = decode(downgradedLegacy().put(field, raw))
            val expectedFinalized = if (field == "downloadFinalized") null else false
            assertEquals("$field: $raw", expectedFinalized, metadata.downloadFinalized)
        }
        val accepted = decode(
            downgradedLegacy()
                .put("createdAtSource", "legacy_v15")
                .put("operationId", " \t ")
        )
        assertEquals(true, accepted.downloadFinalized)
    }

    private fun decode(root: JSONObject): DownloadedAudioMetadata {
        return ManagedDownloadStorageJsonCodec.downloadedAudioMetadataFromJsonObject(root)
    }

    private fun downgradedLegacy(): JSONObject {
        return JSONObject()
            .put("stableKey", "legacy-key")
            .put("audioFileName", "song.flac")
            .put("downloadTimeMs", 123L)
            .put("downloadFinalized", false)
            .put("metadataEmbeddingState", "LEGACY_UNVERIFIED")
            .put("createdAtSource", "LEGACY_V15")
    }

    private fun restorable(): ManagedDownloadRestorableMetadata {
        return ManagedDownloadRestorableMetadata(
            sourceStableKey = "nested-key",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                title = "baseline-title",
                artist = "baseline-artist",
                album = "baseline-album",
                coverReference = "baseline-cover",
                originalLyric = "baseline-original",
                translatedLyric = "baseline-translated",
                romanizedLyric = "baseline-romanized"
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(
                title = "override-title",
                artist = "override-artist",
                coverReference = "override-cover",
                userLyricOffsetMs = -321L,
                originalLyric = "override-original",
                translatedLyric = "override-translated",
                romanizedLyric = "override-romanized"
            ),
            createdAtMs = 456L,
            updatedAtMs = 789L
        )
    }

    private class MetadataField<T>(
        val name: String,
        val read: (DownloadedAudioMetadata) -> T
    )

    private val nonblankFields: List<MetadataField<String?>> = listOf(
        MetadataField("stableKey") { it.stableKey },
        MetadataField("identityAlbum") { it.identityAlbum },
        MetadataField("album") { it.album },
        MetadataField("name") { it.name },
        MetadataField("artist") { it.artist },
        MetadataField("coverUrl") { it.coverUrl },
        MetadataField("matchedLyricSource") { it.matchedLyricSource },
        MetadataField("matchedSongId") { it.matchedSongId },
        MetadataField("customCoverUrl") { it.customCoverUrl },
        MetadataField("customName") { it.customName },
        MetadataField("customArtist") { it.customArtist },
        MetadataField("originalName") { it.originalName },
        MetadataField("originalArtist") { it.originalArtist },
        MetadataField("originalCoverUrl") { it.originalCoverUrl },
        MetadataField("mediaUri") { it.mediaUri },
        MetadataField("channelId") { it.channelId },
        MetadataField("audioId") { it.audioId },
        MetadataField("subAudioId") { it.subAudioId },
        MetadataField("playlistContextId") { it.playlistContextId },
        MetadataField("coverPath") { it.coverPath },
        MetadataField("lyricPath") { it.lyricPath },
        MetadataField("translatedLyricPath") { it.translatedLyricPath },
        MetadataField("romanizedLyricPath") { it.romanizedLyricPath },
        MetadataField("audioPublicationOwnerId") { it.audioPublicationOwnerId },
        MetadataField("createdAtSource") { it.createdAtSource },
        MetadataField("createdAtConfidence") { it.createdAtConfidence },
        MetadataField("artifactId") { it.artifactId },
        MetadataField("operationId") { it.operationId },
        MetadataField("terminalTemporaryWriteCleanupToken") { it.terminalTemporaryWriteCleanupToken },
        MetadataField("artifactState") { it.artifactState },
        MetadataField("audioFileName") { it.audioFileName },
        MetadataField("libraryId") { it.libraryId }
    )

    private val lyricFields: List<MetadataField<String?>> = listOf(
        MetadataField("matchedLyric") { it.matchedLyric },
        MetadataField("matchedTranslatedLyric") { it.matchedTranslatedLyric },
        MetadataField("matchedRomanizedLyric") { it.matchedRomanizedLyric },
        MetadataField("originalLyric") { it.originalLyric },
        MetadataField("originalTranslatedLyric") { it.originalTranslatedLyric },
        MetadataField("originalRomanizedLyric") { it.originalRomanizedLyric }
    )

    private val positiveFields: List<MetadataField<Long?>> = listOf(
        MetadataField("songId") { it.songId },
        MetadataField("verifiedAudioDurationMs") { it.verifiedAudioDurationMs },
        MetadataField("downloadTimeMs") { it.downloadTimeMs },
        MetadataField("createdAtMs") { it.createdAtMs },
        MetadataField("libraryAddedAtMs") { it.libraryAddedAtMs },
        MetadataField("sourceCreatedAtMs") { it.sourceCreatedAtMs },
        MetadataField("sourceModifiedAtMs") { it.sourceModifiedAtMs }
    )
}
