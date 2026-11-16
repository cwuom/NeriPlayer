package moe.ouom.neriplayer.data.local.media.curation

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.metadata.editableLocalMediaUriCandidates
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalMediaWriteAccessPlanTest {

    @Test
    fun `nothing pending needs no system consent`() {
        val plan = systemConsentPlan(emptyList()) { error("must not create a request") }

        assertEquals(LocalMediaWriteAccessPlan.Ready, plan)
    }

    @Test
    fun `large batches are split into requests the system accepts`() {
        val uris = (1..2_500).map { Uri.parse("content://media/external/audio/media/$it") }
        val batchSizes = mutableListOf<Int>()

        val plan = systemConsentPlan(uris) { batch ->
            batchSizes += batch.size
            mock(PendingIntent::class.java)
        }

        assertEquals(listOf(1_000, 1_000, 500), batchSizes)
        val consent = plan as LocalMediaWriteAccessPlan.SystemConsent
        assertEquals(3, consent.requests.size)
        assertEquals(2_500, consent.pendingItems)
    }

    @Test
    fun `a rejected request is reported instead of treated as already granted`() {
        val uris = listOf(Uri.parse("content://media/external/audio/media/1"))

        val plan = systemConsentPlan(uris) { throw IllegalArgumentException("rejected") }

        assertTrue(plan is LocalMediaWriteAccessPlan.Unavailable)
    }

    @Test
    fun `android 10 asks for the common folder of songs that are not writable`() {
        val plan = folderGrantPlan(
            listOf(
                StorageDocumentPath("primary", "Music/A"),
                null,
                StorageDocumentPath("primary", "Music/B/Disc 1")
            )
        )

        assertEquals(LocalMediaWriteAccessPlan.FolderGrant(StorageDocumentPath("primary", "Music"), 3), plan)
        assertEquals(LocalMediaWriteAccessPlan.Ready, folderGrantPlan(emptyList()))
    }

    @Test
    fun `songs on different volumes get no shared folder suggestion`() {
        val plan = folderGrantPlan(
            listOf(StorageDocumentPath("primary", "Music"), StorageDocumentPath("1A2B-3C4D", "Music"))
        )

        assertEquals(LocalMediaWriteAccessPlan.FolderGrant(suggestedFolder = null, pendingSongs = 2), plan)
    }

    @Test
    fun `read only external storage song asks for permission to its parent folder`() = runBlocking {
        val song = externalStorageSong()
        val context = contextWithGrants(treeGrant("primary:Music", writable = false))

        assertEquals(
            LocalMediaWriteAccessPlan.FolderGrant(StorageDocumentPath("primary", "Music/Album"), 1),
            LocalMediaWriteAccess.plan(context, listOf(song))
        )
        assertTrue(LocalMediaWriteAccess.requiresDocumentFolderGrant(context, Uri.parse(checkNotNull(song.mediaUri))))
    }

    @Test
    fun `folder authorization can be upgraded in place or granted at the song parent`() = runBlocking {
        val song = externalStorageSong()
        val upgraded = contextWithGrants(
            treeGrant("primary:Music/Album", writable = true),
            treeGrant("primary:Music", writable = true)
        )
        val separateParent = contextWithGrants(
            treeGrant("primary:Music", writable = false),
            treeGrant("primary:Music/Album", writable = true)
        )

        assertEquals(LocalMediaWriteAccessPlan.Ready, LocalMediaWriteAccess.plan(upgraded, listOf(song)))
        assertEquals(LocalMediaWriteAccessPlan.Ready, LocalMediaWriteAccess.plan(separateParent, listOf(song)))
        assertEquals(
            song.mediaUri,
            LocalMediaSupport.editableLocalMediaUriCandidates(upgraded, song).first().toString()
        )
        val parentTree = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Music/Album")
        assertEquals(
            DocumentsContract.buildDocumentUriUsingTree(parentTree, SONG_DOCUMENT_ID),
            LocalMediaSupport.editableLocalMediaUriCandidates(separateParent, song).first()
        )
    }

    @Test
    fun `writable grants must cover the song parent on the same volume`() = runBlocking {
        val song = externalStorageSong()
        val context = contextWithGrants(
            treeGrant("primary:Music", writable = false),
            treeGrant("1A2B-3C4D:Music/Album", writable = true),
            treeGrant("primary:Music/Alb", writable = true),
            treeGrant("primary:Music/Album/song.mp3", writable = true)
        )

        assertEquals(
            LocalMediaWriteAccessPlan.FolderGrant(StorageDocumentPath("primary", "Music/Album"), 1),
            LocalMediaWriteAccess.plan(context, listOf(song))
        )
    }

    private fun externalStorageSong(): SongItem {
        val tree = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Music")
        return SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            mediaUri = DocumentsContract.buildDocumentUriUsingTree(tree, SONG_DOCUMENT_ID).toString()
        )
    }

    private fun contextWithGrants(vararg grants: UriPermission): Context {
        val resolver = mock(ContentResolver::class.java)
        val context = mock(Context::class.java)
        doReturn(grants.toList()).`when`(resolver).persistedUriPermissions
        doReturn(resolver).`when`(context).contentResolver
        return context
    }

    private fun treeGrant(documentId: String, writable: Boolean): UriPermission {
        val grant = mock(UriPermission::class.java)
        doReturn(DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)).`when`(grant).uri
        doReturn(true).`when`(grant).isReadPermission
        doReturn(writable).`when`(grant).isWritePermission
        return grant
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val SONG_DOCUMENT_ID = "primary:Music/Album/song.mp3"
    }
}
