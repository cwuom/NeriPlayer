package moe.ouom.neriplayer.core.startup.legacy

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.metadata.MAX_SOURCE_COVER_BYTES
import moe.ouom.neriplayer.core.download.storage.metadata.isCoverPixelBudgetWithin
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata

@RunWith(AndroidJUnit4::class)
class LegacyDownloadUpgradeCoordinatorTest {
    @Test
    fun staleRootPayloadDoesNotOverwriteDifferentKnownSidecarIdentity() = runTest {
        assertConflictingSidecarIdentityIsPreserved(topLevelStableKey = "2|netease|")
    }

    @Test
    fun staleRootPayloadDoesNotOverwriteConflictingRestorableSourceIdentity() = runTest {
        assertConflictingSidecarIdentityIsPreserved(topLevelStableKey = "1|netease|")
    }

    private suspend fun assertConflictingSidecarIdentityIsPreserved(topLevelStableKey: String) {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            seedMetadataUpgrade(database, fixture.managedRoot, itemCount = 1)
            val sqliteDatabase = database.openHelper.writableDatabase
            val payload = sqliteDatabase.query(
                "SELECT payload_json FROM legacy_download_upgrade_payload"
            ).use { cursor ->
                check(cursor.moveToFirst())
                JSONObject(cursor.getString(0))
                    .put("rootKey", "content://old.provider/tree/old-root")
                    .put("mediaUri", "content://old.provider/document/missing-audio")
                    .toString()
            }
            sqliteDatabase.execSQL(
                "UPDATE legacy_download_upgrade_payload SET payload_json = ?",
                arrayOf(payload)
            )
            val metadataFile = File(fixture.managedRoot, audioName(0) + METADATA_SUFFIX)
            val existingMetadata = JSONObject()
                .put("stableKey", topLevelStableKey)
                .put("audioFileName", audioName(0))
                .put("name", "Current song B")
                .put("artist", "Current artist B")
                .put("customName", "User title B")
                .put("downloadFinalized", true)
                .put(
                    "restorableMetadata",
                    JSONObject().put(
                        "sourceIdentity",
                        JSONObject().put("stableKey", "2|netease|")
                    )
                )
                .toString()
            metadataFile.writeText(existingMetadata)

            val result = LegacyDownloadUpgradeCoordinator(fixture.context, database).execute()

            assertEquals(existingMetadata, metadataFile.readText())
            assertEquals(0, result.rowsCompleted)
            assertEquals(1, result.rowsQuarantined)
            assertTrue(
                sqliteDatabase.query(
                    "SELECT payload_json FROM legacy_download_upgrade_quarantine " +
                        "WHERE stable_key = '1|netease|'"
                ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == payload }
            )
            val snapshot = ManagedDownloadStorage.buildLegacyUpgradeSnapshot(fixture.context)
            assertEquals(
                0,
                LegacyDownloadUpgradeCoordinator(fixture.context, database)
                    .requeueResolvableQuarantinedRows(snapshot)
            )
            assertEquals(existingMetadata, metadataFile.readText())
        } finally {
            database.close()
            fixture.close()
        }
    }

    @Test
    fun matchingKnownSidecarIdentityKeepsCustomMetadataDuringUpgrade() = runTest {
        assertMatchingSidecarMetadataSurvivesUpgrade(differentRoot = false)
    }

    @Test
    fun knownDifferentRootStillMergesMatchingSidecarIdentity() = runTest {
        assertMatchingSidecarMetadataSurvivesUpgrade(differentRoot = true)
    }

    private suspend fun assertMatchingSidecarMetadataSurvivesUpgrade(differentRoot: Boolean) {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            seedMetadataUpgrade(database, fixture.managedRoot, itemCount = 1)
            if (differentRoot) {
                setLegacyPayloadStorageHints(
                    database = database,
                    rootKey = "file:${File(fixture.sandbox, "old-root").absolutePath}",
                    mediaUri = File(fixture.sandbox, "old-root/${audioName(0)}").toURI().toString()
                )
            }
            val metadataFile = File(fixture.managedRoot, audioName(0) + METADATA_SUFFIX)
            metadataFile.writeText(
                JSONObject()
                    .put("stableKey", "1|netease|")
                    .put("audioFileName", audioName(0))
                    .put("name", "Current title")
                    .put("artist", "Current artist")
                    .put("customName", "User title")
                    .put("matchedRomanizedLyric", "User romanized lyric")
                    .put("downloadFinalized", true)
                    .put(
                        "restorableMetadata",
                        JSONObject().put(
                            "sourceIdentity",
                            JSONObject().put("stableKey", "1|netease|")
                        )
                    )
                    .toString()
            )

            val result = LegacyDownloadUpgradeCoordinator(fixture.context, database).execute()

            assertTrue(result.isComplete)
            assertEquals(1, result.rowsCompleted)
            val restored = JSONObject(metadataFile.readText())
            assertEquals("1|netease|", restored.getString("stableKey"))
            assertEquals("User title", restored.getString("customName"))
            assertEquals("User romanized lyric", restored.getString("matchedRomanizedLyric"))
            assertEquals(
                "1|netease|",
                restored.getJSONObject("restorableMetadata")
                    .getJSONObject("sourceIdentity").getString("stableKey")
            )
        } finally {
            database.close()
            fixture.close()
        }
    }

    @Test
    fun knownDifferentRootDoesNotBootstrapSameNameWithoutIdentity() = runTest {
        assertMetadataLessAudioRootBoundary(differentRoot = true)
    }

    @Test
    fun sameRootPayloadBootstrapsMetadataLessAudio() = runTest {
        assertMetadataLessAudioRootBoundary(differentRoot = false)
    }

    @Test
    fun knownDifferentRootStillBootstrapsMatchingFileReference() = runTest {
        assertMetadataLessAudioRootBoundary(differentRoot = true, currentAudioReference = true)
    }

    @Test
    fun audioReferenceAliasesKeepOpaqueDocumentIdentity() {
        val uri = "content://opaque.provider/tree/current-root/document/opaque%2Fnode%2BA"
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "same-name.mp3",
            reference = uri,
            mediaUri = uri,
            localFilePath = null,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
        val lookup = LegacyManagedRootLookup(listOf(audio), emptyMap())
        fun resolve(reference: String) = lookup.resolveAudioByReference(
            JSONObject().put("mediaUri", reference).put("audioFileName", audio.name)
        )

        assertEquals(audio, resolve("content://opaque.provider/document/opaque%2fnode+A"))
        assertEquals(null, resolve("content://opaque.provider/document/opaque%2Fnode%20A"))
        assertEquals(null, resolve("content://other.provider/document/opaque%2Fnode%2BA"))
        assertEquals(null, resolve("content://opaque.provider/document/opaque%2Fnode%2BA-child"))
    }

    private suspend fun assertMetadataLessAudioRootBoundary(
        differentRoot: Boolean,
        currentAudioReference: Boolean = false
    ) {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            seedMetadataUpgrade(database, fixture.managedRoot, itemCount = 1)
            val rootKey = if (differentRoot) {
                "file:${File(fixture.sandbox, "old-root").absolutePath}"
            } else {
                ManagedDownloadStorage.currentSnapshotCacheKey(fixture.context)
            }
            val payload = setLegacyPayloadStorageHints(
                database = database,
                rootKey = rootKey,
                mediaUri = when {
                    currentAudioReference -> File(fixture.managedRoot, audioName(0)).toURI().toString()
                    differentRoot -> File(fixture.sandbox, "old-root/${audioName(0)}").toURI().toString()
                    else -> null
                }
            )
            val metadataFile = File(fixture.managedRoot, audioName(0) + METADATA_SUFFIX)

            val result = LegacyDownloadUpgradeCoordinator(fixture.context, database).execute()

            if (differentRoot && !currentAudioReference) {
                assertFalse("another root's unknown file must not acquire the old identity", metadataFile.exists())
                assertEquals(0, result.rowsCompleted)
                assertTrue(result.isComplete)
                assertEquals(0, result.rowsPending)
                assertEquals(1, result.rowsQuarantined)
                assertEquals(
                    payload,
                    database.openHelper.writableDatabase.query(
                        "SELECT payload_json FROM legacy_download_upgrade_quarantine " +
                            "WHERE stable_key = '1|netease|' AND reason = 'STORAGE_UNAVAILABLE'"
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getString(0)
                    }
                )
            } else {
                assertTrue(result.isComplete)
                assertEquals(1, result.rowsCompleted)
                assertEquals("1|netease|", JSONObject(metadataFile.readText()).getString("stableKey"))
            }
        } finally {
            database.close()
            fixture.close()
        }
    }

    private fun setLegacyPayloadStorageHints(
        database: NeriUserDataDatabase,
        rootKey: String,
        mediaUri: String?
    ): String {
        val sqliteDatabase = database.openHelper.writableDatabase
        val payload = sqliteDatabase.query(
            "SELECT payload_json FROM legacy_download_upgrade_payload"
        ).use { cursor ->
            check(cursor.moveToFirst())
            JSONObject(cursor.getString(0)).apply {
                put("rootKey", rootKey)
                put("mediaUri", mediaUri)
                getJSONObject("downloaded_song_catalog").put("root_key", rootKey)
            }.toString()
        }
        sqliteDatabase.execSQL(
            "UPDATE legacy_download_upgrade_payload SET payload_json = ?",
            arrayOf(payload)
        )
        return payload
    }

    @Test
    fun emptyExternalCoverDoesNotBlockSongMetadataUpgrade() = runTest {
        assertEmptyCoverDoesNotBlockUpgrade(managedCover = false)
    }

    @Test
    fun emptyManagedCoverDoesNotBlockSongMetadataUpgrade() = runTest {
        assertEmptyCoverDoesNotBlockUpgrade(managedCover = true)
    }

    @Test
    fun oversizedLegacyCoverPreservesOriginalBytesWithoutBlockingSongUpgrade() = runTest {
        assertOversizedCoverDoesNotBlockUpgrade()
    }

    @Test
    fun oversizedExternalCoverWithKnownHashAndMissingFileNameUpgradesLosslessly() = runTest {
        assertOversizedCoverDoesNotBlockUpgrade(knownCoverHash = true)
    }

    private suspend fun assertOversizedCoverDoesNotBlockUpgrade(knownCoverHash: Boolean = false) {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            seedMetadataUpgrade(database, fixture.managedRoot, itemCount = 1)
            val sourceDirectory = File(fixture.sandbox, "old-covers").apply { mkdirs() }
            val sourceCover = File(sourceDirectory, "oversized-4500.png")
            writeLegacyCoverPng(sourceCover)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceCover.absolutePath, bounds)
            assertEquals(4_500, bounds.outWidth)
            assertEquals(4_500, bounds.outHeight)
            assertEquals("image/png", bounds.outMimeType)
            assertFalse(isCoverPixelBudgetWithin(bounds.outWidth, bounds.outHeight))
            assertTrue(sourceCover.length() in 1L..MAX_SOURCE_COVER_BYTES)
            val sourceBytes = sourceCover.readBytes()
            val expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(sourceBytes)
                .joinToString("") { byte -> "%02x".format(byte) }
            setLegacyCoverPayload(database, sourceCover.absolutePath)
            val audio = File(fixture.managedRoot, audioName(0))
            val originalAudio = audio.readBytes()
            val metadataFile = File(fixture.managedRoot, audioName(0) + METADATA_SUFFIX)
            if (knownCoverHash) {
                val assetRefs = JSONObject()
                    .put("baselineCoverHash", expectedHash)
                    .put("currentCoverHash", expectedHash)
                assertFalse(assetRefs.has("baselineCoverFileName"))
                assertFalse(assetRefs.has("currentCoverFileName"))
                metadataFile.writeText(
                    JSONObject()
                        .put("stableKey", stableKey(0))
                        .put("audioFileName", audioName(0))
                        .put("restorableMetadata", JSONObject()
                            .put("sourceIdentity", JSONObject().put("stableKey", stableKey(0)))
                            .put("baseline", JSONObject().put("coverReference", sourceCover.absolutePath))
                            .put("assetRefs", assetRefs)
                        )
                        .toString()
                )
            }
            val coordinator = LegacyDownloadUpgradeCoordinator(fixture.context, database)

            val result = coordinator.execute()

            assertTrue("result=$result", result.isComplete)
            assertEquals(1, result.rowsCompleted)
            assertEquals(0, result.rowsPending)
            assertEquals(0, result.rowsQuarantined)
            assertFalse(payloadTableExists(database))
            assertArrayEquals(originalAudio, audio.readBytes())
            assertArrayEquals(sourceBytes, sourceCover.readBytes())
            val metadataJson = metadataFile.readText()
            val restored = JSONObject(metadataJson)
            assertPreservedCoverFixtureMetadata(restored)
            val assets = restored.getJSONObject("restorableMetadata").getJSONObject("assetRefs")
            assertEquals(expectedHash, assets.getString("baselineCoverHash"))
            assertEquals(expectedHash, assets.getString("currentCoverHash"))
            val currentFileName = assets.getString("currentCoverFileName")
            assertEquals(currentFileName, assets.getString("baselineCoverFileName"))
            val managedCover = File(File(fixture.managedRoot, "Covers"), currentFileName)
            assertTrue(managedCover.isFile)
            assertEquals(managedCover.absolutePath, restored.getString("coverPath"))
            assertArrayEquals(sourceBytes, managedCover.readBytes())

            val repeated = coordinator.execute()

            assertTrue("result=$repeated", repeated.isComplete)
            assertEquals(0, repeated.rowsPending)
            assertEquals(0, repeated.rowsSeen)
            assertEquals(metadataJson, metadataFile.readText())
            assertArrayEquals(sourceBytes, sourceCover.readBytes())
            assertArrayEquals(sourceBytes, managedCover.readBytes())
            assertArrayEquals(originalAudio, audio.readBytes())
        } finally {
            database.close()
            fixture.close()
        }
    }

    private suspend fun assertEmptyCoverDoesNotBlockUpgrade(managedCover: Boolean) {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            seedMetadataUpgrade(database, fixture.managedRoot, itemCount = 1)
            val coverDirectory = File(
                if (managedCover) fixture.managedRoot else fixture.sandbox,
                if (managedCover) "Covers" else "old-covers"
            ).apply { mkdirs() }
            val sourceCover = File(coverDirectory, "empty.jpg").apply {
                writeBytes(byteArrayOf())
            }
            setLegacyCoverPayload(database, sourceCover.absolutePath)
            val audio = File(fixture.managedRoot, audioName(0))
            val originalAudio = audio.readBytes()
            val metadataFile = File(fixture.managedRoot, audioName(0) + METADATA_SUFFIX)
            val coordinator = LegacyDownloadUpgradeCoordinator(fixture.context, database)

            val result = coordinator.execute()

            assertTrue("result=$result", result.isComplete)
            assertEquals(1, result.rowsCompleted)
            assertEquals(0, result.rowsPending)
            assertEquals(0, result.rowsQuarantined)
            assertFalse(payloadTableExists(database))
            assertArrayEquals(originalAudio, audio.readBytes())
            assertTrue("the original empty cover must remain recoverable", sourceCover.isFile)
            assertEquals(0L, sourceCover.length())
            val metadataJson = metadataFile.readText()
            val restored = JSONObject(metadataJson)
            assertPreservedCoverFixtureMetadata(restored)
            assertTrue(restored.optString("coverPath").isBlank())
            val recoveryReferences = restored.getJSONObject("restorableMetadata")
                .getJSONObject("assetRefs")
                .getJSONArray("legacyCoverRecoveryReferences")
            assertTrue(
                "the empty cover reference must remain recoverable",
                (0 until recoveryReferences.length()).any { index ->
                    recoveryReferences.getString(index) == sourceCover.absolutePath
                }
            )

            val repeated = coordinator.execute()

            assertTrue(repeated.isComplete)
            assertEquals(0, repeated.rowsSeen)
            assertEquals(metadataJson, metadataFile.readText())
            assertTrue(sourceCover.isFile)
            assertArrayEquals(originalAudio, audio.readBytes())
        } finally {
            database.close()
            fixture.close()
        }
    }

    @Test
    fun matchingExternalCoverHashWithoutFileNamesMaterializesRecoverableAssets() = runTest {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            seedMetadataUpgrade(database, fixture.managedRoot, itemCount = 1)
            val sourceDirectory = File(fixture.sandbox, "old-covers").apply { mkdirs() }
            val sourceBytes = "original cover bytes".toByteArray()
            val sourceCover = File(sourceDirectory, "original.jpg").apply {
                writeBytes(sourceBytes)
            }
            val expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(sourceBytes)
                .joinToString("") { byte -> "%02x".format(byte) }
            setLegacyCoverPayload(database, sourceCover.absolutePath)
            val metadataFile = File(fixture.managedRoot, audioName(0) + METADATA_SUFFIX)
            metadataFile.writeText(
                JSONObject()
                    .put("stableKey", stableKey(0))
                    .put("audioFileName", audioName(0))
                    .put("restorableMetadata", JSONObject()
                        .put("sourceIdentity", JSONObject().put("stableKey", stableKey(0)))
                        .put("baseline", JSONObject().put("coverReference", sourceCover.absolutePath))
                        .put("assetRefs", JSONObject()
                            .put("baselineCoverHash", expectedHash)
                            .put("currentCoverHash", expectedHash)
                        )
                    )
                    .toString()
            )
            val audio = File(fixture.managedRoot, audioName(0))
            val originalAudio = audio.readBytes()
            val coordinator = LegacyDownloadUpgradeCoordinator(fixture.context, database)

            val result = coordinator.execute()

            assertTrue("result=$result", result.isComplete)
            assertEquals(1, result.rowsCompleted)
            assertEquals(0, result.rowsPending)
            assertEquals(0, result.rowsQuarantined)
            assertFalse(payloadTableExists(database))
            assertArrayEquals(originalAudio, audio.readBytes())
            assertArrayEquals(sourceBytes, sourceCover.readBytes())
            val metadataJson = metadataFile.readText()
            val restored = JSONObject(metadataJson)
            assertPreservedCoverFixtureMetadata(restored)
            val assets = restored.getJSONObject("restorableMetadata").getJSONObject("assetRefs")
            assertEquals(expectedHash, assets.getString("baselineCoverHash"))
            assertEquals(expectedHash, assets.getString("currentCoverHash"))
            val baselineFileName = assets.getString("baselineCoverFileName")
            val currentFileName = assets.getString("currentCoverFileName")
            assertTrue(baselineFileName.isNotBlank())
            assertEquals(baselineFileName, currentFileName)
            val managedCover = File(File(fixture.managedRoot, "Covers"), currentFileName)
            assertTrue(managedCover.isFile)
            assertEquals(managedCover.absolutePath, restored.getString("coverPath"))
            assertArrayEquals(sourceBytes, managedCover.readBytes())

            val repeated = coordinator.execute()

            assertTrue(repeated.isComplete)
            assertEquals(0, repeated.rowsSeen)
            assertEquals(metadataJson, metadataFile.readText())
            assertArrayEquals(sourceBytes, sourceCover.readBytes())
            assertArrayEquals(originalAudio, audio.readBytes())
        } finally {
            database.close()
            fixture.close()
        }
    }

    @Test
    fun mismatchedExternalCoverHashKeepsPayloadAndExistingSidecarUnchanged() = runTest {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            seedMetadataUpgrade(database, fixture.managedRoot, itemCount = 1)
            val sourceDirectory = File(fixture.sandbox, "old-covers").apply { mkdirs() }
            val sourceBytes = "different cover bytes".toByteArray()
            val sourceCover = File(sourceDirectory, "mismatched.jpg").apply {
                writeBytes(sourceBytes)
            }
            val expectedHash = MessageDigest.getInstance("SHA-256")
                .digest("expected cover bytes".toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
            setLegacyCoverPayload(database, sourceCover.absolutePath)
            val metadataFile = File(fixture.managedRoot, audioName(0) + METADATA_SUFFIX)
            val originalMetadata = JSONObject()
                .put("stableKey", stableKey(0))
                .put("audioFileName", audioName(0))
                .put("customName", "Existing user title")
                .put("matchedLyric", "[00:00.00]Existing user lyrics")
                .put("restorableMetadata", JSONObject()
                    .put("sourceIdentity", JSONObject().put("stableKey", stableKey(0)))
                    .put("baseline", JSONObject().put("coverReference", sourceCover.absolutePath))
                    .put("assetRefs", JSONObject()
                        .put("baselineCoverHash", expectedHash)
                        .put("currentCoverHash", expectedHash)
                    )
                )
                .toString()
            metadataFile.writeText(originalMetadata)
            val originalPayload = database.openHelper.writableDatabase.query(
                "SELECT payload_json FROM legacy_download_upgrade_payload"
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            val audio = File(fixture.managedRoot, audioName(0))
            val originalAudio = audio.readBytes()

            val result = LegacyDownloadUpgradeCoordinator(fixture.context, database).execute()

            assertFalse("result=$result", result.isSettled)
            assertEquals(0, result.rowsCompleted)
            assertEquals(1, result.rowsPending)
            assertEquals(0, result.rowsQuarantined)
            assertEquals(LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE, result.rowResults.single().status)
            assertEquals(originalMetadata, metadataFile.readText())
            assertEquals(
                originalPayload,
                database.openHelper.writableDatabase.query(
                    "SELECT payload_json FROM legacy_download_upgrade_payload"
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                }
            )
            assertArrayEquals(sourceBytes, sourceCover.readBytes())
            assertArrayEquals(originalAudio, audio.readBytes())
        } finally {
            database.close()
            fixture.close()
        }
    }

    @Test
    fun unreadableEmptyExternalCoverKeepsOriginalPayloadRetryable() = runTest {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val sourceDirectory = File(fixture.sandbox, "old-covers").apply { mkdirs() }
        val sourceCover = File(sourceDirectory, "protected-empty.jpg").apply {
            writeBytes(byteArrayOf())
        }
        try {
            seedMetadataUpgrade(database, fixture.managedRoot, itemCount = 1)
            setLegacyCoverPayload(database, sourceCover.absolutePath)
            val originalPayload = database.openHelper.writableDatabase.query(
                "SELECT payload_json FROM legacy_download_upgrade_payload"
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            val audio = File(fixture.managedRoot, audioName(0))
            val originalAudio = audio.readBytes()
            val metadataFile = File(fixture.managedRoot, audioName(0) + METADATA_SUFFIX)
            Os.chmod(sourceCover.absolutePath, 0)
            assertEquals(0, Os.stat(sourceCover.absolutePath).st_mode and OsConstants.S_IRWXU)

            val result = LegacyDownloadUpgradeCoordinator(fixture.context, database).execute()

            assertFalse("result=$result", result.isSettled)
            assertEquals(0, result.rowsCompleted)
            assertEquals(1, result.rowsPending)
            assertEquals(0, result.rowsQuarantined)
            assertEquals(LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE, result.rowResults.single().status)
            assertFalse("a failed cover read must not publish partial metadata", metadataFile.exists())
            assertEquals(
                originalPayload,
                database.openHelper.writableDatabase.query(
                    "SELECT payload_json FROM legacy_download_upgrade_payload"
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                }
            )
            assertTrue(sourceCover.isFile)
            assertEquals(0L, sourceCover.length())
            assertArrayEquals(originalAudio, audio.readBytes())
        } finally {
            if (sourceCover.exists()) {
                Os.chmod(sourceCover.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            }
            database.close()
            fixture.close()
        }
    }

    private fun setLegacyCoverPayload(database: NeriUserDataDatabase, coverReference: String) {
        val sqliteDatabase = database.openHelper.writableDatabase
        val payload = sqliteDatabase.query(
            "SELECT payload_json FROM legacy_download_upgrade_payload"
        ).use { cursor ->
            check(cursor.moveToFirst())
            JSONObject(cursor.getString(0)).apply {
                put("coverPath", coverReference)
                put("customName", "Preserved user title")
                put("matchedLyric", "[00:00.00]Preserved user lyrics")
                put("userLyricOffsetMs", 350L)
                getJSONObject("downloaded_song_catalog").put("cover_path", coverReference)
            }.toString()
        }
        sqliteDatabase.execSQL(
            "UPDATE legacy_download_upgrade_payload SET payload_json = ?",
            arrayOf(payload)
        )
    }

    private fun assertPreservedCoverFixtureMetadata(metadata: JSONObject) {
        assertEquals(stableKey(0), metadata.getString("stableKey"))
        assertEquals("Preserved user title", metadata.getString("customName"))
        assertEquals("[00:00.00]Preserved user lyrics", metadata.getString("matchedLyric"))
        assertEquals(350L, metadata.getLong("userLyricOffsetMs"))
        assertTrue(metadata.getBoolean("downloadFinalized"))
    }

    @Test
    fun cancelledMarkerWithoutPendingOperationDoesNotCreateSyntheticJournalRow() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO legacy_download_upgrade_payload (stable_key, payload_json)
                VALUES (
                  '7|netease|',
                  '{"stableKey":"7|netease|","download_cancelled_key":' ||
                  '{"stable_key":"7|netease|","cancelled_at_ms":10}}'
                )
                """.trimIndent()
            )

            val result = LegacyDownloadUpgradeCoordinator(context, database).execute()

            assertTrue(database.downloadOperationDao().findAll().isEmpty())
            assertTrue(result.isComplete)
        } finally {
            database.close()
        }
    }

    @Test
    fun unresolvedLegacyConflictMovesToQuarantineForExplicitResolution() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO legacy_download_upgrade_payload (stable_key, payload_json)
                VALUES ('8|netease|', '{"stableKey":"8|netease|",' ||
                  '"legacyConflicts":[{"reason":"SAME_STABLE_KEY_DIFFERENT_BYTES"}]}')
                """.trimIndent()
            )

            val result = LegacyDownloadUpgradeCoordinator(context, database).execute()

            assertTrue(database.downloadOperationDao().findAll().isEmpty())
            assertTrue(result.isComplete)
            assertEquals(0, result.rowsPending)
            assertEquals(1, result.rowsQuarantined)
            assertFalse(
                database.openHelper.writableDatabase.query(
                    "SELECT 1 FROM sqlite_master " +
                        "WHERE type = 'table' " +
                        "AND name = 'legacy_download_upgrade_payload'"
                ).use { it.moveToFirst() }
            )
            assertTrue(
                database.openHelper.writableDatabase.query(
                    "SELECT 1 FROM legacy_download_upgrade_quarantine " +
                        "WHERE stable_key = '8|netease|' " +
                        "AND reason = 'CONFLICT' " +
                        "AND payload_json LIKE '%SAME_STABLE_KEY_DIFFERENT_BYTES%'"
                ).use { it.moveToFirst() }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun unresolvedFallbackIdentityMovesOutOfTheHotUpgradeQueueWithoutDataLoss() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO legacy_download_upgrade_payload (stable_key, payload_json)
                VALUES (
                  'legacy:download_snapshot_metadata:root_key=root|audio_name=orphan.flac',
                  '{"stableKey":"legacy:download_snapshot_metadata:root_key=root|audio_name=orphan.flac",' ||
                  '"download_snapshot_metadata":{"audio_name":"orphan.flac"}}'
                )
                """.trimIndent()
            )

            val result = LegacyDownloadUpgradeCoordinator(context, database).execute()

            assertTrue(result.isComplete)
            assertEquals(0, result.rowsPending)
            assertEquals(1, result.rowsQuarantined)
            assertTrue(
                database.openHelper.writableDatabase.query(
                    "SELECT 1 FROM legacy_download_upgrade_quarantine " +
                        "WHERE stable_key LIKE 'legacy:download_snapshot_metadata:%' " +
                        "AND payload_json LIKE '%orphan.flac%'"
                ).use { it.moveToFirst() }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingLegacyOperationUsesTheInjectedDatabase() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO legacy_download_upgrade_payload (stable_key, payload_json)
                VALUES (
                  '11|netease|',
                  '{"stableKey":"11|netease|","download_pending_queue":' ||
                  '{"stable_key":"11|netease|","name":"Pending","artist":"Artist",' ||
                  '"queue_order":3}}'
                )
                """.trimIndent()
            )

            val result = LegacyDownloadUpgradeCoordinator(context, database).execute()

            assertTrue(result.isComplete)
            assertEquals(1, database.downloadOperationDao().findAll().size)
            assertEquals(
                "11|netease|",
                database.downloadOperationDao().findAll().single().stableKey
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun unresolvedProjectionQuarantineHandlesTwoThousandRowsWithinTenSeconds() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val sqliteDatabase = database.openHelper.writableDatabase
            sqliteDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            sqliteDatabase.beginTransaction()
            try {
                repeat(2_200) { index ->
                    val stableKey = "legacy:download_snapshot_entry:row=$index"
                    sqliteDatabase.execSQL(
                        "INSERT INTO legacy_download_upgrade_payload " +
                            "(stable_key, payload_json) VALUES (?, ?)",
                        arrayOf(
                            stableKey,
                            "{\"stableKey\":\"$stableKey\"," +
                                "\"download_snapshot_entries\":[]}"
                        )
                    )
                }
                sqliteDatabase.setTransactionSuccessful()
            } finally {
                sqliteDatabase.endTransaction()
            }

            val startedAtNanos = System.nanoTime()
            val result = LegacyDownloadUpgradeCoordinator(context, database).execute()
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L

            assertTrue(result.isComplete)
            assertEquals(2_200, result.rowsQuarantined)
            assertTrue("elapsedMs=$elapsedMs", elapsedMs < 10_000L)
        } finally {
            database.close()
        }
    }

    @Test
    fun publishedSnapshotRequeuesOnlyUniquelyIdentifiedQuarantinedSong() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val sqliteDatabase = database.openHelper.writableDatabase
            sqliteDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_quarantine (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    stable_key TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    quarantined_at_ms INTEGER NOT NULL,
                    UNIQUE(stable_key, payload_json)
                )
                """.trimIndent()
            )
            val unresolvedKey = "legacy:download_snapshot_metadata:audio_name=song.mp3"
            val payload =
                "{\"stableKey\":\"$unresolvedKey\",\"audioFileName\":\"song.mp3\"}"
            sqliteDatabase.execSQL(
                "INSERT INTO legacy_download_upgrade_quarantine " +
                    "(stable_key, payload_json, reason, quarantined_at_ms) " +
                    "VALUES (?, ?, 'UNTRUSTWORTHY_STABLE_IDENTITY', 1)",
                arrayOf(unresolvedKey, payload)
            )
            val audio = ManagedDownloadStorage.StoredEntry(
                name = "song.mp3",
                reference = "content://current/song.mp3",
                mediaUri = "content://current/song.mp3",
                localFilePath = null,
                sizeBytes = 64L,
                lastModifiedMs = 1L
            )
            val metadata = DownloadedAudioMetadata(
                stableKey = "42|netease|",
                audioFileName = audio.name
            )
            val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
                audioEntries = listOf(audio),
                audioEntriesByLookupKey = emptyMap(),
                metadataEntriesByAudioName = emptyMap(),
                metadataByAudioName = mapOf(audio.name to metadata),
                audioEntriesWithoutMetadata = emptyList(),
                audioEntriesByStableKey = mapOf("42|netease|" to listOf(audio)),
                audioEntriesBySongId = emptyMap(),
                audioEntriesByMediaUri = emptyMap(),
                audioEntriesByRemoteTrackKey = emptyMap(),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap(),
                knownReferences = setOf(audio.reference)
            )

            val restored = LegacyDownloadUpgradeCoordinator(context, database)
                .requeueResolvableQuarantinedRows(snapshot)

            assertEquals(1, restored)
            assertTrue(
                sqliteDatabase.query(
                    "SELECT payload_json FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key = '42|netease|'"
                ).use { cursor ->
                    cursor.moveToFirst() &&
                        cursor.getString(0).contains("\"legacyQuarantineStableKey\"")
                }
            )
            assertFalse(
                sqliteDatabase.query(
                    "SELECT 1 FROM legacy_download_upgrade_quarantine LIMIT 1"
                ).use { cursor -> cursor.moveToFirst() }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun completedPayloadRowsAreDeletedInBatchesWithBoundedProgress() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val sqliteDatabase = database.openHelper.writableDatabase
            sqliteDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            sqliteDatabase.beginTransaction()
            try {
                repeat(130) { index ->
                    val stableKey = "$index|netease|"
                    val payload =
                        "{\"stableKey\":\"$stableKey\",\"download_cancelled_key\":" +
                            "{\"stable_key\":\"$stableKey\",\"cancelled_at_ms\":10}}"
                    sqliteDatabase.execSQL(
                        "INSERT INTO legacy_download_upgrade_payload " +
                            "(stable_key, payload_json) VALUES (?, ?)",
                        arrayOf(stableKey, payload)
                    )
                }
                sqliteDatabase.setTransactionSuccessful()
            } finally {
                sqliteDatabase.endTransaction()
            }
            val progress = mutableListOf<Pair<Int, Int>>()

            val coordinator = LegacyDownloadUpgradeCoordinator(context, database)
            val result = coordinator.execute { processed, total ->
                progress += processed to total
            }

            assertTrue(result.isComplete)
            assertEquals(130, result.rowsCompleted)
            assertEquals(
                listOf(
                    16 to 130,
                    32 to 130,
                    48 to 130,
                    64 to 130,
                    80 to 130,
                    96 to 130,
                    112 to 130,
                    128 to 130,
                    130 to 130
                ),
                progress
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun oneThousandMetadataPayloadsUpgradeWithinTenSeconds() = runTest {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            seedMetadataUpgrade(
                database = database,
                root = fixture.managedRoot,
                itemCount = METADATA_UPGRADE_BENCHMARK_SIZE
            )

            val startedAtNanos = System.nanoTime()
            val result = LegacyDownloadUpgradeCoordinator(fixture.context, database).execute()
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L

            assertTrue(result.isComplete)
            assertEquals(METADATA_UPGRADE_BENCHMARK_SIZE, result.rowsCompleted)
            assertEquals(
                METADATA_UPGRADE_BENCHMARK_SIZE,
                metadataFiles(fixture.managedRoot).size
            )
            assertTrue("elapsedMs=$elapsedMs", elapsedMs < METADATA_UPGRADE_BUDGET_MS)
            assertMetadataMatchesStableKeys(
                root = fixture.managedRoot,
                itemCount = METADATA_UPGRADE_BENCHMARK_SIZE
            )
        } finally {
            database.close()
            fixture.close()
        }
    }

    @Test
    fun cancelledBatchResumesFromDurablePayloadAfterDatabaseReopen() = runTest {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val databaseName = "legacy-upgrade-resume-${UUID.randomUUID()}.db"
        var database = openFileBackedDatabase(baseContext, databaseName)
        try {
            seedMetadataUpgrade(
                database = database,
                root = fixture.managedRoot,
                itemCount = PROCESS_DEATH_FIXTURE_SIZE
            )
            var cancellationObserved = false
            try {
                LegacyDownloadUpgradeCoordinator(fixture.context, database).execute {
                    processed,
                    _ ->
                    if (processed >= 16) {
                        throw CancellationException("simulated process stop")
                    }
                }
            } catch (_: CancellationException) {
                cancellationObserved = true
            }

            assertTrue(cancellationObserved)
            assertEquals(PROCESS_DEATH_FIXTURE_SIZE, payloadRowCount(database))
            database.close()
            database = openFileBackedDatabase(baseContext, databaseName)

            val resumed = LegacyDownloadUpgradeCoordinator(fixture.context, database).execute()

            assertTrue(resumed.isComplete)
            assertEquals(PROCESS_DEATH_FIXTURE_SIZE, resumed.rowsCompleted)
            assertFalse(payloadTableExists(database))
            assertMetadataMatchesStableKeys(
                root = fixture.managedRoot,
                itemCount = PROCESS_DEATH_FIXTURE_SIZE
            )
        } finally {
            database.close()
            baseContext.deleteDatabase(databaseName)
            fixture.close()
        }
    }

    private fun createStorageFixture(baseContext: Context): StorageFixture {
        val sandbox = File(
            baseContext.cacheDir,
            "legacy-upgrade-storage-${UUID.randomUUID()}"
        ).apply { mkdirs() }
        val context = IsolatedStorageContext(baseContext, sandbox)
        ManagedDownloadStorage.primeSettings(
            directoryUri = null,
            directoryLabel = null
        )
        val managedRoot = ManagedDownloadRootResolver.defaultRootDirectory(context).apply {
            mkdirs()
        }
        return StorageFixture(
            context = context,
            sandbox = sandbox,
            managedRoot = managedRoot
        )
    }

    private fun seedMetadataUpgrade(
        database: NeriUserDataDatabase,
        root: File,
        itemCount: Int
    ) {
        val sqliteDatabase = database.openHelper.writableDatabase
        sqliteDatabase.execSQL(
            """
            CREATE TABLE legacy_download_upgrade_payload (
              stable_key TEXT NOT NULL PRIMARY KEY,
              payload_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        val insert = sqliteDatabase.compileStatement(
            "INSERT INTO legacy_download_upgrade_payload " +
                "(stable_key, payload_json) VALUES (?, ?)"
        )
        sqliteDatabase.beginTransaction()
        try {
            repeat(itemCount) { index ->
                val stableKey = stableKey(index)
                val audioName = audioName(index)
                File(root, audioName).writeBytes(byteArrayOf((index % 251).toByte()))
                val payload = JSONObject()
                    .put("stableKey", stableKey)
                    .put("audioFileName", audioName)
                    .put("name", "Legacy song $index")
                    .put("artist", "Legacy artist")
                    .put("source", "netease")
                    .put("downloadTime", 10_000L + index)
                    .put(
                        "downloaded_song_catalog",
                        JSONObject()
                            .put("stable_key", stableKey)
                            .put("audio_file_name", audioName)
                    )
                insert.clearBindings()
                insert.bindString(1, stableKey)
                insert.bindString(2, payload.toString())
                insert.executeInsert()
            }
            sqliteDatabase.setTransactionSuccessful()
        } finally {
            sqliteDatabase.endTransaction()
        }
    }

    private fun openFileBackedDatabase(
        context: Context,
        databaseName: String
    ): NeriUserDataDatabase {
        return Room.databaseBuilder(
            context,
            NeriUserDataDatabase::class.java,
            databaseName
        ).allowMainThreadQueries().build()
    }

    private fun payloadRowCount(database: NeriUserDataDatabase): Int {
        return database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM legacy_download_upgrade_payload"
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private fun payloadTableExists(database: NeriUserDataDatabase): Boolean {
        return database.openHelper.writableDatabase.query(
            "SELECT 1 FROM sqlite_master " +
                "WHERE type = 'table' AND name = 'legacy_download_upgrade_payload'"
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun assertMetadataMatchesStableKeys(root: File, itemCount: Int) {
        repeat(itemCount) { index ->
            val metadataFile = File(root, audioName(index) + METADATA_SUFFIX)
            assertTrue(metadataFile.isFile)
            assertEquals(
                stableKey(index),
                JSONObject(metadataFile.readText()).getString("stableKey")
            )
        }
    }

    private fun metadataFiles(root: File): List<File> {
        return root.listFiles()
            ?.filter { file -> file.isFile && file.name.endsWith(METADATA_SUFFIX) }
            .orEmpty()
    }

    private fun stableKey(index: Int): String = "${index + 1}|netease|"

    private fun audioName(index: Int): String = "legacy-${index.toString().padStart(4, '0')}.mp3"

    private data class StorageFixture(
        val context: Context,
        val sandbox: File,
        val managedRoot: File
    ) {
        fun close() {
            ManagedDownloadStorage.primeSettings(
                directoryUri = null,
                directoryLabel = null
            )
            sandbox.deleteRecursively()
        }
    }

    private class IsolatedStorageContext(
        baseContext: Context,
        private val sandbox: File
    ) : ContextWrapper(baseContext) {
        override fun getApplicationContext(): Context = this

        override fun getExternalFilesDir(type: String?): File {
            return File(sandbox, "external/${type ?: "root"}").apply { mkdirs() }
        }

        override fun getFilesDir(): File {
            return File(sandbox, "files").apply { mkdirs() }
        }

        override fun getCacheDir(): File {
            return File(sandbox, "cache").apply { mkdirs() }
        }
    }

    private companion object {
        const val METADATA_UPGRADE_BENCHMARK_SIZE = 1_000
        const val PROCESS_DEATH_FIXTURE_SIZE = 128
        const val METADATA_UPGRADE_BUDGET_MS = 10_000L
    }
}
