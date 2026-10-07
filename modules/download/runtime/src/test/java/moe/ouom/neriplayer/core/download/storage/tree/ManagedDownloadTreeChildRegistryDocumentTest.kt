package moe.ouom.neriplayer.core.download.storage.tree

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class ManagedDownloadTreeChildRegistryDocumentTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)
    private val parentUri = uri("content://provider/tree/root")
    private val parent = document(parentUri)
    private val registry = ManagedDownloadTreeChildRegistry(
        writeCacheValidateIntervalMs = 60_000L,
        treeCacheValidateIntervalMs = 60_000L,
        treeWriteCacheValidateIntervalMs = 60_000L,
        onTreeQueryFailed = {}
    )

    @Test
    fun `stored entries are remembered only when their reference parses as a uri`() {
        val songReference = "content://provider/tree/root/document/song"
        val songUri = uri(songReference)

        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(songReference) }.thenReturn(songUri)
            uris.`when`<Uri> { Uri.parse("not a uri") }.thenThrow(IllegalArgumentException("bad uri"))

            registry.rememberTreeChild(parent, storedEntry(name = "Song.mp3", reference = songReference))
            registry.rememberTreeChild(parent, storedEntry(name = "Broken.mp3", reference = "not a uri"))
        }

        assertEquals(
            QueriedTreeChild(
                name = "Song.mp3",
                documentUri = songUri,
                sizeBytes = 42L,
                lastModifiedMs = 7L,
                isDirectory = false
            ),
            registry.peekTreeChildIncludingIncomplete(parent, "Song.mp3")
        )
        assertNull(registry.peekTreeChildIncludingIncomplete(parent, "Broken.mp3"))
    }

    @Test
    fun `deleted references forget file reservations and cached tree children separately`() {
        val directory = temporaryFolder.newFolder("downloads")
        assertEquals("a.mp3", registry.reserveUniqueFileChildName(directory, "a.mp3"))
        assertEquals("a (1).mp3", registry.reserveUniqueFileChildName(directory, "a.mp3"))
        val songReference = "content://provider/tree/root/document/song"
        val song = QueriedTreeChild(
            name = "Song.mp3",
            documentUri = uri(songReference),
            sizeBytes = 1L,
            lastModifiedMs = 1L,
            isDirectory = false
        )
        registry.rememberTreeChild(parent, song)

        registry.forgetDeletedReferences(emptySet())
        registry.forgetDeletedReferences(setOf("/", File(directory, "a.mp3").path))

        assertEquals(song, registry.peekTreeChildIncludingIncomplete(parent, "Song.mp3"))
        assertEquals("a.mp3", registry.reserveUniqueFileChildName(directory, "a.mp3"))

        registry.forgetDeletedReferences(setOf(songReference))

        assertNull(registry.peekTreeChildIncludingIncomplete(parent, "Song.mp3"))
    }

    @Test
    fun `tree wrappers are accepted only when they keep the child document id`() {
        val treeMatchChild = document(uri("content://provider/document/match"))
        val singleMatchChild = document(uri("content://provider/document/single"))
        val strangerChild = document(uri("content://provider/document/stranger"))
        val missingChild = document(uri("content://provider/document/missing"))
        val opaqueChild = document(uri("content://provider/document/opaque"))
        val deniedChild = document(uri("content://provider/document/denied"))
        val matchTreeUri = uri("content://provider/tree/root/document/match")
        val singleTreeUri = uri("content://provider/tree/root/document/single")
        val strangerTreeUri = uri("content://provider/tree/root/document/stranger")
        val missingTreeUri = uri("content://provider/tree/root/document/missing")
        val matchTree = document(uri("wrapper://match"))
        val rootTree = document(uri("wrapper://root"))
        val singleWrapper = document(uri("wrapper://single"))
        val strangerWrapper = document(uri("wrapper://stranger"))

        mockStatic(DocumentFile::class.java).use { documentFile ->
            mockStatic(DocumentsContract::class.java).use { documentsContract ->
                mapOf(
                    treeMatchChild.uri to "match",
                    singleMatchChild.uri to "single",
                    strangerChild.uri to "stranger",
                    missingChild.uri to "missing",
                    matchTree.uri to "match",
                    rootTree.uri to "root",
                    singleWrapper.uri to "single",
                    strangerWrapper.uri to "someone-else"
                ).forEach { (documentUri, documentId) ->
                    documentsContract.`when`<String> { DocumentsContract.getDocumentId(documentUri) }
                        .thenReturn(documentId)
                }
                val opaqueUri = opaqueChild.uri
                val deniedUri = deniedChild.uri
                documentsContract.`when`<String> { DocumentsContract.getDocumentId(opaqueUri) }
                    .thenThrow(IllegalArgumentException("opaque"))
                documentsContract.`when`<String> { DocumentsContract.getDocumentId(deniedUri) }
                    .thenThrow(SecurityException("denied"))
                mapOf(
                    "match" to matchTreeUri,
                    "single" to singleTreeUri,
                    "stranger" to strangerTreeUri,
                    "missing" to missingTreeUri
                ).forEach { (documentId, treeUri) ->
                    documentsContract.`when`<Uri> {
                        DocumentsContract.buildDocumentUriUsingTree(parentUri, documentId)
                    }.thenReturn(treeUri)
                }
                documentFile.`when`<DocumentFile?> { DocumentFile.fromTreeUri(context, matchTreeUri) }
                    .thenReturn(matchTree)
                documentFile.`when`<DocumentFile?> { DocumentFile.fromTreeUri(context, singleTreeUri) }
                    .thenReturn(rootTree)
                documentFile.`when`<DocumentFile?> { DocumentFile.fromSingleUri(context, singleTreeUri) }
                    .thenReturn(singleWrapper)
                documentFile.`when`<DocumentFile?> { DocumentFile.fromSingleUri(context, strangerTreeUri) }
                    .thenReturn(strangerWrapper)

                assertSame(matchTree, registry.toTreeDocumentFile(context, parent, treeMatchChild))
                assertSame(singleWrapper, registry.toTreeDocumentFile(context, parent, singleMatchChild))
                assertNull(registry.toTreeDocumentFile(context, parent, strangerChild))
                assertNull(registry.toTreeDocumentFile(context, parent, missingChild))
                assertNull(registry.toTreeDocumentFile(context, parent, opaqueChild))
                assertThrows(SecurityException::class.java) {
                    registry.toTreeDocumentFile(context, parent, deniedChild)
                }
            }
        }
    }

    @Test
    fun `cached children resolve directories through the tree and files through single documents`() {
        val missingUri = uri("content://provider/document/missing")
        val opaqueFileUri = uri("content://provider/document/opaque-file")
        val treeFileUri = uri("content://provider/document/tree-file")
        val directoryUri = uri("content://provider/document/directory")
        val opaqueDirectoryUri = uri("content://provider/document/opaque-directory")
        val opaqueFile = document(opaqueFileUri)
        val singleTreeFile = document(treeFileUri)
        val singleDirectory = document(directoryUri)
        val opaqueDirectory = document(opaqueDirectoryUri)
        val treeFileDocumentUri = uri("content://provider/tree/root/document/tree-file")
        val directoryDocumentUri = uri("content://provider/tree/root/document/directory")
        val treeFile = document(uri("wrapper://tree-file"))
        val treeDirectory = document(uri("wrapper://directory"))

        mockStatic(DocumentFile::class.java).use { documentFile ->
            mockStatic(DocumentsContract::class.java).use { documentsContract ->
                mapOf(
                    opaqueFileUri to opaqueFile,
                    treeFileUri to singleTreeFile,
                    directoryUri to singleDirectory,
                    opaqueDirectoryUri to opaqueDirectory
                ).forEach { (documentUri, single) ->
                    documentFile.`when`<DocumentFile?> { DocumentFile.fromSingleUri(context, documentUri) }
                        .thenReturn(single)
                }
                listOf(opaqueFileUri, opaqueDirectoryUri).forEach { documentUri ->
                    documentsContract.`when`<String> { DocumentsContract.getDocumentId(documentUri) }
                        .thenThrow(IllegalArgumentException("opaque"))
                }
                mapOf(
                    treeFileUri to "tree-file",
                    directoryUri to "directory",
                    treeFile.uri to "tree-file",
                    treeDirectory.uri to "directory"
                ).forEach { (documentUri, documentId) ->
                    documentsContract.`when`<String> { DocumentsContract.getDocumentId(documentUri) }
                        .thenReturn(documentId)
                }
                documentsContract.`when`<Uri> {
                    DocumentsContract.buildDocumentUriUsingTree(parentUri, "tree-file")
                }.thenReturn(treeFileDocumentUri)
                documentsContract.`when`<Uri> {
                    DocumentsContract.buildDocumentUriUsingTree(parentUri, "directory")
                }.thenReturn(directoryDocumentUri)
                documentFile.`when`<DocumentFile?> { DocumentFile.fromTreeUri(context, treeFileDocumentUri) }
                    .thenReturn(treeFile)
                documentFile.`when`<DocumentFile?> { DocumentFile.fromTreeUri(context, directoryDocumentUri) }
                    .thenReturn(treeDirectory)

                assertNull(registry.toDocumentFile(context, parent, child("Missing.mp3", missingUri, isDirectory = false)))
                assertSame(opaqueFile, registry.toDocumentFile(context, parent, child("Opaque.mp3", opaqueFileUri, isDirectory = false)))
                assertSame(treeFile, registry.toDocumentFile(context, parent, child("Tree.mp3", treeFileUri, isDirectory = false)))
                assertSame(treeDirectory, registry.toDocumentFile(context, parent, child("Covers", directoryUri, isDirectory = true)))
                assertNull(registry.toDocumentFile(context, parent, child("Lyrics", opaqueDirectoryUri, isDirectory = true)))
            }
        }
    }

    private fun child(name: String, documentUri: Uri, isDirectory: Boolean): QueriedTreeChild {
        return QueriedTreeChild(
            name = name,
            documentUri = documentUri,
            sizeBytes = 0L,
            lastModifiedMs = 0L,
            isDirectory = isDirectory
        )
    }

    private fun storedEntry(name: String, reference: String): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = 42L,
            lastModifiedMs = 7L
        )
    }

    private fun uri(value: String): Uri = mock(Uri::class.java).also { uri ->
        `when`(uri.toString()).thenReturn(value)
    }

    private fun document(uri: Uri): DocumentFile = mock(DocumentFile::class.java).also { document ->
        `when`(document.uri).thenReturn(uri)
    }
}
