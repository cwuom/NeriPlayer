package moe.ouom.neriplayer.core.startup.legacy

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.system.Os
import android.system.OsConstants
import androidx.core.content.edit
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Field
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.host.DownloadEnvironment
import moe.ouom.neriplayer.core.download.host.DownloadHostBindings
import moe.ouom.neriplayer.core.download.host.DownloadHosts
import moe.ouom.neriplayer.core.download.manager.catalog.cancelScheduledDownloadedSongsCatalogPersist
import moe.ouom.neriplayer.core.download.manager.catalog.publishDownloadedSongs
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState
import moe.ouom.neriplayer.data.model.download.ManagedLibraryRefreshOutcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyDownloadUpgradeRecoveryTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NeriUserDataDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun releaseVersion15CatalogWithThirtyFourMissingAudioFilesSettlesWithoutPayloadLoss() = runTest {
        assertMissingAssetUpgradeSettles(audioPresent = false, missingCover = false)
    }

    @Test
    fun releaseVersion15CatalogWithThirtyFourMissingCoverFilesPreservesSongMetadata() = runTest {
        assertMissingAssetUpgradeSettles(audioPresent = true, missingCover = true)
    }

    @Test
    fun affectedUserWithPersistedMissingAudioUpgradeClearsWaitingBanner() = runTest {
        assertMissingAssetUpgradeSettles(audioPresent = false, missingCover = false, resumeWaiting = true)
    }

    @Test
    fun affectedUserWithPersistedMissingCoverUpgradeResumesCatalogRebuild() = runTest {
        assertMissingAssetUpgradeSettles(audioPresent = true, missingCover = true, resumeWaiting = true)
    }

    @Test
    fun affectedVersion15UserWithEmptyCoverPublishesRecoveredCatalogAndClearsBanner() = runTest {
        assertMissingAssetUpgradeSettles(
            audioPresent = true,
            missingCover = true,
            resumeWaiting = true,
            emptyCover = true
        )
    }

    @Test
    fun affectedVersion15UserWithOneOversizedCoverPublishesSeventeenSongsAndClearsBanner() = runTest {
        val fixture = createStorageFixture()
        val rowCount = 17
        var affectedOperationId: String? = null
        try {
            val cover = File(fixture.managedRoot, "Covers/legacy-large.png").apply {
                parentFile?.mkdirs()
                writeLegacyCoverPng(this)
            }
            val originalCover = cover.readBytes()
            val database = openMigratedLegacyDatabase(
                fixture,
                audioPresent = true,
                missingCover = false,
                coverReference = { index ->
                    if (index == 0) cover.absolutePath else File(fixture.sandbox, "missing-cover-$index.jpg").absolutePath
                },
                rowCount = rowCount
            )
            try {
                val coordinator = LegacyDownloadUpgradeCoordinator(fixture.context, database)
                affectedOperationId = restoreAffectedUpgradeState(fixture.context, rowCount = rowCount)

                val recovered = LegacyJsonCleanupScheduler.runDownloadUpgradeOnce(fixture.context, coordinator)

                assertTrue(describeResult(recovered), recovered.isComplete)
                assertEquals(rowCount, recovered.rowsCompleted)
                assertEquals(0, recovered.rowsPending)
                assertFalse(tableExists(database.openHelper.writableDatabase, PAYLOAD_TABLE))
                repeat(rowCount) { index ->
                    assertTrue(File(fixture.managedRoot, audioName(index)).readBytes().contentEquals(byteArrayOf(index.toByte())))
                    val metadata = JSONObject(File(fixture.managedRoot, audioName(index) + METADATA_SUFFIX).readText())
                    assertEquals("User title $index", metadata.getString("customName"))
                    assertEquals("[00:00.00]Preserved legacy lyrics $index", metadata.getString("matchedLyric"))
                    assertEquals(250L + index, metadata.getLong("userLyricOffsetMs"))
                    if (index == 0) {
                        assertEquals(cover.absolutePath, metadata.getString("coverPath"))
                        val assets = metadata.getJSONObject("restorableMetadata").getJSONObject("assetRefs")
                        assertEquals(ManagedDownloadCoverAssetStore.sha256(originalCover), assets.getString("currentCoverHash"))
                    }
                }
                assertProcessingStateAfterUpgrade(fixture.context, affectedOperationId, expectRebuild = true)
                assertActualCatalogPublishCompletesProcessing(fixture, database, affectedOperationId, rowCount)
                assertTrue(cover.readBytes().contentEquals(originalCover))
                val repeated = coordinator.execute()
                assertTrue(describeResult(repeated), repeated.isComplete)
                assertEquals(0, repeated.rowsPending)
                assertProcessingStateAfterUpgrade(fixture.context, affectedOperationId, expectRebuild = false)
            } finally {
                awaitSnapshotPersistence()
                database.close()
            }
        } finally {
            try {
                completeFixtureOperation(fixture.context, affectedOperationId)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun affectedUserWhosePayloadAlreadySettledClearsPersistedWaitingBanner() = runTest {
        val fixture = createStorageFixture()
        var affectedOperationId: String? = null
        try {
            val database = openMigratedLegacyDatabase(fixture, audioPresent = false, missingCover = false)
            try {
                val sqliteDatabase = database.openHelper.writableDatabase
                val originalPayloads = readPayloads(sqliteDatabase, PAYLOAD_TABLE)
                val coordinator = LegacyDownloadUpgradeCoordinator(fixture.context, database)
                val initialResult = coordinator.execute()
                assertTrue(describeResult(initialResult), initialResult.isComplete)
                assertFalse(tableExists(sqliteDatabase, PAYLOAD_TABLE))
                affectedOperationId = restoreAffectedUpgradeState(fixture.context)

                val recovered = LegacyJsonCleanupScheduler.runDownloadUpgradeOnce(fixture.context, coordinator)

                assertTrue(describeResult(recovered), recovered.isComplete)
                assertEquals(0, recovered.rowsPending)
                assertEquals(0, recovered.rowsSeen)
                assertProcessingStateAfterUpgrade(fixture.context, affectedOperationId, expectRebuild = true)
                assertActualCatalogPublishCompletesProcessing(fixture, database, affectedOperationId, expectedSongCount = 0)
                assertEquals(originalPayloads, readPayloads(sqliteDatabase, QUARANTINE_TABLE))
            } finally {
                awaitSnapshotPersistence()
                database.close()
            }
        } finally {
            try {
                completeFixtureOperation(fixture.context, affectedOperationId)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun affectedUserWhoseCatalogScanFailedClearsWaitingBannerAfterActualPublish() = runTest {
        val fixture = createStorageFixture()
        var affectedOperationId: String? = null
        try {
            val database = openMigratedLegacyDatabase(fixture, audioPresent = true, missingCover = true)
            try {
                val coordinator = LegacyDownloadUpgradeCoordinator(fixture.context, database)
                affectedOperationId = restoreAffectedUpgradeState(fixture.context)
                val result = LegacyJsonCleanupScheduler.runDownloadUpgradeOnce(fixture.context, coordinator)
                assertTrue(describeResult(result), result.isComplete)
                assertProcessingStateAfterUpgrade(fixture.context, affectedOperationId, expectRebuild = true)

                withGlobalCatalogFixture(fixture, database) {
                    try {
                        Os.chmod(fixture.managedRoot.absolutePath, 0)

                        val failed = GlobalDownloadManager.scanLocalFilesAwait(fixture.context, forceRefresh = true)

                        assertFalse("scan=$failed", failed is ManagedLibraryRefreshOutcome.Published)
                        val waiting = ManagedLibraryProcessingCoordinator.state.value
                        assertTrue("state=$waiting", waiting is ManagedLibraryProcessingState.WaitingForRetry)
                        assertEquals(affectedOperationId, waiting.operationId)
                        assertEquals(ManagedLibraryProcessingPhase.REBUILDING_INDEX, waiting.phase)
                        val preferences = fixture.context.getSharedPreferences(PROCESSING_PREFERENCES, Context.MODE_PRIVATE)
                        assertEquals("waiting", preferences.getString("state_kind", null))
                        assertEquals(ManagedLibraryProcessingPhase.REBUILDING_INDEX.name, preferences.getString("phase", null))
                    } finally {
                        Os.chmod(fixture.managedRoot.absolutePath, OsConstants.S_IRWXU)
                    }

                    assertActualCatalogPublishCompletesProcessing(fixture, affectedOperationId, LEGACY_ROW_COUNT)
                }
            } finally {
                awaitSnapshotPersistence()
                database.close()
            }
        } finally {
            try {
                completeFixtureOperation(fixture.context, affectedOperationId)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun persistedCatalogRebuildWithAlreadySettledPayloadRequiresActualPublish() = runTest {
        val fixture = createStorageFixture()
        var affectedOperationId: String? = null
        try {
            val database = openMigratedLegacyDatabase(fixture, audioPresent = true, missingCover = true)
            try {
                val coordinator = LegacyDownloadUpgradeCoordinator(fixture.context, database)
                val originalPayloads = readPayloads(database.openHelper.writableDatabase, PAYLOAD_TABLE)
                val initialResult = coordinator.execute()
                assertTrue(describeResult(initialResult), initialResult.isComplete)
                affectedOperationId = restoreAffectedUpgradeState(
                    fixture.context,
                    phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
                )

                val recovered = LegacyJsonCleanupScheduler.runDownloadUpgradeOnce(fixture.context, coordinator)

                assertTrue(describeResult(recovered), recovered.isComplete)
                assertEquals(0, recovered.rowsSeen)
                assertProcessingStateAfterUpgrade(fixture.context, affectedOperationId, expectRebuild = true)
                assertLegacySidecarsPreserved(fixture, originalPayloads)
                assertActualCatalogPublishCompletesProcessing(fixture, database, affectedOperationId, LEGACY_ROW_COUNT)
            } finally {
                awaitSnapshotPersistence()
                database.close()
            }
        } finally {
            try {
                completeFixtureOperation(fixture.context, affectedOperationId)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun coverPermissionLossKeepsOriginalPayloadRetryable() = runTest {
        assertUnavailableCoverRemainsPending { SecurityException("synthetic cover permission loss") }
    }

    @Test
    fun coverProviderFailureKeepsOriginalPayloadRetryable() = runTest {
        assertUnavailableCoverRemainsPending { IllegalStateException("synthetic cover provider failure") }
    }

    @Test
    fun unreadableLocalCoverDirectoryKeepsOriginalPayloadRetryable() = runTest {
        val fixture = createStorageFixture()
        val coverDirectory = File(fixture.sandbox, "protected-covers").apply { mkdirs() }
        try {
            val database = openMigratedLegacyDatabase(
                fixture,
                audioPresent = true,
                missingCover = false,
                coverReference = { index ->
                    File(coverDirectory, "cover-$index.jpg").apply {
                        writeBytes(byteArrayOf(index.toByte()))
                    }.absolutePath
                }
            )
            try {
                val sqliteDatabase = database.openHelper.writableDatabase
                val originalPayloads = readPayloads(sqliteDatabase, PAYLOAD_TABLE)
                Os.chmod(coverDirectory.absolutePath, 0)
                assertEquals(0, Os.stat(coverDirectory.absolutePath).st_mode and OsConstants.S_IRWXU)

                val result = LegacyDownloadUpgradeCoordinator(fixture.context, database).execute()

                assertFalse(describeResult(result), result.isSettled)
                assertEquals(LEGACY_ROW_COUNT, result.rowsPending)
                assertEquals(0, result.rowsQuarantined)
                assertTrue(
                    describeResult(result),
                    result.rowResults.all { it.status == LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE }
                )
                assertEquals(originalPayloads, readPayloads(sqliteDatabase, PAYLOAD_TABLE))
                assertTrue(readPayloads(sqliteDatabase, QUARANTINE_TABLE).isEmpty())
            } finally {
                awaitSnapshotPersistence()
                database.close()
            }
        } finally {
            Os.chmod(coverDirectory.absolutePath, OsConstants.S_IRWXU)
            fixture.close()
        }
    }

    private suspend fun assertMissingAssetUpgradeSettles(
        audioPresent: Boolean,
        missingCover: Boolean,
        resumeWaiting: Boolean = false,
        emptyCover: Boolean = false
    ) {
        val fixture = createStorageFixture()
        var affectedOperationId: String? = null
        try {
            assertFalse(
                "remote cover references must not be classified as missing local files",
                ManagedDownloadCoverAssetStore.isSourceMissing(fixture.context, "https://example.invalid/cover.jpg")
            )
            val database = openMigratedLegacyDatabase(
                fixture,
                audioPresent,
                missingCover,
                coverReference = if (emptyCover) {
                    { index -> File(fixture.sandbox, "empty-cover-$index.jpg").apply { writeBytes(byteArrayOf()) }.absolutePath }
                } else {
                    null
                }
            )
            try {
                val sqliteDatabase = database.openHelper.writableDatabase
                val originalPayloads = readPayloads(sqliteDatabase, PAYLOAD_TABLE)
                assertEquals(LEGACY_ROW_COUNT, originalPayloads.size)
                val progress = mutableListOf<Pair<Int, Int>>()
                val coordinator = LegacyDownloadUpgradeCoordinator(fixture.context, database)

                val result = if (resumeWaiting) {
                    affectedOperationId = restoreAffectedUpgradeState(fixture.context)
                    LegacyJsonCleanupScheduler.runDownloadUpgradeOnce(fixture.context, coordinator)
                } else {
                    coordinator.execute { processed, total ->
                        progress += processed to total
                    }
                }

                if (resumeWaiting) {
                    assertProcessingStateAfterUpgrade(
                        fixture.context,
                        affectedOperationId,
                        expectRebuild = true
                    )
                } else {
                    assertEquals(LEGACY_ROW_COUNT to LEGACY_ROW_COUNT, progress.lastOrNull())
                }
                assertTrue(describeResult(result), result.isComplete)
                assertEquals(0, result.rowsPending)
                if (missingCover) {
                    assertEquals(LEGACY_ROW_COUNT, result.rowsCompleted)
                    assertEquals(0, result.rowsQuarantined)
                    assertLegacySidecarsPreserved(fixture, originalPayloads)
                } else {
                    assertEquals(LEGACY_ROW_COUNT, result.rowsQuarantined)
                    assertEquals(originalPayloads, readPayloads(sqliteDatabase, QUARANTINE_TABLE))
                }
                assertFalse(tableExists(sqliteDatabase, PAYLOAD_TABLE))
                repeat(LEGACY_ROW_COUNT) { index ->
                    assertEquals(audioPresent, File(fixture.managedRoot, audioName(index)).isFile)
                }
                if (resumeWaiting) {
                    assertActualCatalogPublishCompletesProcessing(
                        fixture,
                        database,
                        affectedOperationId,
                        expectedSongCount = if (audioPresent) LEGACY_ROW_COUNT else 0
                    )
                }

                val repeated = coordinator.execute()

                assertTrue(describeResult(repeated), repeated.isComplete)
                assertEquals(0, repeated.rowsPending)
                assertEquals(0, repeated.rowsSeen)
                if (missingCover) {
                    assertLegacySidecarsPreserved(fixture, originalPayloads)
                } else {
                    assertEquals(originalPayloads, readPayloads(sqliteDatabase, QUARANTINE_TABLE))
                }
            } finally {
                awaitSnapshotPersistence()
                database.close()
            }
        } finally {
            try {
                completeFixtureOperation(fixture.context, affectedOperationId)
            } finally {
                fixture.close()
            }
        }
    }

    private fun assertLegacySidecarsPreserved(fixture: StorageFixture, originalPayloads: Map<String, String>) {
        repeat(LEGACY_ROW_COUNT) { index ->
            val stableKey = "${index + 1}|netease|"
            val original = JSONObject(originalPayloads.getValue(stableKey))
            val missingCover = original.getJSONObject("downloaded_song_catalog").getString("cover_path")
            val metadataFile = File(fixture.managedRoot, audioName(index) + METADATA_SUFFIX)
            assertTrue("metadata must survive missing cover: $stableKey", metadataFile.isFile)
            val metadata = JSONObject(metadataFile.readText())
            assertEquals(stableKey, metadata.getString("stableKey"))
            assertEquals("User title $index", metadata.getString("customName"))
            assertEquals("[00:00.00]Preserved legacy lyrics $index", metadata.getString("matchedLyric"))
            assertEquals(250L + index, metadata.getLong("userLyricOffsetMs"))
            assertTrue(metadata.getBoolean("downloadFinalized"))
            assertTrue("missing cover must not remain visible", metadata.optString("coverPath").isBlank())
            val recoveryReferences = metadata.getJSONObject("restorableMetadata")
                .getJSONObject("assetRefs")
                .getJSONArray("legacyCoverRecoveryReferences")
            assertTrue(
                "missing cover reference must remain recoverable: $stableKey",
                (0 until recoveryReferences.length()).any { recoveryReferences.getString(it) == missingCover }
            )
        }
    }

    private suspend fun assertProcessingStateAfterUpgrade(
        context: Context,
        operationId: String?,
        expectRebuild: Boolean
    ) {
        val state = ManagedLibraryProcessingCoordinator.state.value
        val preferences = context.getSharedPreferences(PROCESSING_PREFERENCES, Context.MODE_PRIVATE)
        if (expectRebuild) {
            assertTrue("state=$state", state is ManagedLibraryProcessingState.Running)
            assertEquals(operationId, state.operationId)
            assertEquals(ManagedLibraryProcessingPhase.REBUILDING_INDEX, state.phase)
            assertEquals("running", preferences.getString("state_kind", null))
            assertEquals(ManagedLibraryProcessingPhase.REBUILDING_INDEX.name, preferences.getString("phase", null))
        } else {
            assertEquals(ManagedLibraryProcessingState.Idle, state)
            assertTrue("the old upgrade state must be durably cleared", preferences.all.isEmpty())
            assertEquals(ManagedLibraryProcessingState.Idle, ManagedLibraryProcessingCoordinator.restore(context))
        }
    }

    private suspend fun assertActualCatalogPublishCompletesProcessing(
        fixture: StorageFixture,
        database: NeriUserDataDatabase,
        operationId: String?,
        expectedSongCount: Int
    ) {
        withGlobalCatalogFixture(fixture, database) {
            assertActualCatalogPublishCompletesProcessing(fixture, operationId, expectedSongCount)
        }
    }

    private suspend fun assertActualCatalogPublishCompletesProcessing(
        fixture: StorageFixture,
        operationId: String?,
        expectedSongCount: Int
    ) {
        assertEquals(operationId, ManagedLibraryProcessingCoordinator.state.value.operationId)
        val published = GlobalDownloadManager.scanLocalFilesAwait(fixture.context, forceRefresh = true)
        assertTrue("scan=$published", published is ManagedLibraryRefreshOutcome.Published)
        assertEquals(expectedSongCount, (published as ManagedLibraryRefreshOutcome.Published).songCount)
        assertEquals(expectedSongCount, GlobalDownloadManager.downloadedSongsMutable.value.size)
        assertProcessingStateAfterUpgrade(fixture.context, operationId, expectRebuild = false)
    }

    private suspend fun withGlobalCatalogFixture(
        fixture: StorageFixture,
        database: NeriUserDataDatabase,
        block: suspend () -> Unit
    ) {
        awaitGlobalCatalogWork()
        val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
        val previousPresenceVersion = GlobalDownloadManager.downloadPresenceVersionMutable.value
        val previousCatalogRevision = GlobalDownloadManager.downloadedSongCatalogPersistenceRevision.get()
        val previousMetadataRevision = GlobalDownloadManager.downloadedSongMetadataRevision.get()
        val previousEmptySequence = GlobalDownloadManager.emptyScanSequence.get()
        val previousCatalogGeneration = GlobalDownloadManager.catalogPersistGeneration.get()
        val previousManager = capturePrivateState(
            GlobalDownloadManager,
            "downloadedSongCatalogIndex", "downloadedSongCatalogReady", "downloadedSongCatalogRootKey",
            "refreshJob", "catalogPersistJob", "catalogReconcileJob", "fastIndexPersistenceJob",
            "pendingRefresh", "pendingForceRefresh", "activeRefreshForceRefresh",
            "pendingCatalogReconcileForceRefresh", "pendingFastIndexPersistence"
        )
        val previousReconciler = capturePrivateState(
            GlobalDownloadManager.managedLibraryReconciler,
            "pendingEmpty", "lastCompleteEmpty"
        )
        val previousHosts = DownloadHostBindings(
            DownloadHosts.environment,
            DownloadHosts.sources,
            DownloadHosts.lyrics,
            DownloadHosts.credentials,
            DownloadHosts.playback
        )
        val isolatedEnvironment = object : DownloadEnvironment by previousHosts.environment {
            override val applicationContext: Context = fixture.context

            // 隔离区后台调度另有测试，扫描本身仍执行真实目录发布与状态收尾
            override fun scheduleQuarantineRecovery(context: Context) = Unit
        }
        DownloadHosts.install(
            DownloadHostBindings(
                isolatedEnvironment,
                previousHosts.sources,
                previousHosts.lyrics,
                previousHosts.credentials,
                previousHosts.playback
            )
        )
        fixture.databaseInstanceField.set(null, database)
        GlobalDownloadManager.publishDownloadedSongs(fixture.context, emptyList(), persistCatalog = false)
        GlobalDownloadManager.downloadedSongCatalogRootKey =
            ManagedDownloadStorage.currentSnapshotCacheKey(fixture.context)
        GlobalDownloadManager.managedLibraryReconciler.reset()
        try {
            block()
        } finally {
            withContext(NonCancellable) {
                GlobalDownloadManager.refreshJob?.join()
                GlobalDownloadManager.fastIndexPersistenceJob?.join()
                GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                val fixturePersistJob = GlobalDownloadManager.catalogPersistJob
                GlobalDownloadManager.cancelScheduledDownloadedSongsCatalogPersist()
                fixturePersistJob?.join()
                awaitSnapshotPersistence()
                GlobalDownloadManager.publishDownloadedSongs(fixture.baseContext, previousSongs, persistCatalog = false)
                previousManager.restore()
                previousReconciler.restore()
                GlobalDownloadManager.downloadedSongCatalogPersistenceRevision.set(previousCatalogRevision)
                GlobalDownloadManager.downloadedSongMetadataRevision.set(previousMetadataRevision)
                GlobalDownloadManager.emptyScanSequence.set(previousEmptySequence)
                GlobalDownloadManager.catalogPersistGeneration.set(previousCatalogGeneration)
                GlobalDownloadManager.downloadPresenceVersionMutable.value = previousPresenceVersion
                DownloadHosts.install(previousHosts)
            }
        }
    }

    private suspend fun awaitGlobalCatalogWork() {
        GlobalDownloadManager.catalogReconcileJob?.join()
        GlobalDownloadManager.refreshJob?.join()
        GlobalDownloadManager.fastIndexPersistenceJob?.join()
        GlobalDownloadManager.catalogPersistJob?.join()
        awaitSnapshotPersistence()
    }

    private suspend fun awaitSnapshotPersistence() {
        val store = ManagedDownloadStorage.snapshotCacheStore
        for (name in arrayOf("snapshotClearJob", "snapshotPersistJob")) {
            val field = store.javaClass.getDeclaredField(name).apply { isAccessible = true }
            (field.get(store) as? Job)?.join()
        }
    }

    private fun capturePrivateState(target: Any, vararg fieldNames: String): PrivateState {
        return PrivateState(target, fieldNames.map { name ->
            val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
            field to field.get(target)
        })
    }

    private class PrivateState(private val target: Any, private val fields: List<Pair<Field, Any?>>) {
        fun stringValue(name: String): String? = fields.first { it.first.name == name }.second as? String

        fun restore() {
            fields.forEach { (field, value) -> field.set(target, value) }
        }
    }

    private suspend fun completeFixtureOperation(context: Context, operationId: String?) {
        withContext(NonCancellable) {
            if (operationId != null && ManagedLibraryProcessingCoordinator.state.value.operationId == operationId) {
                ManagedLibraryProcessingCoordinator.complete(context, operationId)
            }
        }
    }

    private suspend fun restoreAffectedUpgradeState(
        context: Context,
        phase: ManagedLibraryProcessingPhase = ManagedLibraryProcessingPhase.UPGRADING_DATABASE,
        rowCount: Int = LEGACY_ROW_COUNT
    ): String {
        ManagedLibraryProcessingCoordinator.state.value.operationId?.let { operationId ->
            ManagedLibraryProcessingCoordinator.complete(context, operationId)
        }
        val operationId = "affected-legacy-upgrade-${UUID.randomUUID()}"
        context.getSharedPreferences(PROCESSING_PREFERENCES, Context.MODE_PRIVATE).edit(commit = true) {
            clear()
            putString("operation_id", operationId)
            putString("reason", ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE.name)
            putString("phase", phase.name)
            putString("state_kind", "waiting")
            putInt("processed", rowCount)
            putInt("total", rowCount)
        }
        val restored = ManagedLibraryProcessingCoordinator.restore(context)
        assertTrue("restored=$restored", restored is ManagedLibraryProcessingState.WaitingForRetry)
        assertEquals(operationId, restored.operationId)
        assertEquals(rowCount, restored.processed)
        assertEquals(rowCount, restored.total)
        return operationId
    }

    private suspend fun assertUnavailableCoverRemainsPending(failure: () -> Throwable) {
        val fixture = createStorageFixture()
        try {
            val database = openMigratedLegacyDatabase(
                fixture,
                audioPresent = true,
                missingCover = false,
                coverReference = { index -> "content://legacy-upgrade-test/document/cover-$index.jpg" }
            )
            try {
                val sqliteDatabase = database.openHelper.writableDatabase
                val originalPayloads = readPayloads(sqliteDatabase, PAYLOAD_TABLE)
                val resolverFailures = AtomicInteger()
                val unavailableContext = object : ContextWrapper(fixture.context) {
                    override fun getApplicationContext(): Context = this

                    override fun getContentResolver(): ContentResolver {
                        resolverFailures.incrementAndGet()
                        throw failure()
                    }
                }

                val result = LegacyDownloadUpgradeCoordinator(unavailableContext, database).execute()

                assertTrue("the synthetic resolver failure must be exercised", resolverFailures.get() > 0)
                assertFalse(describeResult(result), result.isSettled)
                assertEquals(LEGACY_ROW_COUNT, result.rowsPending)
                assertEquals(0, result.rowsQuarantined)
                assertTrue(
                    describeResult(result),
                    result.rowResults.all { it.status == LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE }
                )
                assertEquals(originalPayloads, readPayloads(sqliteDatabase, PAYLOAD_TABLE))
                assertTrue(readPayloads(sqliteDatabase, QUARANTINE_TABLE).isEmpty())
            } finally {
                awaitSnapshotPersistence()
                database.close()
            }
        } finally {
            fixture.close()
        }
    }

    private fun openMigratedLegacyDatabase(
        fixture: StorageFixture,
        audioPresent: Boolean,
        missingCover: Boolean,
        coverReference: ((Int) -> String)? = null,
        rowCount: Int = LEGACY_ROW_COUNT
    ): NeriUserDataDatabase {
        helper.createDatabase(fixture.databaseName, 15).use { database ->
            val rootKey = ManagedDownloadStorage.currentSnapshotCacheKey(fixture.context)
            val oldCoverDirectory = File(fixture.sandbox, "old-covers").apply { mkdirs() }
            repeat(rowCount) { index ->
                val audio = File(fixture.managedRoot, audioName(index))
                if (audioPresent) audio.writeBytes(byteArrayOf(index.toByte()))
                val coverPath = if (coverReference != null) {
                    coverReference(index)
                } else if (missingCover) {
                    File(oldCoverDirectory, "legacy-$index.jpg").absolutePath
                } else {
                    null
                }
                database.execSQL(
                    """
                    INSERT INTO downloaded_song_catalog (
                        catalog_key, root_key, display_position, id, name, artist, album,
                        file_path, file_size, download_time, cover_path, custom_name,
                        matched_lyric, user_lyric_offset_ms, media_uri, duration_ms, stable_key
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        "file:${audio.absolutePath}",
                        rootKey,
                        index,
                        index + 1L,
                        "Legacy song $index",
                        "Legacy artist",
                        "Legacy album",
                        audio.absolutePath,
                        1L,
                        10_000L + index,
                        coverPath,
                        "User title $index",
                        "[00:00.00]Preserved legacy lyrics $index",
                        250L + index,
                        audio.toURI().toString(),
                        180_000L,
                        "${index + 1}|netease|"
                    )
                )
            }
        }
        helper.runMigrationsAndValidate(
            fixture.databaseName,
            NeriUserDataDatabase.FINAL_DB_VERSION,
            false,
            NeriUserDataDatabase.MIGRATION_15_FINAL,
            NeriUserDataDatabase.MIGRATION_16_17,
            NeriUserDataDatabase.MIGRATION_17_18,
            NeriUserDataDatabase.MIGRATION_18_19,
            NeriUserDataDatabase.MIGRATION_19_20
        ).close()
        return Room.databaseBuilder(
            fixture.baseContext,
            NeriUserDataDatabase::class.java,
            fixture.databaseName
        ).allowMainThreadQueries().build().also { database ->
            fixture.databaseInstanceField.set(null, database)
        }
    }

    private fun readPayloads(
        database: SupportSQLiteDatabase,
        tableName: String
    ): Map<String, String> = buildMap {
        database.query("SELECT stable_key, payload_json FROM $tableName ORDER BY stable_key").use { cursor ->
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
        }
    }

    private fun tableExists(database: SupportSQLiteDatabase, tableName: String): Boolean {
        return database.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName)
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun describeResult(result: LegacyDownloadUpgradeResult): String {
        return "rowsSeen=${result.rowsSeen}, rowsCompleted=${result.rowsCompleted}, " +
            "rowsPending=${result.rowsPending}, rowsQuarantined=${result.rowsQuarantined}, " +
            "rowResults=${result.rowResults}"
    }

    private suspend fun createStorageFixture(): StorageFixture {
        val schedulerReservation = reserveLegacyScheduler()
        var startupLocked = false
        var recoveryLocked = false
        try {
            GlobalDownloadManager.startupRecoveryMutex.lock()
            startupLocked = true
            GlobalDownloadManager.pendingDownloadRecoverySlot.lock()
            recoveryLocked = true
            return createLockedStorageFixture(schedulerReservation)
        } catch (error: Throwable) {
            if (recoveryLocked) GlobalDownloadManager.pendingDownloadRecoverySlot.unlock()
            if (startupLocked) GlobalDownloadManager.startupRecoveryMutex.unlock()
            schedulerReservation.close()
            throw error
        }
    }

    private suspend fun reserveLegacyScheduler(): SchedulerReservation = withContext(Dispatchers.IO) {
        val type = LegacyJsonCleanupScheduler.javaClass
        val running = type.getDeclaredField("running").apply { isAccessible = true }
            .get(null) as AtomicBoolean
        val quarantineRunning = type.getDeclaredField("quarantineRecoveryRunning").apply { isAccessible = true }
            .get(null) as AtomicBoolean
        var runningReserved = false
        var quarantineReserved = false
        try {
            withTimeout(45_000L) {
                while (!running.compareAndSet(false, true)) delay(25L)
                runningReserved = true
            }
            withTimeout(45_000L) {
                while (!quarantineRunning.compareAndSet(false, true)) delay(25L)
                quarantineReserved = true
            }
            val pendingReason = type.getDeclaredField("pendingReason").apply { isAccessible = true }
                .get(null) as AtomicReference<*>
            val previousPendingReason = pendingReason.getAndSet(null)
            SchedulerReservation(running, quarantineRunning, pendingReason, previousPendingReason)
        } catch (error: Throwable) {
            if (quarantineReserved) quarantineRunning.set(false)
            if (runningReserved) running.set(false)
            throw error
        }
    }

    private class SchedulerReservation(
        private val running: AtomicBoolean,
        private val quarantineRunning: AtomicBoolean,
        private val pendingReason: AtomicReference<*>,
        private val previousPendingReason: Any?
    ) {
        fun close() {
            try {
                pendingReason.javaClass.getMethod("set", Any::class.java).invoke(pendingReason, previousPendingReason)
            } finally {
                quarantineRunning.set(false)
                running.set(false)
            }
        }
    }

    private suspend fun createLockedStorageFixture(schedulerReservation: SchedulerReservation): StorageFixture {
        awaitGlobalCatalogWork()
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val databaseInstanceField = NeriUserDataDatabase::class.java.getDeclaredField("instance").apply {
            isAccessible = true
        }
        val previousDatabase = databaseInstanceField.get(null)
        val previousSettings = capturePrivateState(
            ManagedDownloadStorage.settings,
            "customDirectoryUri", "customDirectoryLabel", "downloadFileNameTemplate"
        )
        val previousRoot = capturePrivateState(ManagedDownloadStorage.rootResolver, "cachedTreeRoot")
        val previousSnapshot = capturePrivateState(
            ManagedDownloadStorage.snapshotCacheStore,
            "snapshotCache", "snapshotRevision", "snapshotGeneration", "snapshotClearInFlight",
            "snapshotPersistJob", "snapshotClearJob"
        )
        val fixtureId = UUID.randomUUID().toString()
        val sandbox = File(baseContext.cacheDir, "legacy-upgrade-recovery-$fixtureId").apply {
            mkdirs()
        }
        val context = IsolatedStorageContext(baseContext, sandbox, fixtureId)
        ManagedDownloadStorage.primeSettings(directoryUri = null, directoryLabel = null)
        val root = ManagedDownloadRootResolver.defaultRootDirectory(context).apply { mkdirs() }
        return StorageFixture(
            baseContext, context, sandbox, root, "legacy-upgrade-recovery-$fixtureId.db",
            databaseInstanceField, previousDatabase, previousSettings, previousRoot, previousSnapshot,
            schedulerReservation
        )
    }

    private class StorageFixture(
        val baseContext: Context,
        val context: IsolatedStorageContext,
        val sandbox: File,
        val managedRoot: File,
        val databaseName: String,
        val databaseInstanceField: Field,
        private val previousDatabase: Any?,
        private val previousSettings: PrivateState,
        private val previousRoot: PrivateState,
        private val previousSnapshot: PrivateState,
        private val schedulerReservation: SchedulerReservation
    ) {
        fun close() {
            try {
                ManagedDownloadStorage.primeSettings(
                    directoryUri = previousSettings.stringValue("customDirectoryUri"),
                    directoryLabel = previousSettings.stringValue("customDirectoryLabel"),
                    fileNameTemplate = previousSettings.stringValue("downloadFileNameTemplate")
                )
                previousRoot.restore()
                previousSnapshot.restore()
                databaseInstanceField.set(null, previousDatabase)
                baseContext.deleteDatabase(databaseName)
                context.deletePreferences()
                sandbox.deleteRecursively()
            } finally {
                GlobalDownloadManager.pendingDownloadRecoverySlot.unlock()
                GlobalDownloadManager.startupRecoveryMutex.unlock()
                schedulerReservation.close()
            }
        }
    }

    private class IsolatedStorageContext(
        baseContext: Context,
        private val sandbox: File,
        private val fixtureId: String
    ) : ContextWrapper(baseContext) {
        private val preferenceNames = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this

        override fun getExternalFilesDir(type: String?): File =
            File(sandbox, "external/${type ?: "root"}").apply { mkdirs() }

        override fun getFilesDir(): File = File(sandbox, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(sandbox, "cache").apply { mkdirs() }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "legacy-upgrade-recovery-$fixtureId-$name"
            synchronized(preferenceNames) { preferenceNames += isolatedName }
            return baseContext.getSharedPreferences(isolatedName, mode)
        }

        fun deletePreferences() {
            synchronized(preferenceNames) {
                preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            }
        }
    }

    private fun audioName(index: Int): String = "legacy-${index.toString().padStart(4, '0')}.mp3"

    private companion object {
        const val LEGACY_ROW_COUNT = 34
        const val PAYLOAD_TABLE = "legacy_download_upgrade_payload"
        const val QUARANTINE_TABLE = "legacy_download_upgrade_quarantine"
        const val PROCESSING_PREFERENCES = "managed_library_processing"
    }
}
