package moe.ouom.neriplayer.core.download.storage.operation.content

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationNamePlan
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class ManagedDownloadStorageMetadataHelpersTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val storage = ManagedDownloadStorage
    private val context = mock(Context::class.java)

    @Test
    fun `migration target index prefers canonical metadata and can skip parsing`() {
        val rootDir = temporaryFolder.newFolder("target")
        File(rootDir, "Song.mp3").writeText("audio")
        File(rootDir, "Song.mp3.npmeta.json").writeText("""{"stableKey":"song-canonical"}""")
        File(rootDir, "Song.mp3.npmeta.pending.json").writeText("""{"stableKey":"song-pending"}""")
        File(rootDir, "Other.mp3.npmeta.pending.json").writeText("""{"stableKey":"other-pending"}""")
        File(rootDir, "Broken.mp3.npmeta.json").writeText("{broken")
        File(rootDir, "Covers").mkdir()
        File(rootDir, "Covers/Song.jpg").writeText("cover")
        File(rootDir, "Lyrics").mkdir()
        File(rootDir, "Lyrics/Song.lrc").writeText("lyric")
        val targetRoot = ManagedDownloadRootHandle.FileRoot(rootDir)

        val index = storage.buildMigrationTargetIndex(context, targetRoot)

        assertEquals(
            mapOf("Song.mp3" to "song-canonical", "Other.mp3" to "other-pending"),
            index.metadataByAudioName.mapValues { (_, metadata) -> metadata.stableKey }
        )
        assertEquals(setOf("Song.jpg"), index.coverEntriesByName.keys)
        assertEquals(setOf("Song.lrc"), index.lyricEntriesByName.keys)
        assertTrue(index.rootEntriesByName.keys.containsAll(listOf("Song.mp3", "Song.mp3.npmeta.json")))

        val unparsed = storage.buildMigrationTargetIndex(context, targetRoot, skipMetadataParsing = true)
        assertTrue(unparsed.metadataByAudioName.isEmpty())
        assertEquals(index.rootEntriesByName.keys, unparsed.rootEntriesByName.keys)
    }

    @Test
    fun `persisted replacement journals must belong to the same migration directories`() {
        val from = "content://com.android.externalstorage.documents/tree/primary%3AOld"
        val to = "content://com.android.externalstorage.documents/tree/primary%3ANew"
        val elsewhere = "content://com.android.externalstorage.documents/tree/primary%3AElsewhere"
        val plan = ManagedMigrationNamePlan(targetNamesByReference = mapOf("/source/Song.mp3" to "Song.mp3"))

        assertSame(plan, storage.mergePersistedReplacementPlan(from, to, plan, persistedJournal = null))
        assertEquals(plan, storage.mergePersistedReplacementPlan(from, to, plan, journal(from, to)))
        val movedSource = assertThrows(ManagedDownloadMigrationException::class.java) {
            storage.mergePersistedReplacementPlan(from, to, plan, journal(elsewhere, to))
        }
        assertTrue(movedSource.retryable)
        assertThrows(ManagedDownloadMigrationException::class.java) {
            storage.mergePersistedReplacementPlan(from, to, plan, journal(from, elsewhere))
        }
    }

    @Test
    fun `tree promotion documents resolve through the parent tree when the provider allows it`() {
        val parentUri = mock(Uri::class.java)
        val parent = mock(DocumentFile::class.java)
        `when`(parent.uri).thenReturn(parentUri)
        val singleUri = mock(Uri::class.java)
        val direct = mock(DocumentFile::class.java)
        `when`(direct.uri).thenReturn(singleUri)
        val treeUri = mock(Uri::class.java)
        val treeDocument = mock(DocumentFile::class.java)
        `when`(treeDocument.uri).thenReturn(treeUri)
        val missingUri = mock(Uri::class.java)

        mockStatic(DocumentFile::class.java).use { documentFile ->
            mockStatic(DocumentsContract::class.java).use { documentsContract ->
                documentFile.`when`<DocumentFile?> { DocumentFile.fromSingleUri(context, singleUri) }.thenReturn(direct)
                documentsContract.`when`<String> { DocumentsContract.getDocumentId(singleUri) }
                    .thenThrow(IllegalArgumentException("not a tree document"))
                assertSame(direct, storage.resolvePendingTreeDocument(context, parent, singleUri))
                assertSame(direct, storage.resolveNewTreePromotionDocument(context, parent, singleUri))

                documentsContract.`when`<String> { DocumentsContract.getDocumentId(singleUri) }
                    .thenReturn("primary:Music/Song.mp3")
                documentsContract.`when`<Uri> {
                    DocumentsContract.buildDocumentUriUsingTree(parentUri, "primary:Music/Song.mp3")
                }.thenReturn(treeUri)
                documentsContract.`when`<String> { DocumentsContract.getDocumentId(treeUri) }
                    .thenReturn("primary:Music/Song.mp3")
                documentFile.`when`<DocumentFile?> { DocumentFile.fromTreeUri(context, treeUri) }.thenReturn(treeDocument)
                assertSame(treeDocument, storage.resolvePendingTreeDocument(context, parent, singleUri))
                assertSame(treeDocument, storage.resolveNewTreePromotionDocument(context, parent, singleUri))

                assertNull(storage.resolvePendingTreeDocument(context, parent, missingUri))
                assertNull(storage.resolveNewTreePromotionDocument(context, parent, missingUri))
            }
        }
    }

    private fun journal(fromDirectoryUri: String?, toDirectoryUri: String?) = ManagedMigrationReplacementJournal(
        workId = "work-1",
        fromDirectoryUri = fromDirectoryUri,
        toDirectoryUri = toDirectoryUri,
        backupNamespace = "backup-1",
        phase = ManagedMigrationReplacementJournalPhase.PLANNED,
        replacements = emptyList()
    )
}
