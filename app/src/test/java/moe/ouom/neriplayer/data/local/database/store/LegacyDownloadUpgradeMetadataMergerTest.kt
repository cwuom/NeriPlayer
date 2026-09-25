package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.core.download.model.DownloadedAudioEmbeddingState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDownloadUpgradeMetadataMergerTest {
    @Test
    fun payloadBuildsRestorableMetadataForManagedSidecar() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "name": "Song",
                  "artist": "Artist",
                  "album": "Album",
                  "mediaUri": "file:///music/song.flac",
                  "downloadTime": 1234,
                  "originalLyric": "base lyric",
                  "matchedLyric": "edited lyric"
                }
                """.trimIndent()
            ),
            existing = null,
            audioFileName = "song.flac"
        )

        assertEquals("file:song.flac", merged.getString("stableKey"))
        assertEquals("song.flac", merged.getString("audioFileName"))
        assertEquals(1234L, merged.getLong("downloadTimeMs"))
        assertTrue(merged.getBoolean("downloadFinalized"))
        assertEquals(
            DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED.name,
            merged.getString("metadataEmbeddingState")
        )
        val restorable = merged.getJSONObject("restorableMetadata")
        assertEquals("file:song.flac", restorable.getJSONObject("sourceIdentity").getString("stableKey"))
        assertEquals("base lyric", restorable.getJSONObject("baseline").getString("originalLyric"))
        assertEquals("edited lyric", restorable.getJSONObject("overrides").getString("originalLyric"))
    }

    @Test
    fun existingMetadataWinsForUserOverridesButPayloadFillsMissingFields() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "name": "Remote title",
                  "artist": "Remote artist",
                  "downloadTime": 1234,
                  "customName": "Remote override"
                }
                """.trimIndent()
            ),
            existing = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "name": "Local title",
                  "customName": "Local override",
                  "matchedLyric": "local lyric"
                }
                """.trimIndent()
            ),
            audioFileName = "song.flac"
        )

        assertEquals("Local title", merged.getString("name"))
        assertEquals("Local override", merged.getString("customName"))
        assertEquals("local lyric", merged.getString("matchedLyric"))
        assertEquals("Remote artist", merged.getString("artist"))
        assertEquals(1234L, merged.getLong("downloadTimeMs"))
    }

    @Test
    fun nullPayloadValueDoesNotEraseExistingMetadata() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject().apply {
                put("stableKey", "file:song.flac")
                put("customName", JSONObject.NULL)
            },
            existing = JSONObject().apply {
                put("stableKey", "file:song.flac")
                put("customName", "Local override")
            },
            audioFileName = "song.flac"
        )

        assertEquals("Local override", merged.getString("customName"))
        assertTrue(merged.has("restorableMetadata"))
        assertFalse(merged.isNull("stableKey"))
    }

    @Test
    fun legacyCoverSourcesRemainInRestorableMetadata() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "coverPath": "content://legacy/Covers/old.jpg",
                  "originalCoverUrl": "https://example.test/original.jpg",
                  "customCoverUrl": "content://legacy/Covers/custom.jpg"
                }
                """.trimIndent()
            ),
            existing = null,
            audioFileName = "song.flac"
        )

        val restorable = merged.getJSONObject("restorableMetadata")
        assertEquals(
            "https://example.test/original.jpg",
            restorable.getJSONObject("baseline").getString("coverReference")
        )
        assertEquals(
            "content://legacy/Covers/custom.jpg",
            restorable.getJSONObject("overrides").getString("coverReference")
        )
        assertTrue(restorable.has("assetRefs"))
    }

    @Test
    fun customCoverIsNotPromotedToBaselineWhenOriginalIsAbsent() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "coverUrl": "https://example.test/original.jpg",
                  "customCoverUrl": "content://legacy/Covers/custom.jpg"
                }
                """.trimIndent()
            ),
            existing = null,
            audioFileName = "song.flac"
        )

        val restorable = merged.getJSONObject("restorableMetadata")
        assertEquals(
            "https://example.test/original.jpg",
            restorable.getJSONObject("baseline").getString("coverReference")
        )
        assertEquals(
            "content://legacy/Covers/custom.jpg",
            restorable.getJSONObject("overrides").getString("coverReference")
        )
    }

    @Test
    fun explicitEmbeddingCompletionStateRemainsTrusted() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "metadataEmbeddingState": "USER_DISABLED"
                }
                """.trimIndent()
            ),
            existing = null,
            audioFileName = "song.flac"
        )

        assertTrue(merged.getBoolean("downloadFinalized"))
        assertEquals(
            DownloadedAudioEmbeddingState.USER_DISABLED.name,
            merged.getString("metadataEmbeddingState")
        )
    }

    @Test
    fun trustedEmbeddingCompletionStatesAreFinalizedWithoutSeparateEvidence() {
        listOf(
            DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
            DownloadedAudioEmbeddingState.USER_DISABLED,
            DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED
        ).forEach { state ->
            listOf(false, true).forEach { explicitNull ->
                val payload = JSONObject()
                    .put("stableKey", "file:song.flac")
                    .put("metadataEmbeddingState", state.name)
                if (explicitNull) payload.put("downloadFinalized", JSONObject.NULL)

                val merged = LegacyDownloadUpgradeMetadataMerger.merge(
                    payload = payload,
                    existing = null,
                    audioFileName = "song.flac"
                )

                assertTrue("state=$state explicitNull=$explicitNull", merged.getBoolean("downloadFinalized"))
                assertEquals(state.name, merged.getString("metadataEmbeddingState"))
            }
        }
    }

    @Test
    fun existingExplicitFalseCompletionEvidenceOverridesTrustedEmbeddingState() {
        listOf(
            DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
            DownloadedAudioEmbeddingState.USER_DISABLED,
            DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED
        ).forEach { state ->
            listOf(false, 0L).forEach { explicitFalse ->
                val merged = LegacyDownloadUpgradeMetadataMerger.merge(
                    payload = JSONObject()
                        .put("stableKey", "file:song.flac")
                        .put("metadataEmbeddingState", state.name)
                        .put("downloadFinalized", true),
                    existing = JSONObject().put("downloadFinalized", explicitFalse),
                    audioFileName = "song.flac"
                )

                assertFalse("state=$state value=$explicitFalse", merged.getBoolean("downloadFinalized"))
                assertEquals(state.name, merged.getString("metadataEmbeddingState"))
            }
        }
    }

    @Test
    fun completionEvidenceRespectsRootNestedExistingAndNullPrecedence() {
        val trustedState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED.name

        val nestedFalse = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject()
                .put("downloadFinalized", true)
                .put(
                    "metadata",
                    JSONObject()
                        .put("metadataEmbeddingState", trustedState)
                        .put("downloadFinalized", false)
                ),
            existing = null,
            audioFileName = "song.flac"
        )
        assertFalse(nestedFalse.getBoolean("downloadFinalized"))

        val nestedNull = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject()
                .put("downloadFinalized", false)
                .put(
                    "metadata",
                    JSONObject()
                        .put("metadataEmbeddingState", trustedState)
                        .put("downloadFinalized", JSONObject.NULL)
                ),
            existing = null,
            audioFileName = "song.flac"
        )
        assertFalse(nestedNull.getBoolean("downloadFinalized"))

        val existingNull = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject()
                .put(
                    "metadata",
                    JSONObject()
                        .put("metadataEmbeddingState", trustedState)
                        .put("downloadFinalized", false)
                ),
            existing = JSONObject().put("downloadFinalized", JSONObject.NULL),
            audioFileName = "song.flac"
        )
        assertFalse(existingNull.getBoolean("downloadFinalized"))

        val invalidExisting = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject().put("metadataEmbeddingState", trustedState),
            existing = JSONObject().put("downloadFinalized", "false"),
            audioFileName = "song.flac"
        )
        assertFalse(invalidExisting.getBoolean("downloadFinalized"))
    }

    @Test
    fun unfinalizedEmbeddingStatesStayUnfinishedWithoutCompletionEvidence() {
        listOf(
            DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER,
            DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED
        ).forEach { state ->
            listOf(false, true).forEach { explicitNull ->
                val payload = JSONObject()
                    .put("stableKey", "file:song.flac")
                    .put("metadataEmbeddingState", state.name)
                if (explicitNull) payload.put("downloadFinalized", JSONObject.NULL)

                val merged = LegacyDownloadUpgradeMetadataMerger.merge(
                    payload = payload,
                    existing = null,
                    audioFileName = "song.flac"
                )

                assertFalse("state=$state explicitNull=$explicitNull", merged.getBoolean("downloadFinalized"))
                assertEquals(state.name, merged.getString("metadataEmbeddingState"))
            }
        }
    }

    @Test
    fun legacyFinalizedFlagWithoutEmbeddingFieldRemainsVisibleAfterUpgrade() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "downloadFinalized": true
                }
                """.trimIndent()
            ),
            existing = null,
            audioFileName = "song.flac"
        )

        assertTrue(merged.getBoolean("downloadFinalized"))
        assertEquals(
            DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED.name,
            merged.getString("metadataEmbeddingState")
        )
    }

    @Test
    fun explicitlyUnfinishedLegacyMetadataRemainsUnfinished() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "downloadFinalized": false
                }
                """.trimIndent()
            ),
            existing = null,
            audioFileName = "song.flac"
        )

        assertFalse(merged.getBoolean("downloadFinalized"))
        assertEquals(
            DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED.name,
            merged.getString("metadataEmbeddingState")
        )
    }

    @Test
    fun catalogAliasesPreserveIdentityAndSqlIntegerCompletionEvidence() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject()
                .put("stableKey", "7123|__local_files__|/music/song.flac")
                .put("id", 7123L)
                .put("sourceIdentityAlbum", "__local_files__")
                .put("sourceChannelId", "99")
                .put("sourceAudioId", "BV1")
                .put("sourceSubAudioId", "123")
                .put("sourcePlaylistContextId", "ctx")
                .put("downloadFinalized", 1L),
            existing = null,
            audioFileName = "song.flac"
        )

        assertEquals(7123L, merged.getLong("songId"))
        assertEquals("__local_files__", merged.getString("identityAlbum"))
        assertEquals("99", merged.getString("channelId"))
        assertEquals("BV1", merged.getString("audioId"))
        assertEquals("123", merged.getString("subAudioId"))
        assertEquals("ctx", merged.getString("playlistContextId"))
        assertTrue(merged.getBoolean("downloadFinalized"))
        assertEquals(
            DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED.name,
            merged.getString("metadataEmbeddingState")
        )
    }

    @Test
    fun nestedMetadataAndExistingSidecarOverrideCatalogFallbackByField() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject()
                .put("stableKey", "7123|__local_files__|/music/song.flac")
                .put(
                    "downloaded_song_catalog",
                    JSONObject()
                        .put("id", 7123L)
                        .put("source_identity_album", "__local_files__")
                        .put("source_channel_id", "99")
                        .put("source_audio_id", "BV-catalog")
                        .put("source_sub_audio_id", "123")
                        .put("source_playlist_context_id", "ctx")
                )
                .put(
                    "metadata",
                    JSONObject()
                        .put("audioId", "BV-metadata")
                        .put("subAudioId", JSONObject.NULL)
                ),
            existing = JSONObject()
                .put("audioId", "BV-sidecar")
                .put("channelId", JSONObject.NULL),
            audioFileName = "song.flac"
        )

        assertEquals(7123L, merged.getLong("songId"))
        assertEquals("__local_files__", merged.getString("identityAlbum"))
        assertEquals("99", merged.getString("channelId"))
        assertEquals("BV-sidecar", merged.getString("audioId"))
        assertEquals("123", merged.getString("subAudioId"))
        assertEquals("ctx", merged.getString("playlistContextId"))
    }

    @Test
    fun onlyBooleanOrSqlZeroOneAreAcceptedAsCompletionEvidence() {
        listOf(false, 0L, JSONObject.NULL, 2L, -1L, 0.0, 1.0, "1", "true").forEach { value ->
            val merged = LegacyDownloadUpgradeMetadataMerger.merge(
                payload = JSONObject()
                    .put("stableKey", "file:song.flac")
                    .put("downloadFinalized", value),
                existing = null,
                audioFileName = "song.flac"
            )

            assertFalse("value=$value", merged.getBoolean("downloadFinalized"))
            assertEquals(
                "value=$value",
                DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED.name,
                merged.getString("metadataEmbeddingState")
            )
        }
        listOf(true, 1L).forEach { value ->
            val merged = LegacyDownloadUpgradeMetadataMerger.merge(
                payload = JSONObject()
                    .put("stableKey", "file:song.flac")
                    .put("downloadFinalized", value),
                existing = null,
                audioFileName = "song.flac"
            )

            assertTrue("value=$value", merged.getBoolean("downloadFinalized"))
        }
    }
}
