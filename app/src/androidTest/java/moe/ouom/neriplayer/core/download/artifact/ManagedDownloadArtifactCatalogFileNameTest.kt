package moe.ouom.neriplayer.core.download.artifact

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.catalog.ManagedLibraryItemRoomStore
import moe.ouom.neriplayer.core.download.catalog.deserializeDownloadedSongsCatalog
import moe.ouom.neriplayer.core.download.catalog.serializeDownloadedSongsCatalog
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadArtifactCatalogFileNameTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val coordinator = ManagedDownloadArtifactCoordinator()

    @Test
    fun repeatedCatalogAndRoomRestoreRetainExplicitOpaqueDisplayName() = runBlocking {
        withDatabase { database, rootKey ->
            val song = song(localFileName = "literal%20+colon:name.m4a")
            val dao = database.managedDownloadArtifactDao()
            dao.upsert(artifact(rootKey, song, audioName = "opaque%2Fnode"))
            var restored = requireNotNull(deserializeDownloadedSongsCatalog(
                serializeDownloadedSongsCatalog(rootKey, listOf(song)), rootKey
            )).single()
            repeat(3) {
                coordinator.reconcileCatalog(context, listOf(restored), database)
                val stored = requireNotNull(dao.find(rootKey, STABLE_KEY))
                assertEquals(song.localFileName, stored.audioName)
                assertEquals(OPAQUE_REFERENCE, stored.audioReference)
                assertEquals(ManagedDownloadArtifactState.FINALIZED.name, stored.state)
                assertEquals("artifact-$STABLE_KEY", stored.artifactId)
                restored = requireNotNull(ManagedLibraryItemRoomStore.restore(context, database)).single()
                assertEquals(song.localFileName, restored.localFileName)
            }
        }
    }

    @Test
    fun unknownContentNameIsNeverInferredFromDocumentId() = runBlocking {
        withDatabase { database, rootKey ->
            repeat(2) {
                coordinator.reconcileCatalog(context, listOf(song()), database)
                val stored = requireNotNull(database.managedDownloadArtifactDao().find(rootKey, STABLE_KEY))
                assertNull(stored.audioName)
                assertEquals(OPAQUE_REFERENCE, stored.audioReference)
            }
        }
    }

    @Test
    fun unknownNameAtSameReferencePreservesKnownArtifactNames() = runBlocking {
        withDatabase { database, rootKey ->
            val song = song()
            val original = artifact(rootKey, song, audioName = "known.m4a")
                .copy(metadataName = "known.m4a.npmeta.json")
            val dao = database.managedDownloadArtifactDao()
            dao.upsert(original)
            coordinator.reconcileCatalog(context, listOf(song), database)
            val stored = requireNotNull(dao.find(rootKey, STABLE_KEY))
            assertEquals(original.audioName, stored.audioName)
            assertEquals(original.metadataName, stored.metadataName)
            assertEquals(original.audioReference, stored.audioReference)
        }
    }

    @Test
    fun changedUnknownReferenceClearsOldAudioAndMetadataNames() = runBlocking {
        withDatabase { database, rootKey ->
            val original = artifact(rootKey, song(), audioName = "old.m4a")
                .copy(metadataName = "old.m4a.npmeta.json")
            val dao = database.managedDownloadArtifactDao()
            dao.upsert(original)
            val moved = song(reference = "content://provider/document/new%2Fnode")
            coordinator.reconcileCatalog(context, listOf(moved), database)
            val stored = requireNotNull(dao.find(rootKey, STABLE_KEY))
            assertEquals(moved.mediaUri, stored.audioReference)
            assertNull(stored.audioName)
            assertNull(stored.metadataName)
            assertEquals(original.artifactId, stored.artifactId)
            assertEquals(original.state, stored.state)
        }
    }

    @Test
    fun privatePathsAndFileUrisPreserveTheirRealBasenames() = runBlocking {
        withDatabase { database, rootKey ->
            val songs = listOf(
                song(reference = "/music/literal%20+colon:name.m4a")
                    .copy(stableKey = "private", mediaUri = null),
                song(reference = "file:///music/space%20and%2Bliteral%2520.m4a")
                    .copy(stableKey = "file-uri")
            )
            coordinator.reconcileCatalog(context, songs, database)
            val dao = database.managedDownloadArtifactDao()
            assertEquals("literal%20+colon:name.m4a", dao.find(rootKey, "private")?.audioName)
            assertEquals("space and+literal%20.m4a", dao.find(rootKey, "file-uri")?.audioName)
        }
    }

    @Test
    fun activeMediaUriAndFilenameUseTheSameReference() = runBlocking {
        withDatabase { database, rootKey ->
            val song = song(localFileName = "current.m4a").copy(filePath = "/old/obsolete.m4a")
            coordinator.reconcileCatalog(context, listOf(song), database)
            val dao = database.managedDownloadArtifactDao()
            assertEquals(OPAQUE_REFERENCE, dao.find(rootKey, STABLE_KEY)?.audioReference)
            assertEquals("current.m4a", dao.find(rootKey, STABLE_KEY)?.audioName)
            coordinator.reconcileCatalog(context, listOf(song.copy(localFileName = null)), database)
            assertEquals("current.m4a", dao.find(rootKey, STABLE_KEY)?.audioName)
        }
    }

    @Test
    fun catalogRestoreCannotChangeActiveLeaseOrCoreArtifact() = runBlocking {
        withDatabase { database, rootKey ->
            val dao = database.managedDownloadArtifactDao()
            for (state in listOf(ManagedDownloadArtifactState.DOWNLOADING, ManagedDownloadArtifactState.CORE_COMMITTED)) {
                val original = artifact(rootKey, song(), audioName = "pending.m4a").copy(
                    state = state.name, leaseId = "owned-lease", needsReconcile = true
                )
                dao.upsert(original)
                coordinator.reconcileCatalog(context, listOf(song(localFileName = "catalog.m4a")), database)
                assertEquals(original, dao.find(rootKey, STABLE_KEY))
            }
        }
    }

    private suspend fun withDatabase(block: suspend (NeriUserDataDatabase, String) -> Unit) {
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
            try {
                block(database, ManagedDownloadStorage.currentSnapshotRootKey(context))
            } finally {
                database.close()
            }
        }
    }

    private fun song(reference: String = OPAQUE_REFERENCE, localFileName: String? = null) = DownloadedSong(
        id = 42L, name = "title", artist = "artist", album = "netease",
        filePath = reference, mediaUri = reference, localFileName = localFileName,
        fileSize = 4L, downloadTime = 1L, stableKey = STABLE_KEY
    )

    private fun artifact(rootKey: String, song: DownloadedSong, audioName: String) = ManagedDownloadArtifactEntity(
        rootKey = rootKey, stableKey = STABLE_KEY, artifactId = "artifact-$STABLE_KEY",
        state = ManagedDownloadArtifactState.FINALIZED.name,
        audioReference = song.mediaUri, audioName = audioName, fileSize = song.fileSize,
        updatedAtMs = 1L, needsReconcile = false
    )

    private companion object {
        const val OPAQUE_REFERENCE = "content://provider/document/opaque%2Fnode"
        const val STABLE_KEY = "42|netease|"
    }
}
