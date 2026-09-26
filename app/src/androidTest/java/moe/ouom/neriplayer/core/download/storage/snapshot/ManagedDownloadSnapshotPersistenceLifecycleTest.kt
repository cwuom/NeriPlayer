package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.SNAPSHOT_CACHE_FILE_NAME
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.store.LegacyJsonCleanupCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadSnapshotPersistenceLifecycleTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var database: NeriUserDataDatabase
    private lateinit var scopeJob: Job
    private lateinit var scope: CoroutineScope

    @Before
    fun createIsolatedPersistence() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        directory = File(baseContext.cacheDir, "snapshot-lifecycle-${UUID.randomUUID()}")
        check(directory.mkdirs())
        context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        database = Room.inMemoryDatabaseBuilder(baseContext, NeriUserDataDatabase::class.java)
            .build()
        scopeJob = SupervisorJob()
        scope = CoroutineScope(scopeJob + Dispatchers.IO)
    }

    @After
    fun removeOwnedPersistence() = runBlocking {
        if (::scopeJob.isInitialized) scopeJob.cancelAndJoin()
        if (::database.isInitialized) database.close()
        if (::directory.isInitialized) check(directory.deleteRecursively())
    }

    @Test
    fun scheduledSnapshotSurvivesTwoColdCacheInstances() = runBlocking {
        val original = snapshot()
        cacheStore().putSnapshot(context, "root-a", original)
        awaitPersistence()
        assertEquals("root-a", database.syncMetadataDao().getMigrationMetadata(
            ManagedDownloadSnapshotRoomStore.ROOT_KEY_METADATA_KEY
        )?.value)

        assertNull(coldRestore("root-b"))
        repeat(2) { index ->
            val restored = coldRestore("root-a")
            assertNotNull("cold restore ${index + 1} must retain the only persisted snapshot", restored)
            assertSnapshot(original, checkNotNull(restored))
        }
    }

    @Test
    fun restoringAnExistingSnapshotDoesNotConsumeItsDurableCopy() = runBlocking {
        val original = snapshot()
        assertTrue(ManagedDownloadSnapshotRoomStore(context, database).persist("root-a", original))

        repeat(2) { index ->
            val restored = coldRestore("root-a")
            assertNotNull("reading a snapshot must not prevent cold restore ${index + 1}", restored)
            assertSnapshot(original, checkNotNull(restored))
        }
        assertTrue(ManagedDownloadSnapshotDiskCache.cacheFile(context).isFile)
    }

    @Test
    fun legacyCleanupCannotDeleteTheSnapshotStoredByTheCurrentAdapter() = runBlocking {
        val original = snapshot()
        assertTrue(ManagedDownloadSnapshotRoomStore(context, database).persist("root-a", original))
        val cacheFile = ManagedDownloadSnapshotDiskCache.cacheFile(context)
        val cleanup = LegacyJsonCleanupCoordinator(context, database)
        val plan = cleanup.buildPlan()
        assertTrue(plan.targets.none { it.fileName == cacheFile.name })
        assertEquals(
            ManagedDownloadSnapshotRoomStore.DISK_PRIMARY_STATE,
            database.syncMetadataDao().getMigrationMetadata(
                ManagedDownloadSnapshotRoomStore.CUTOVER_STATE_METADATA_KEY
            )?.value
        )
        val result = cleanup.execute(plan, confirmed = true)
        assertFalse(cacheFile.name in result.deletedFiles)
        assertTrue(cacheFile.isFile)
        repeat(2) { assertSnapshot(original, checkNotNull(coldRestore("root-a"))) }
    }

    @Test
    fun capturedLegacyCleanupTargetsCannotAddressTheCurrentSnapshot() = runBlocking {
        val original = snapshot()
        val legacyFile = File(context.filesDir, SNAPSHOT_CACHE_FILE_NAME)
        legacyFile.writeText(ManagedDownloadSnapshotIndex.serializePayload("root-a", original))
        database.syncMetadataDao().upsertMigrationMetadata(MigrationMetadataEntity(
            key = ManagedDownloadSnapshotRoomStore.CUTOVER_STATE_METADATA_KEY,
            value = ManagedDownloadSnapshotRoomStore.ROOM_PRIMARY_STATE,
            updatedAt = 1L
        ))
        val capturedPlan = LegacyJsonCleanupCoordinator(context, database).buildPlan()
        assertTrue(capturedPlan.existingEligibleTargets.any { it.fileName == legacyFile.name })

        assertTrue(ManagedDownloadSnapshotRoomStore(context, database).persist("root-a", original))
        val currentFile = ManagedDownloadSnapshotDiskCache.cacheFile(context)
        assertTrue(
            "current payload must be outside the captured legacy deletion namespace",
            capturedPlan.targets.none { it.fileName == currentFile.name }
        )
        // 模拟清理器已读完旧计划，随后新写入完成，再执行已捕获的删除集合
        capturedPlan.existingEligibleTargets.forEach { target ->
            assertTrue(File(context.filesDir, target.fileName).delete())
        }
        assertFalse(legacyFile.exists())
        repeat(2) { assertSnapshot(original, checkNotNull(coldRestore("root-a"))) }
    }

    @Test
    fun legacySnapshotRemainsReadableUntilExplicitInvalidation() = runBlocking {
        val original = snapshot()
        val legacyFile = File(context.filesDir, SNAPSHOT_CACHE_FILE_NAME)
        legacyFile.writeText(ManagedDownloadSnapshotIndex.serializePayload("root-a", original))
        repeat(2) { assertSnapshot(original, checkNotNull(coldRestore("root-a"))) }
        assertTrue(legacyFile.isFile)

        cacheStore().invalidate(context)
        awaitPersistence()

        assertFalse(legacyFile.exists())
        assertNull(coldRestore("root-a"))
    }

    @Test
    fun currentSnapshotNeverFallsBackToAnOlderLegacyPayload() = runBlocking {
        val legacyFile = File(context.filesDir, SNAPSHOT_CACHE_FILE_NAME)
        legacyFile.writeText(ManagedDownloadSnapshotIndex.serializePayload("root-a", snapshot()))
        val currentFile = ManagedDownloadSnapshotDiskCache.cacheFile(context)
        assertTrue(ManagedDownloadSnapshotRoomStore(context, database).persist("root-b", snapshot()))

        assertNull(coldRestore("root-a"))
        assertSnapshot(snapshot(), checkNotNull(coldRestore("root-b")))

        currentFile.writeText("{broken-current-payload")
        assertNull(coldRestore("root-a"))
        assertNull(coldRestore("root-b"))
        assertTrue(legacyFile.isFile)
    }

    @Test
    fun failingTheCutoverMarkerKeepsThePreviousPayloadByteForByte() = runBlocking {
        val store = ManagedDownloadSnapshotRoomStore(context, database)
        assertTrue(store.persist("root-a", snapshot()))
        val cacheFile = ManagedDownloadSnapshotDiskCache.cacheFile(context)
        val previousPayload = cacheFile.readText()
        database.openHelper.writableDatabase.execSQL(
            """
                CREATE TRIGGER fail_snapshot_marker BEFORE INSERT ON migration_metadata
                WHEN NEW.key = '${ManagedDownloadSnapshotRoomStore.CUTOVER_STATE_METADATA_KEY}'
                BEGIN SELECT RAISE(ABORT, 'injected snapshot marker failure'); END
            """.trimIndent()
        )

        val result = runCatching { store.persist("root-b", snapshot()) }

        assertTrue(result.isFailure || result.getOrNull() == false)
        assertEquals(previousPayload, cacheFile.readText())
        assertEquals("root-a", ManagedDownloadSnapshotDiskCache.restore(context)?.first)
        assertTrue(directory.listFiles().orEmpty().all { it == cacheFile })
    }

    @Test
    fun explicitInvalidationClearsTheSnapshotBeforeANewerGenerationIsPersisted() = runBlocking {
        val cache = cacheStore()
        val original = snapshot()
        val legacyFile = File(context.filesDir, SNAPSHOT_CACHE_FILE_NAME)
        legacyFile.writeText(ManagedDownloadSnapshotIndex.serializePayload("root-a", original))
        cache.putSnapshot(context, "root-a", original)
        awaitPersistence()
        assertSnapshot(original, checkNotNull(coldRestore("root-a")))

        cache.invalidate(context)
        awaitPersistence()
        assertNull(coldRestore("root-a"))
        assertFalse(ManagedDownloadSnapshotDiskCache.cacheFile(context).exists())
        assertFalse(legacyFile.exists())

        val replacement = original.copy(rootEntriesComplete = true)
        cache.putSnapshot(context, "root-a", replacement)
        awaitPersistence()
        repeat(2) { assertSnapshot(replacement, checkNotNull(coldRestore("root-a"))) }
    }

    private suspend fun awaitPersistence() = withTimeout(10_000L) {
        while (true) {
            val pending = scopeJob.children.toList()
            if (pending.isEmpty()) return@withTimeout
            pending.joinAll()
        }
    }

    private fun cacheStore() = ManagedDownloadSnapshotCacheStore(
        scope = scope,
        cacheKeyProvider = { "root-a" },
        // 使用默认适配器的真实实现，仅隔离 Room 实例以免污染应用恢复标记
        persistenceStoreProvider = { ManagedDownloadSnapshotRoomStore(it, database) }
    )

    private suspend fun coldRestore(rootKey: String) = withContext(Dispatchers.IO) {
        cacheStore().restorePersisted(context, expectedKey = rootKey)
    }

    private fun assertSnapshot(
        expected: ManagedDownloadStorage.DownloadLibrarySnapshot,
        actual: ManagedDownloadStorage.DownloadLibrarySnapshot
    ) {
        assertEquals(expected.audioEntries, actual.audioEntries)
        assertEquals(expected.audioEntriesByRemoteTrackKey, actual.audioEntriesByRemoteTrackKey)
        assertEquals(expected.knownReferences, actual.knownReferences)
        assertEquals(expected.rootEntriesComplete, actual.rootEntriesComplete)
        assertEquals(expected.sidecarEntriesComplete, actual.sidecarEntriesComplete)
        val expectedMetadata = expected.metadataByAudioName.values.single()
        val actualMetadata = actual.metadataByAudioName.values.single()
        assertEquals(expectedMetadata.stableKey, actualMetadata.stableKey)
        assertEquals(expectedMetadata.customName, actualMetadata.customName)
        assertEquals(expectedMetadata.customArtist, actualMetadata.customArtist)
        assertEquals(expectedMetadata.originalLyric, actualMetadata.originalLyric)
        assertEquals(expectedMetadata.originalTranslatedLyric, actualMetadata.originalTranslatedLyric)
        assertEquals(expectedMetadata.originalRomanizedLyric, actualMetadata.originalRomanizedLyric)
    }

    private fun snapshot(): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "snapshot-lifecycle.flac",
            reference = "content://snapshot.fixture/document/opaque%2Faudio",
            mediaUri = "content://snapshot.fixture/document/opaque%2Faudio",
            localFilePath = null,
            sizeBytes = 4_096L,
            lastModifiedMs = 100L
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "snapshot-lifecycle-stable",
            songId = 55L,
            name = "Snapshot lifecycle",
            artist = "Artist",
            customName = "Custom title",
            customArtist = "Custom artist",
            originalLyric = "[00:01.00]original",
            originalTranslatedLyric = "[00:01.00]translated",
            originalRomanizedLyric = "[00:01.00]romanized",
            channelId = "netease",
            audioId = "55",
            downloadFinalized = true
        )
        return ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(audio),
            metadataEntries = listOf(audio.copy(
                name = audio.name + ".npmeta.json",
                reference = "content://snapshot.fixture/document/opaque%2Fmetadata",
                mediaUri = "content://snapshot.fixture/document/opaque%2Fmetadata"
            )),
            metadataByAudioName = mapOf(audio.name to metadata),
            coverEntries = emptyList(),
            lyricEntries = emptyList(),
            rootEntriesComplete = false,
            sidecarEntriesComplete = false
        )
    }
}
