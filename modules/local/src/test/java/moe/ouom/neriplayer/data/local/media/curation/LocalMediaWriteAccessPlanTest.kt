package moe.ouom.neriplayer.data.local.media.curation

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.metadata.editableLocalMediaTargets
import moe.ouom.neriplayer.data.local.media.metadata.withMetadataRecoveryDescriptors
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalMediaWriteAccessPlanTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
            LocalMediaSupport.editableLocalMediaTargets(upgraded, song).candidates.first().toString()
        )
        val parentTree = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Music/Album")
        assertEquals(
            DocumentsContract.buildDocumentUriUsingTree(parentTree, SONG_DOCUMENT_ID),
            LocalMediaSupport.editableLocalMediaTargets(separateParent, song).candidates.first()
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

    @Test
    @Config(sdk = [35])
    fun `legacy paths request consent and prioritize the same media item for writeback`() = runBlocking {
        val context = contextWithGrants()
        val resolver = context.contentResolver
        val pendingIntent = mock(PendingIntent::class.java)
        val item = Uri.parse("content://media/abcd-1234/audio/media/42")
        val collection = Uri.parse("content://media/abcd-1234/audio/media")
        val path = "/storage/ABCD-1234/音乐搜索/音频/情字最大 #1.mp3"
        val song = externalStorageSong().copy(mediaUri = path)
        val fileUri = Uri.fromFile(File(path))
        val alias = song.copy(mediaUri = null, localFilePath = fileUri.toString())
        stubMediaStorePath(context, collection, path, 42L, path)
        doReturn(PackageManager.PERMISSION_DENIED).`when`(context)
            .checkUriPermission(item, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        doAnswer { call ->
            val clip = checkNotNull(call.getArgument<Bundle>(3).getParcelable("clip_data", ClipData::class.java))
            assertEquals(1, clip.itemCount)
            assertEquals(item, clip.getItemAt(0).uri)
            Bundle().apply { putParcelable("result", pendingIntent) }
        }.`when`(resolver).call(eq("media"), eq("create_write_request"), isNull(), any(Bundle::class.java))

        assertEquals(
            LocalMediaWriteAccessPlan.SystemConsent(listOf(pendingIntent), 1),
            LocalMediaWriteAccess.plan(context, listOf(song, alias))
        )
        doReturn(PackageManager.PERMISSION_GRANTED).`when`(context)
            .checkUriPermission(item, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(LocalMediaWriteAccessPlan.Ready, LocalMediaWriteAccess.plan(context, listOf(song, alias)))
        assertEquals(listOf(item, fileUri), LocalMediaSupport.editableLocalMediaTargets(context, song).candidates)
        assertEquals(listOf(item, fileUri), LocalMediaSupport.editableLocalMediaTargets(context, alias).candidates)
        assertEquals("mp3", LocalMediaSupport.resolveEditableMediaExtension(song, item))
    }

    @Test
    @Config(sdk = [35])
    fun `granted media URI stays preferred for audio while cover sidecars use the writable directory`() = runBlocking {
        val root = temporaryFolder.newFolder()
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        val file = temporaryFolder.newFile()
        val audio = file.copyTo(File(root, "song.mp3"))
        val source = Uri.fromFile(audio)
        val song = externalStorageSong().copy(mediaUri = audio.path)
        val context = contextWithGrants()
        val item = Uri.parse("content://media/external_primary/audio/media/42")
        stubMediaStorePath(
            context, Uri.parse("content://media/external_primary/audio/media"), audio.canonicalPath, 42L, audio.canonicalPath
        )
        doReturn(PackageManager.PERMISSION_DENIED).`when`(context)
            .checkUriPermission(item, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(LocalMediaWriteAccessPlan.Ready, LocalMediaWriteAccess.plan(context, listOf(song)))
        assertEquals(listOf(source), LocalMediaSupport.editableLocalMediaTargets(context, song).candidates)

        doReturn(PackageManager.PERMISSION_GRANTED).`when`(context)
            .checkUriPermission(item, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(listOf(item, source), LocalMediaSupport.editableLocalMediaTargets(context, song).candidates)

        val coverBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2, 3)
        val cover = temporaryFolder.newFile("selected.jpg").apply { writeBytes(coverBytes) }
        val identity = "local:1"
        val sidecar = File(root, "Covers/" + LocalMediaSupport.localCoverSidecarName("song", "jpg", identity))
        assertTrue(LocalMediaSupport.writeLocalCoverSidecar(context, song, cover.absolutePath, true, identity))
        assertTrue("a successful sidecar write must create the cover", sidecar.isFile)
        assertArrayEquals(coverBytes, sidecar.readBytes())
        assertTrue(LocalMediaSupport.writeLocalCoverSidecar(context, song, null, true, identity))
        assertFalse("a successful sidecar clear must remove the cover", sidecar.exists())
    }

    @Test
    @Config(sdk = [35])
    fun `embedded write through MediaStore also refreshes lyrics in a writable directory`() = withMetadataRecoveryDescriptors {
        val root = temporaryFolder.newFolder("shared")
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        val album = File(root, "Music/Album").apply { check(mkdirs()) }
        val audio = File(album, "song.mp3").apply { writeText("audio fixture") }
        val lyrics = File(album, "song.lrc").apply { writeText("[00:00.00]Old line") }
        val context = contextWithGrants()
        val resolver = context.contentResolver
        val song = externalStorageSong().copy(mediaUri = audio.path, matchedLyric = "[00:00.00]New line")
        val item = Uri.parse("content://media/external_primary/audio/media/42")
        stubMediaStorePath(
            context, Uri.parse("content://media/external_primary/audio/media"), audio.canonicalPath, 42L, audio.canonicalPath
        )
        doReturn(context).`when`(context).applicationContext
        doReturn(temporaryFolder.newFolder("journal")).`when`(context).noBackupFilesDir
        doReturn(RuntimeEnvironment.getApplication().packageManager).`when`(context).packageManager
        doReturn(PackageManager.PERMISSION_GRANTED).`when`(context)
            .checkUriPermission(item, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        doAnswer {
            MatrixCursor(arrayOf("relative_path")).apply { addRow(arrayOf("Music/Album/")) }
        }.`when`(resolver).query(eq(item), any(), isNull(), isNull(), isNull())
        val access = LocalMediaHostAccess::class.java.getDeclaredField("access").apply { isAccessible = true }
        val previous = access.get(null)
        try {
            LocalMediaHostAccess.bind(
                mock(LocalMediaDownloadAccess::class.java),
                mock(LocalMediaCoverAccess::class.java),
                CrashLogCleanup { false }
            )
            mockStatic(Class.forName("moe.ouom.neriplayer.data.local.media.LocalMediaSupportLyricsKt"),
                Answer<Any?> { call ->
                    if (call.method.name == "writeEditableMetadataThroughStagedContentCopy") {
                        assertEquals(item, call.getArgument<Uri>(3))
                        LocalMediaSupport.EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.SUCCESS)
                    } else {
                        call.callRealMethod()
                    }
                }
            ).use {
                assertEquals(
                    LocalMediaMetadataWriteOutcome.SUCCESS,
                    LocalMediaSupport.writeEditableMetadata(context, song, writeLyrics = true)
                )
            }
        } finally {
            access.set(null, previous)
        }
        assertEquals("[00:00.00]New line", lyrics.readText())
    }

    @Test
    @Config(sdk = [35])
    fun `unindexed path uses the matching writable folder only`() = runBlocking {
        ShadowEnvironment.setExternalStorageDirectory(File("/storage/emulated/0").toPath())
        val path = "/storage/emulated/0/Music/Album/song.mp3"
        val song = externalStorageSong().copy(mediaUri = path)
        val otherVolume = treeGrant("ABCD-1234:Music/Album", writable = true)
        val limited = contextWithGrants(otherVolume)
        stubMediaStorePath(
            limited,
            Uri.parse("content://media/external_primary/audio/media"),
            path,
            42L,
            "/storage/emulated/0/Other/Album/song.mp3"
        )
        assertEquals(
            LocalMediaWriteAccessPlan.FolderGrant(StorageDocumentPath("primary", "Music/Album"), 1),
            LocalMediaWriteAccess.plan(limited, listOf(song))
        )

        val granted = contextWithGrants(otherVolume, treeGrant("primary:Music", writable = true))
        stubMediaStorePath(granted, Uri.parse("content://media/external_primary/audio/media"), path, 42L, path)
        assertEquals(LocalMediaWriteAccessPlan.Ready, LocalMediaWriteAccess.plan(granted, listOf(song)))
        val targets = LocalMediaSupport.editableLocalMediaTargets(granted, song)
        assertEquals(
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2Fsong.mp3"),
            targets.candidates.first()
        )
        assertTrue("content://media/external_primary/audio/media/42" in targets.recoveryReferences)
        val documentTargets = LocalMediaSupport.editableLocalMediaTargets(granted, externalStorageSong())
        assertEquals(listOf(targets.candidates.first()), documentTargets.candidates)
        assertTrue(Uri.fromFile(File(path)).toString() in documentTargets.recoveryReferences)
        assertTrue("content://media/external_primary/audio/media/42" in documentTargets.recoveryReferences)
    }

    private fun stubMediaStorePath(context: Context, collection: Uri, path: String, id: Long, rowPath: String) {
        val resolver = context.contentResolver
        doAnswer {
            MatrixCursor(arrayOf("_id", "_data")).apply { addRow(arrayOf<Any>(id, rowPath)) }
        }.`when`(resolver).query(collection, arrayOf("_id", "_data"), "_data = ?", arrayOf(path), null)
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
