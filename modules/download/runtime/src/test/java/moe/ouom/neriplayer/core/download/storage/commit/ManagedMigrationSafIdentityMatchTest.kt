package moe.ouom.neriplayer.core.download.storage.commit

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedMigrationSafIdentityMatchTest {
    private val documentId = "primary%3AMusic%2FNeriPlayer%2Fsong.flac"

    @Test
    fun `tree and document uris of one saf document share a migration identity`() {
        val treeDocument = entry(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/$documentId"
        )
        val plainDocument = entry("CONTENT://COM.ANDROID.EXTERNALSTORAGE.DOCUMENTS/document/$documentId?mode=r")

        assertTrue(sameManagedMigrationStoredEntryIdentity(treeDocument, plainDocument))
    }

    @Test
    fun `other providers, plain files and uris without an authority never match`() {
        val document = entry("content://com.android.externalstorage.documents/document/$documentId")

        listOf(
            "content://com.android.providers.downloads.documents/document/$documentId",
            "content:///document/$documentId",
            "content://com.android.externalstorage.documents",
            "file:///storage/emulated/0/Music/NeriPlayer/song.flac",
            "/storage/emulated/0/Music/NeriPlayer/song.flac",
            "://com.android.externalstorage.documents/document/$documentId"
        ).forEach { reference ->
            assertFalse(reference, sameManagedMigrationStoredEntryIdentity(document, entry(reference)))
        }
    }

    private fun entry(reference: String) = ManagedDownloadStorage.StoredEntry(
        name = "song.flac",
        reference = reference,
        mediaUri = reference,
        localFilePath = null,
        sizeBytes = 10L,
        lastModifiedMs = 1L
    )
}
