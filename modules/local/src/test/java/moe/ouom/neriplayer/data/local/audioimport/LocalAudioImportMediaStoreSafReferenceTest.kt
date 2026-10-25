package moe.ouom.neriplayer.data.local.audioimport

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.testing.FakeLibrarySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalAudioImportMediaStoreSafReferenceTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `folder grants keep indexed SAF audio even when data paths exist and MediaStore opens are denied`() = runBlocking {
        assertAuthorizedReferences(songCount = 3)
    }

    @Test
    fun `large folder scans reuse known empty sidecar indexes for complete non MP3 identities`() = runBlocking {
        assertAuthorizedReferences(songCount = 257, requireBoundedProviderQueries = true)
    }

    @Test
    fun `large folder scans still probe missing identity metadata`() = runBlocking {
        assertAuthorizedReferences(songCount = 257, metadataComplete = false)
    }

    private suspend fun assertAuthorizedReferences(
        songCount: Int,
        requireBoundedProviderQueries: Boolean = false,
        metadataComplete: Boolean = true
    ) {
        val resolver = mock(ContentResolver::class.java)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
            override fun getCacheDir(): File = directory.root
        }
        val downloads = mock(LocalMediaDownloadAccess::class.java)
        doReturn(FakeLibrarySnapshot()).`when`(downloads).cachedDownloadLibrarySnapshot(context, true)
        doReturn(emptyList<String>()).`when`(downloads).candidateFileNameTemplates(null)
        LocalMediaHostAccess.bind(downloads, mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { false })

        val authority = "com.android.externalstorage.documents"
        val folderId = "primary:Music/SafOnly"
        val folder = DocumentsContract.buildTreeDocumentUri(authority, folderId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, folderId)
        val files = (1..songCount).map { directory.newFile("song-$it.flac").apply { writeBytes(byteArrayOf(1)) } }
        val treeReferences = files.map { file ->
            DocumentsContract.buildDocumentUriUsingTree(folder, "$folderId/${file.name}").toString()
        }
        val directoryQueries = AtomicInteger()
        val individualDocumentQueries = AtomicInteger()
        val mediaStoreOpenAttempts = AtomicInteger()
        doAnswer { invocation ->
            val projection = invocation.getArgument<Array<String>>(1)
            MatrixCursor(projection).apply {
                files.forEachIndexed { index, file ->
                    addRow(projection.map { column ->
                        when (column) {
                            MediaStore.Audio.Media._ID -> index + 1L
                            MediaStore.Audio.Media.TITLE -> "Song ${index + 1}"
                            MediaStore.Audio.Media.ARTIST -> "Artist"
                            MediaStore.Audio.Media.ALBUM -> "Named album".takeIf { metadataComplete }
                            MediaStore.Audio.Media.DURATION -> 180_000L
                            MediaStore.MediaColumns.DISPLAY_NAME -> file.name
                            MediaStore.MediaColumns.RELATIVE_PATH -> "Music/SafOnly/"
                            "_data" -> file.absolutePath
                            else -> null
                        }
                    })
                }
            }
        }.`when`(resolver).query(any(), any(), any(), any(), any(), any())
        doAnswer { invocation ->
            val uri = invocation.getArgument<Uri>(0)
            val projection = invocation.getArgument<Array<String>>(1)
            MatrixCursor(projection).apply {
                if (uri == children) {
                    directoryQueries.incrementAndGet()
                    files.forEach { file ->
                        addRow(projection.map { column ->
                            when (column) {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "$folderId/${file.name}"
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                                DocumentsContract.Document.COLUMN_MIME_TYPE -> "audio/flac"
                                else -> null
                            }
                        })
                    }
                } else if (uri.authority == authority && uri.toString().contains("/document/")) {
                    individualDocumentQueries.incrementAndGet()
                }
            }
        }.`when`(resolver).query(any(), any(), any(), any(), any())
        doAnswer {
            mediaStoreOpenAttempts.incrementAndGet()
            throw SecurityException("READ_MEDIA_AUDIO is not granted")
        }.`when`(resolver).openAssetFileDescriptor(any(), eq("r"))
        doThrow(SecurityException("READ_MEDIA_AUDIO is not granted"))
            .`when`(resolver).openFileDescriptor(any(), eq("r"))

        mockStatic(MediaStore::class.java).use { mediaStore ->
            mediaStore.`when`<Set<String>> { MediaStore.getExternalVolumeNames(context) }
                .thenReturn(setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY))

            val result = requireNotNull(
                LocalAudioImportManager.scanExternalStorageFolderWithMediaStore(
                    context,
                    folder,
                    LocalAudioScanProgressEmitter(scanId = 1L, startedAt = 0L) {}
                )
            )

            assertEquals(treeReferences.toSet(), result.songs.map { it.mediaUri }.toSet())
            assertEquals(files.map(File::getAbsolutePath).toSet(), result.songs.map { it.localFilePath }.toSet())
            val mediaStoreCollection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val expectedIds = files.mapIndexed { index, file ->
                file.name to LocalAudioImportManager.computeStableSongId(
                    Uri.withAppendedPath(mediaStoreCollection, (index + 1).toString()).toString()
                )
            }.toMap()
            assertEquals(expectedIds, result.songs.associate { it.localFileName to it.id })
            assertTrue(result.songs.all { it.channelId == "local" })
            assertTrue(mediaStoreOpenAttempts.get() > 0)
            if (requireBoundedProviderQueries) {
                assertEquals(
                    "the folder index confirms absent sidecars; directory queries=${directoryQueries.get()}",
                    0,
                    individualDocumentQueries.get()
                )
                assertTrue("directory queries stay bounded", directoryQueries.get() <= 3)
            }
            if (!metadataComplete) {
                assertTrue("missing identity still needs metadata probing", individualDocumentQueries.get() > 0)
            }
        }
    }
}
