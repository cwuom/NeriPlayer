package moe.ouom.neriplayer.core.download.catalog

import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadMigrationTestDocumentProvider
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioMetadataStore
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.toPlaybackSongItem
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedLibraryItemFileNameTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    )
    private var previousDirectoryUri: String? = null

    @Before
    fun setUp() {
        previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        resetProvider()
        ManagedDownloadStorage.primeSettings(treeUri.toString(), null)
    }

    @After
    fun tearDown() {
        ManagedDownloadStorage.primeSettings(previousDirectoryUri, null)
        resetProvider()
    }

    @Test
    fun opaqueProviderScanKeepsDisplayNameAcrossAllPreviewWrites() = runBlocking {
        val displayName = "NeriSafFixture__present.m4a"
        val song = scannedSong(displayName)
        assertEquals(displayName, song.localFileName)
        val failures = mutableListOf<String>()
        for (mode in listOf("full", "delta", "upsert", "backup")) {
            val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
            try {
                when (mode) {
                    "full" -> ManagedLibraryItemRoomStore.replacePreviews(context, listOf(song), database)
                    "delta" -> ManagedLibraryItemRoomStore.applyPreviewDelta(context, listOf(song), emptySet(), database)
                    "upsert" -> ManagedLibraryItemRoomStore.upsertPreview(context, song, database = database)
                    "backup" -> {
                        val restored = requireNotNull(deserializeDownloadedSongsCatalog(
                            serializeDownloadedSongsCatalog("fixture", listOf(song)), "fixture"
                        )).single()
                        ManagedLibraryItemRoomStore.upsertPreview(context, restored, database = database)
                    }
                }
                val actual = database.managedLibraryItemDao()
                    .findAll(ManagedDownloadStorage.currentSnapshotCacheKey(context)).single().audioName
                if (actual != displayName) failures += "$mode expected=$displayName actual=$actual"
            } finally {
                database.close()
            }
        }
        assertEquals("opaque document ids are not display names", emptyList<String>(), failures)
    }

    @Test
    fun freshOpaqueScanRepairsExistingWrongNameAndRoomRestoreRetainsIt() = runBlocking {
        val name = "literal%20+colon:name.m4a"
        val song = scannedSong(name)
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            ManagedLibraryItemRoomStore.upsertPreview(context, song, metadataRevision = 1L, database = database)
            val dao = database.managedLibraryItemDao()
            val rootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
            dao.upsert(dao.findAll(rootKey).single().copy(
                audioName = song.filePath.substringAfterLast('/'), metadataRevision = 1L
            ))
            ManagedLibraryItemRoomStore.replacePreviews(context, listOf(song), database, metadataRevision = 2L)
            assertEquals(name, dao.findAll(rootKey).single().audioName)
            val restored = requireNotNull(ManagedLibraryItemRoomStore.restore(context, database)).single()
            assertEquals(name, restored.localFileName)
            ManagedLibraryItemRoomStore.upsertPreview(context, restored, metadataRevision = 3L, database = database)
            assertEquals(name, dao.findAll(rootKey).single().audioName)
            assertEquals(song.stableKey, restored.stableKey)
        } finally {
            database.close()
        }
    }

    @Test
    fun optimisticDownloadKeepsStoredEntryDisplayName() = runBlocking {
        val scanned = scannedSong("optimistic.m4a")
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        val optimistic = GlobalDownloadManager.buildOptimisticDownloadedSong(
            scanned.toPlaybackSongItem(), snapshot.audioEntries.single()
        )
        assertEquals("optimistic.m4a", optimistic.localFileName)
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            ManagedLibraryItemRoomStore.upsertPreview(context, optimistic, database = database)
            assertEquals("optimistic.m4a", database.managedLibraryItemDao()
                .findAll(ManagedDownloadStorage.currentSnapshotCacheKey(context)).single().audioName)
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyUnknownContentNamePreservesKnownNameAndNeverGuessesOnInsert() = runBlocking {
        val known = scannedSong("known.m4a")
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            ManagedLibraryItemRoomStore.upsertPreview(context, known, metadataRevision = 1L, database = database)
            val unknown = known.copy(localFileName = null)
            ManagedLibraryItemRoomStore.applyPreviewDelta(context, listOf(unknown), emptySet(), database, metadataRevision = 2L)
            val dao = database.managedLibraryItemDao()
            val rootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
            assertEquals("known.m4a", dao.findAll(rootKey).single().audioName)
            ManagedLibraryItemRoomStore.clearPreviews(context, database)
            ManagedLibraryItemRoomStore.upsertPreview(context, unknown, database = database)
            assertNull(dao.findAll(rootKey).single().audioName)
        } finally {
            database.close()
        }
    }

    @Test
    fun privatePathsAndFileUrisRetainExactBasename() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            for ((reference, expected) in listOf(
                "/music/literal%20+colon:name.m4a" to "literal%20+colon:name.m4a",
                "file:///music/space%20and%2Bplus.m4a" to "space and+plus.m4a"
            )) {
                val song = DownloadedSong(42L, "title", "artist", "album", reference, 4L, 1L,
                    stableKey = "42|netease|")
                ManagedLibraryItemRoomStore.upsertPreview(context, song, database = database)
                assertEquals(expected, database.managedLibraryItemDao()
                    .findAll(ManagedDownloadStorage.currentSnapshotCacheKey(context)).single().audioName)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun changedUnknownReferenceClearsOldNamesButStaleRevisionCannotClearThem() = runBlocking {
        val known = scannedSong("known.m4a")
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            ManagedLibraryItemRoomStore.upsertPreview(context, known, metadataRevision = 10L, database = database)
            val dao = database.managedLibraryItemDao()
            val rootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
            dao.upsert(dao.findAll(rootKey).single().copy(metadataName = "known.m4a.npmeta.json"))
            val moved = known.copy(
                filePath = "content://different/document/opaque%2Funknown",
                mediaUri = "content://different/document/opaque%2Funknown",
                localFileName = null
            )
            ManagedLibraryItemRoomStore.upsertPreview(context, moved, metadataRevision = 9L, database = database)
            assertEquals("known.m4a", dao.findAll(rootKey).single().audioName)
            assertEquals("known.m4a.npmeta.json", dao.findAll(rootKey).single().metadataName)
            ManagedLibraryItemRoomStore.upsertPreview(context, moved, metadataRevision = 11L, database = database)
            val changed = dao.findAll(rootKey).single()
            assertEquals(moved.mediaUri, changed.audioReference)
            assertNull(changed.audioName)
            assertNull(changed.metadataName)
        } finally {
            database.close()
        }
    }

    private suspend fun scannedSong(displayName: String): DownloadedSong {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri))
        val audio = requireNotNull(root.createFile("audio/mp4", displayName))
        requireNotNull(context.contentResolver.openOutputStream(audio.uri, "w")).use {
            it.write(byteArrayOf(1, 2, 3, 4))
        }
        val sidecar = requireNotNull(root.createFile("application/json", "$displayName.npmeta.json"))
        requireNotNull(context.contentResolver.openOutputStream(sidecar.uri, "w")).use {
            it.write(JSONObject().apply {
                put("stableKey", "42|netease|")
                put("songId", 42L)
                put("identityAlbum", "netease")
                put("title", "Fixture title")
                put("artist", "Fixture artist")
                put("mediaUri", audio.uri.toString())
                put("downloadFinalized", true)
                put("createdAtMs", 123456L)
            }.toString().toByteArray())
        }
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        val storedAudio = snapshot.audioEntries.single { it.logicalName == displayName }
        assertNotEquals(displayName, storedAudio.reference.substringAfterLast('/'))
        val song = DownloadedSongBuilder(
            DownloadedAudioMetadataStore(1, 0L, "FileNameTest"),
            "FileNameTest"
        ).build(context, storedAudio, snapshot, allowSlowLocalInspection = false)
        assertEquals("42|netease|", song.stableKey)
        return song
    }

    private fun resetProvider() {
        context.contentResolver.call(
            treeUri,
            ManagedDownloadMigrationTestDocumentProvider.RESET,
            null,
            null
        )
    }
}
