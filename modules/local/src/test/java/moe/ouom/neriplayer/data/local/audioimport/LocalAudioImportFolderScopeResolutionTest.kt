package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalAudioImportFolderScopeResolutionTest {
    private val context: Context = mock(Context::class.java)

    @Test
    fun `primary volume tree folders map to the primary media store relative path`() {
        val folder = documentUri(EXTERNAL_STORAGE_AUTHORITY)

        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<String> { DocumentsContract.getTreeDocumentId(folder) }
                .thenReturn("primary: /Music/Albums/ ")
            mockStatic(MediaStore::class.java).use { mediaStore ->
                mediaStore.`when`<Set<String>> { MediaStore.getExternalVolumeNames(context) }
                    .thenReturn(setOf("external_primary"))

                assertEquals(
                    ExternalStorageFolderMediaStoreScope(volumeName = "external_primary", relativePath = "Music/Albums/"),
                    LocalAudioImportManager.resolveExternalStorageFolderMediaStoreScope(context, folder)
                )
            }
        }
    }

    @Test
    fun `plain document folders on removable volumes use the known volume name`() {
        val folder = documentUri(EXTERNAL_STORAGE_AUTHORITY)

        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<String> { DocumentsContract.getTreeDocumentId(folder) }
                .thenThrow(IllegalArgumentException("not a tree uri"))
            documents.`when`<String> { DocumentsContract.getDocumentId(folder) }
                .thenReturn("1A2B-3C4D:Podcasts")
            mockStatic(MediaStore::class.java).use { mediaStore ->
                mediaStore.`when`<Set<String>> { MediaStore.getExternalVolumeNames(context) }
                    .thenReturn(setOf("external_primary", "1a2b-3c4d"))

                assertEquals(
                    ExternalStorageFolderMediaStoreScope(volumeName = "1a2b-3c4d", relativePath = "Podcasts/"),
                    LocalAudioImportManager.resolveExternalStorageFolderMediaStoreScope(context, folder)
                )
            }
        }
    }

    @Test
    fun `folders without a resolvable external storage document id have no scope`() {
        val foreign = documentUri("com.android.providers.downloads.documents")
        val unresolvable = documentUri(EXTERNAL_STORAGE_AUTHORITY)
        val missingId = documentUri(EXTERNAL_STORAGE_AUTHORITY)

        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<String> { DocumentsContract.getTreeDocumentId(unresolvable) }
                .thenThrow(IllegalArgumentException("not a tree uri"))
            documents.`when`<String> { DocumentsContract.getDocumentId(unresolvable) }
                .thenThrow(IllegalArgumentException("not a document uri"))
            documents.`when`<String> { DocumentsContract.getTreeDocumentId(missingId) }
                .thenReturn(null)

            assertNull(LocalAudioImportManager.resolveExternalStorageFolderMediaStoreScope(context, foreign))
            assertNull(LocalAudioImportManager.resolveExternalStorageFolderMediaStoreScope(context, unresolvable))
            assertNull(LocalAudioImportManager.resolveExternalStorageFolderMediaStoreScope(context, missingId))
            documents.verify { DocumentsContract.getTreeDocumentId(unresolvable) }
            documents.verify { DocumentsContract.getDocumentId(unresolvable) }
        }
    }

    private fun documentUri(authority: String): Uri = mock(Uri::class.java).also { uri ->
        doReturn(authority).`when`(uri).authority
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
