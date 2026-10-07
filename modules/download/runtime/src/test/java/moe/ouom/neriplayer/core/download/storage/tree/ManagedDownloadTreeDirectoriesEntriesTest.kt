package moe.ouom.neriplayer.core.download.storage.tree

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.data.model.download.storage.StorageMutationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.MockedStatic
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class ManagedDownloadTreeDirectoriesEntriesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)
    private val registry = ManagedDownloadTreeChildRegistry(
        writeCacheValidateIntervalMs = 60_000L,
        treeCacheValidateIntervalMs = 60_000L,
        treeWriteCacheValidateIntervalMs = 60_000L,
        onTreeQueryFailed = {}
    )
    private val directories = ManagedDownloadTreeDirectories(
        treeChildRegistry = registry,
        tag = "test",
        deleteTrustedReference = { _, _ -> StorageMutationResult.Deleted }
    )

    @Test
    fun `file roots list root entries and managed sidecar directories`() {
        val rootDirectory = temporaryFolder.newFolder("root")
        val song = File(rootDirectory, "Song.mp3").apply { writeText("audio") }
        val covers = File(rootDirectory, "Covers").apply { mkdir() }
        val numberedCovers = File(rootDirectory, "Covers (1)").apply { mkdir() }
        val lyrics = File(rootDirectory, "Lyrics").apply { mkdir() }
        File(covers, "Song.jpg").writeText("jpg")
        File(covers, "nested").mkdir()
        File(numberedCovers, "Other.jpg").writeText("jpg")
        File(lyrics, "Song.lrc").writeText("lrc")
        val root = ManagedDownloadRootHandle.FileRoot(rootDirectory)
        val missingRoot = ManagedDownloadRootHandle.FileRoot(File(rootDirectory, "missing"))
        val rootNames = setOf("Song.mp3", "Covers", "Covers (1)", "Lyrics")

        val rootRefresh = directories.refreshRootEntries(context, root)
        assertTrue(rootRefresh.isComplete)
        assertEquals(rootNames, rootRefresh.entries.names().toSet())
        val songEntry = rootRefresh.entries.single { it.name == "Song.mp3" }
        assertEquals(song.absolutePath, songEntry.reference)
        assertEquals(song.absolutePath, songEntry.localFilePath)
        assertEquals(5L, songEntry.sizeBytes)
        assertEquals(rootNames, directories.cachedRootEntries(root)?.entries?.names()?.toSet())
        assertEquals(rootNames, directories.listChildren(context, root).names().toSet())

        val libraryRefresh = directories.refreshDownloadLibraryEntries(context, root)
        assertEquals(rootNames, libraryRefresh.rootEntries.names().toSet())
        assertEquals(listOf("Song.jpg", "Other.jpg"), libraryRefresh.coverEntries.names())
        assertEquals(listOf("Song.lrc"), libraryRefresh.lyricEntries.names())
        assertTrue(libraryRefresh.rootEntriesComplete)
        assertTrue(libraryRefresh.sidecarEntriesComplete)

        val migrationRefresh = directories.refreshManagedMigrationEntries(context, root)
        assertEquals(setOf("Song.jpg", "Other.jpg"), migrationRefresh.coverEntries.names().toSet())
        assertEquals(listOf("Song.lrc"), migrationRefresh.lyricEntries.names())
        assertTrue(migrationRefresh.isComplete)
        val rootOnlyMigration = directories.refreshManagedMigrationEntries(
            context = context,
            root = root,
            requiresSidecarEntries = { false }
        )
        assertEquals(rootNames, rootOnlyMigration.rootEntries.names().toSet())
        assertEquals(emptyList<String>(), rootOnlyMigration.coverEntries.names() + rootOnlyMigration.lyricEntries.names())
        assertTrue(rootOnlyMigration.isComplete)

        val coverRefresh = directories.refreshSubdirectoryEntries(context, root, "Covers")
        assertEquals(listOf("Song.jpg", "Other.jpg"), coverRefresh.entries.names())
        assertTrue(coverRefresh.isComplete)
        assertEquals(
            listOf(
                ManagedDownloadRootHandle.FileRoot(covers),
                ManagedDownloadRootHandle.FileRoot(numberedCovers)
            ),
            directories.findSubdirectories(context, root, "Covers")
        )
        assertEquals(
            listOf(
                ManagedDownloadRootHandle.FileRoot(numberedCovers),
                ManagedDownloadRootHandle.FileRoot(covers)
            ),
            directories.findSubdirectories(context, root, "Covers", canonicalLast = true)
        )
        assertEquals(
            listOf("Other.jpg", "Song.jpg"),
            directories.listSubdirectoryEntries(context, root, "Covers").names()
        )

        assertEquals(
            ManagedDownloadTreeDirectories.RootEntriesRefresh(emptyList(), isComplete = false),
            directories.refreshRootEntries(context, missingRoot)
        )
        assertNull(directories.cachedRootEntries(missingRoot))
        assertEquals(emptyList<ManagedDownloadStorage.StoredEntry>(), directories.listChildren(context, missingRoot))
        assertEquals(
            ManagedDownloadTreeDirectories.DownloadLibraryEntriesRefresh(
                rootEntries = emptyList(),
                coverEntries = emptyList(),
                lyricEntries = emptyList(),
                rootEntriesComplete = false,
                sidecarEntriesComplete = false
            ),
            directories.refreshDownloadLibraryEntries(context, missingRoot)
        )
        assertEquals(
            ManagedDownloadTreeDirectories.ManagedMigrationEntriesRefresh(
                rootEntries = emptyList(),
                coverEntries = emptyList(),
                lyricEntries = emptyList(),
                isComplete = false
            ),
            directories.refreshManagedMigrationEntries(context, missingRoot)
        )
        assertEquals(
            ManagedDownloadTreeDirectories.SubdirectoryEntriesRefresh(emptyList(), isComplete = false),
            directories.refreshSubdirectoryEntries(context, missingRoot, "Covers")
        )
        assertEquals(emptyList<ManagedDownloadRootHandle>(), directories.findSubdirectories(context, missingRoot, "Covers"))
    }

    @Test
    fun `tree roots report fallback listings as incomplete and skip sidecars that cannot be opened`() {
        val parentUri = uri("content://provider/tree/library")
        val coversUri = uri("content://provider/tree/library/document/covers")
        val lyricsUri = uri("content://provider/tree/library/document/lyrics")
        val songUri = uri("content://provider/tree/library/document/song")
        val coverUri = uri("content://provider/tree/library/document/cover")
        val coversTreeUri = uri("content://provider/tree/library/document/covers-tree")
        val coversDirectoryUri = uri("content://provider/tree/library/document/covers-directory")
        val coverDocuments = arrayOf(
            listedDocument(coverUri, "Song.jpg", isDirectory = false, length = 3L, lastModified = 4L)
        )
        val coversDirectory = listedDocument(coversDirectoryUri, "Covers", isDirectory = true).also { directory ->
            `when`(directory.listFiles()).thenReturn(coverDocuments)
        }
        val rootDocuments = arrayOf(
            listedDocument(coversUri, "Covers", isDirectory = true),
            listedDocument(lyricsUri, "Lyrics", isDirectory = true),
            listedDocument(songUri, "Song.mp3", isDirectory = false, length = 5L, lastModified = 9L)
        )
        val parent = listedDocument(parentUri, "library", isDirectory = true).also { directory ->
            `when`(directory.listFiles()).thenReturn(rootDocuments)
        }
        val root = ManagedDownloadRootHandle.TreeRoot(parent)
        val rootEntries = listOf(
            treeEntry("Covers", coversUri, sizeBytes = 0L, lastModifiedMs = 0L, isDirectory = true),
            treeEntry("Lyrics", lyricsUri, sizeBytes = 0L, lastModifiedMs = 0L, isDirectory = true),
            treeEntry("Song.mp3", songUri, sizeBytes = 5L, lastModifiedMs = 9L, isDirectory = false)
        )
        val coverEntries = listOf(
            treeEntry("Song.jpg", coverUri, sizeBytes = 3L, lastModifiedMs = 4L, isDirectory = false)
        )

        mockStatic(DocumentFile::class.java).use { documentFile ->
            mockStatic(DocumentsContract::class.java).use { documentsContract ->
                stubTreeDirectory(
                    documentFile = documentFile,
                    documentsContract = documentsContract,
                    parentUri = parentUri,
                    childUri = coversUri,
                    treeUri = coversTreeUri,
                    documentId = "covers",
                    directory = coversDirectory
                )

                assertEquals(
                    ManagedDownloadTreeDirectories.RootEntriesRefresh(rootEntries, isComplete = false),
                    directories.refreshRootEntries(context, root)
                )
                assertEquals(rootEntries, directories.listChildren(context, root))
                assertNull(directories.cachedRootEntries(root))
                assertEquals(
                    ManagedDownloadTreeDirectories.DownloadLibraryEntriesRefresh(
                        rootEntries = rootEntries,
                        coverEntries = coverEntries,
                        lyricEntries = emptyList(),
                        rootEntriesComplete = false,
                        sidecarEntriesComplete = false
                    ),
                    directories.refreshDownloadLibraryEntries(context, root)
                )
                assertEquals(
                    ManagedDownloadTreeDirectories.ManagedMigrationEntriesRefresh(
                        rootEntries = rootEntries,
                        coverEntries = coverEntries,
                        lyricEntries = emptyList(),
                        isComplete = false
                    ),
                    directories.refreshManagedMigrationEntries(context, root)
                )
                assertEquals(
                    ManagedDownloadTreeDirectories.SubdirectoryEntriesRefresh(coverEntries, isComplete = false),
                    directories.refreshSubdirectoryEntries(context, root, "Covers")
                )
                assertEquals(
                    ManagedDownloadTreeDirectories.SubdirectoryEntriesRefresh(emptyList(), isComplete = false),
                    directories.refreshSubdirectoryEntries(context, root, "Lyrics")
                )
                assertEquals(
                    listOf(ManagedDownloadRootHandle.TreeRoot(coversDirectory)),
                    directories.findSubdirectories(context, root, "Covers")
                )
                assertEquals(
                    emptyList<ManagedDownloadRootHandle>(),
                    directories.findSubdirectories(context, root, "Lyrics")
                )
            }
        }
    }

    @Test
    fun `known subdirectories are cached until their reference is deleted`() {
        val exactParent = listedDocument(uri("content://provider/tree/exact"), "exact", isDirectory = true)
        val numberedParent = listedDocument(uri("content://provider/tree/numbered"), "numbered", isDirectory = true)
        val unknownParent = listedDocument(uri("content://provider/tree/unknown"), "unknown", isDirectory = true)
        val unopenableParent = listedDocument(uri("content://provider/tree/unopenable"), "unopenable", isDirectory = true)
        listOf(exactParent, numberedParent, unknownParent, unopenableParent).forEach { parent ->
            `when`(parent.listFiles()).thenReturn(emptyArray())
        }
        val exactCoversUri = uri("content://provider/tree/exact/document/covers")
        val numberedCoversUri = uri("content://provider/tree/numbered/document/covers-1")
        val exactCovers = listedDocument(uri("wrapper://exact-covers"), "Covers", isDirectory = true)
        val numberedCovers = listedDocument(uri("wrapper://numbered-covers"), "Covers (1)", isDirectory = true)
        rememberComplete(exactParent, child("Covers", exactCoversUri, isDirectory = true))
        rememberComplete(
            numberedParent,
            child("Covers", uri("content://provider/tree/numbered/document/covers-file"), isDirectory = false),
            child("Covers (1)", numberedCoversUri, isDirectory = true)
        )
        rememberComplete(
            unopenableParent,
            child("Covers", uri("content://provider/tree/unopenable/document/covers"), isDirectory = true)
        )

        mockStatic(DocumentFile::class.java).use { documentFile ->
            mockStatic(DocumentsContract::class.java).use { documentsContract ->
                stubTreeDirectory(
                    documentFile = documentFile,
                    documentsContract = documentsContract,
                    parentUri = exactParent.uri,
                    childUri = exactCoversUri,
                    treeUri = uri("content://provider/tree/exact/document/covers-tree"),
                    documentId = "exact-covers",
                    directory = exactCovers
                )
                stubTreeDirectory(
                    documentFile = documentFile,
                    documentsContract = documentsContract,
                    parentUri = numberedParent.uri,
                    childUri = numberedCoversUri,
                    treeUri = uri("content://provider/tree/numbered/document/covers-1-tree"),
                    documentId = "numbered-covers",
                    directory = numberedCovers
                )

                assertSame(exactCovers, directories.findOrCreateDirectory(context, exactParent, "Covers"))
                assertSame(numberedCovers, directories.findOrCreateDirectory(context, numberedParent, "Covers"))
                assertNull(directories.findOrCreateDirectory(context, unknownParent, "Covers"))
                assertNull(directories.findOrCreateDirectory(context, unopenableParent, "Covers"))

                registry.forgetTreeChildName(exactParent, "Covers")
                assertSame(exactCovers, directories.findOrCreateDirectory(context, exactParent, "Covers"))
                verify(exactParent, times(0)).createDirectory("Covers")

                directories.forgetDeletedReferences(setOf(exactCovers.uri.toString()))
                assertNull(directories.findOrCreateDirectory(context, exactParent, "Covers"))
                verify(exactParent).createDirectory("Covers")
            }
        }
    }

    private fun stubTreeDirectory(
        documentFile: MockedStatic<DocumentFile>,
        documentsContract: MockedStatic<DocumentsContract>,
        parentUri: Uri,
        childUri: Uri,
        treeUri: Uri,
        documentId: String,
        directory: DocumentFile
    ) {
        val single = listedDocument(childUri, "single", isDirectory = true)
        val directoryUri = directory.uri
        documentFile.`when`<DocumentFile?> { DocumentFile.fromSingleUri(context, childUri) }.thenReturn(single)
        documentsContract.`when`<String> { DocumentsContract.getDocumentId(childUri) }.thenReturn(documentId)
        documentsContract.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(parentUri, documentId) }
            .thenReturn(treeUri)
        documentFile.`when`<DocumentFile?> { DocumentFile.fromTreeUri(context, treeUri) }.thenReturn(directory)
        documentsContract.`when`<String> { DocumentsContract.getDocumentId(directoryUri) }.thenReturn(documentId)
    }

    private fun rememberComplete(parent: DocumentFile, vararg children: QueriedTreeChild) {
        registry.rememberTreeChildren(
            parent = parent,
            children = children.toList(),
            refreshedAtMs = System.currentTimeMillis(),
            isComplete = true
        )
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

    private fun treeEntry(
        name: String,
        documentUri: Uri,
        sizeBytes: Long,
        lastModifiedMs: Long,
        isDirectory: Boolean
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = documentUri.toString(),
            mediaUri = documentUri.toString(),
            localFilePath = null,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            isDirectory = isDirectory
        )
    }

    private fun listedDocument(
        uri: Uri,
        name: String,
        isDirectory: Boolean,
        length: Long = 0L,
        lastModified: Long = 0L
    ): DocumentFile = mock(DocumentFile::class.java).also { document ->
        `when`(document.uri).thenReturn(uri)
        `when`(document.name).thenReturn(name)
        `when`(document.isDirectory).thenReturn(isDirectory)
        `when`(document.length()).thenReturn(length)
        `when`(document.lastModified()).thenReturn(lastModified)
    }

    private fun uri(value: String): Uri = mock(Uri::class.java).also { uri ->
        `when`(uri.toString()).thenReturn(value)
    }

    private fun List<ManagedDownloadStorage.StoredEntry>.names(): List<String> = map { it.name }
}
