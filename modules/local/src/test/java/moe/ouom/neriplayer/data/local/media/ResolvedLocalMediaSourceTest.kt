package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import moe.ouom.neriplayer.data.local.media.source.ResolvedLocalMediaSource
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ResolvedLocalMediaSourceTest {
    private val context = mock(Context::class.java)
    private val resolver = mock(ContentResolver::class.java)
    private val original = "content://media/external/audio/media/7"
    private val path = "/storage/emulated/0/Music/Album/song.mp3"
    private val song = SongItem(
        id = 7L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
        durationMs = 180_000L, coverUrl = null, mediaUri = original, localFilePath = path,
        channelId = "netease", audioId = "7", sourceStableKey = "7|netease|"
    )

    @Test
    fun `direct sources only prove their exact stored reference`() {
        val source = ResolvedLocalMediaSource.direct(song, original)

        assertEquals(original, source.reference)
        assertEquals(original, source.anchorFor(song, original))
        assertNull(source.anchorFor(song, path))
        assertNull(ResolvedLocalMediaSource.direct(song, path).anchorFor(song, path))
        val unaddressed = song.copy(localFilePath = null, mediaUri = null)
        assertNull(ResolvedLocalMediaSource.direct(unaddressed, path).unverifiedDeclaredReferenceFor(unaddressed, path))
    }

    @Test
    fun `source contexts cannot be borrowed by another song or source snapshot`() {
        val source = ResolvedLocalMediaSource.direct(song, original)

        assertNull(source.anchorFor(song.copy(id = 8L), original))
        assertNull(source.anchorFor(song.copy(localFilePath = "$path.other"), original))
        assertNull(source.anchorFor(song.copy(mediaUri = "content://media/external/audio/media/8"), original))
    }

    @Test
    fun `unverified declarations can only deny replacement for the same snapshot and actual reference`() {
        val source = ResolvedLocalMediaSource.direct(song, path)

        assertEquals(original, source.unverifiedDeclaredReferenceFor(song, path))
        assertNull(source.unverifiedDeclaredReferenceFor(song.copy(id = 8L), path))
        assertNull(source.unverifiedDeclaredReferenceFor(song.copy(localFilePath = "$path.other"), path))
        assertNull(source.unverifiedDeclaredReferenceFor(song, original))
        assertNull(ResolvedLocalMediaSource.direct(song, original).unverifiedDeclaredReferenceFor(song, original))
    }

    @Test
    fun `readable folder recovery carries the original reference for the same complete file path`() {
        val recovered = grant("primary:Music")

        val source = requireNotNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))

        assertEquals(recovered.toString(), source.reference)
        assertEquals(original, source.anchorFor(song, source.reference))
        assertNull(source.anchorFor(song, original))
        assertNull(source.anchorFor(song.copy(localFilePath = "$path.other"), source.reference))
        assertNull(source.anchorFor(song.copy(id = 8L), source.reference))
        assertNull(source.unverifiedDeclaredReferenceFor(song, source.reference))
    }

    @Test
    fun `an external storage document proves its own complete path without a Provider query`() {
        val recovered = grant("primary:Music")
        val document = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Music/Album/song.mp3"
        ).toString()
        val documentSong = song.copy(mediaUri = document)

        val source = requireNotNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, documentSong))

        assertEquals(recovered.toString(), source.reference)
        assertEquals(document, source.anchorFor(documentSong, source.reference))
        verify(resolver, never()).query(any(), any(), any(), any(), any())
    }

    @Test
    fun `folder recovery requires persisted read access to the exact volume and directory`() {
        grant("primary:Music", readGranted = false)
        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
        grant("1234-ABCD:Music")
        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
        grant("primary:Music/AlbumOther")
        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
        grant("primary:Music", authority = "other.documents")
        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
    }

    @Test
    fun `a folder grant without an openable document cannot mint alias provenance`() {
        grant("primary:Music", readable = false)

        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
    }

    @Test
    fun `canonical parent traversal cannot borrow a narrower folder grant`() {
        grant("primary:Music/Album")

        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(
            context, song.copy(localFilePath = "/storage/emulated/0/Music/Album/../Other/song.mp3")
        ))
    }

    @Test
    fun `an external storage document cannot lend its provenance to a different declared file`() {
        grant("primary:Music")
        val document = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Music/Album/original.mp3"
        ).toString()

        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song.copy(mediaUri = document)))
    }

    @Test
    fun `a MediaStore record cannot lend its provenance to a conflicting declared file`() {
        grant("primary:Music")
        mediaStorePath("/storage/emulated/0/Music/Album/original.mp3")

        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
    }

    @Test
    fun `an unknown MediaStore path cannot be inferred from the declared file`() {
        grant("primary:Music")
        doReturn(null).`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())

        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
    }

    @Test
    fun `a MediaStore collection cannot prove one file from its first query row`() {
        grant("primary:Music")
        val collection = "content://media/external/audio/media"
        mediaStorePath(path, collection)

        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song.copy(mediaUri = collection)))
    }

    @Test
    fun `a MediaStore item returning multiple files cannot mint alias provenance`() {
        grant("primary:Music")
        doAnswer {
            MatrixCursor(arrayOf("_data")).apply {
                addRow(arrayOf(path))
                addRow(arrayOf("/storage/emulated/0/Music/Album/another.mp3"))
            }
        }.`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())

        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
    }

    @Test
    fun `missing preferred references and remote songs cannot acquire folder aliases`() {
        listOf(null, " ").forEach { reference ->
            assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song.copy(
                localFilePath = null, mediaUri = reference, album = LocalSongSupport.LOCAL_ALBUM_IDENTITY
            )))
        }
        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song.copy(
            localFilePath = null, mediaUri = "https://example.org/song.mp3", albumId = 1L
        )))
    }

    @Test
    fun `raw and file references recover the same readable document`() {
        val recovered = grant("primary:Music")
        listOf(path, "file://$path").forEach { reference ->
            val candidate = song.copy(localFilePath = null, mediaUri = reference)
            val source = requireNotNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, candidate))

            assertEquals(recovered.toString(), source.reference)
            assertEquals(reference, source.anchorFor(candidate, source.reference))
        }
    }

    @Test
    fun `unsupported references and paths outside external storage cannot acquire folder aliases`() {
        grant("primary:Music")
        listOf("invalid", "file:", "/tmp/song.mp3", "content://other.documents/document/7",
            "content:///document/7", "content:/document/7").forEach { reference ->
            assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song.copy(
                localFilePath = reference, mediaUri = null
            )))
        }
    }

    @Test
    fun `declared file conflicts require a resolvable preferred path`() {
        grant("primary:Music")
        assertFalse(ResolvedLocalMediaSource.hasConflictingDeclaredFile(context, song))
        assertTrue(ResolvedLocalMediaSource.hasConflictingDeclaredFile(context, song.copy(localFilePath = "$path.other")))
        assertFalse(ResolvedLocalMediaSource.hasConflictingDeclaredFile(context,
            song.copy(localFilePath = null, mediaUri = null)))
        assertFalse(ResolvedLocalMediaSource.hasConflictingDeclaredFile(context,
            song.copy(mediaUri = "content://other.documents/document/7")))
        val invalid = song.copy(localFilePath = "$path\u0000")
        assertTrue(ResolvedLocalMediaSource.hasConflictingDeclaredFile(context, invalid))
        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, invalid))
    }

    @Test
    fun `removable storage documents require their exact volume and complete canonical path`() {
        val documentId = "1234-ABCD:Music/Album/song.mp3"
        val recovered = grant("1234-ABCD:Music", documentId = documentId)
        val document = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "1234-abcd:Music/Album/song.mp3"
        ).toString()
        val candidate = song.copy(mediaUri = document,
            localFilePath = "/storage/1234-ABCD/Music/Album/song.mp3")

        val source = requireNotNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, candidate))

        assertEquals(recovered.toString(), source.reference)
        assertEquals(document, source.anchorFor(candidate, source.reference))
    }

    @Test
    fun `external storage document syntax cannot escape its volume or normalize a different document`() {
        grant("primary:Music")
        listOf("unknown:Music/song.mp3", "primary:", "primary", "1234:Music/song.mp3",
            "primary:Music/Album/../Other/song.mp3", "primary:/Music/Album/song.mp3",
            "primary:../../1234-ABCD/Music/song.mp3", "primary:../../../tmp/song.mp3").forEach { documentId ->
            val reference = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", documentId
            ).toString()
            assertNull(documentId, ResolvedLocalMediaSource.recoverFromFolderGrant(context,
                song.copy(localFilePath = null, mediaUri = reference)))
        }
        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song.copy(
            localFilePath = null, mediaUri = "content://com.android.externalstorage.documents/invalid"
        )))
    }

    @Test
    fun `only canonical MediaStore audio item paths can be queried for alias provenance`() {
        grant("primary:Music")
        listOf("video/media/7", "audio/artists/7", "audio/media/7/extra", "audio/media/not-an-id",
            "audio/media/-1", "audio/media/+7", "audio/media/007", "audio/media/9223372036854775808")
            .forEach { itemPath ->
                val reference = "content://media/external/$itemPath"
                mediaStorePath(path, reference)
                assertNull(itemPath, ResolvedLocalMediaSource.recoverFromFolderGrant(context,
                    song.copy(mediaUri = reference)))
            }
        verify(resolver, never()).query(any(), any(), any(), any(), any())
    }

    @Test
    fun `missing or nonabsolute MediaStore data never proves a declared file and closes its cursor`() {
        grant("primary:Music")
        val cursors = listOf(
            MatrixCursor(arrayOf("_data")),
            MatrixCursor(arrayOf("name")).apply { addRow(arrayOf("song.mp3")) },
            MatrixCursor(arrayOf("_data")).apply { addRow(arrayOfNulls<String>(1)) },
            MatrixCursor(arrayOf("_data")).apply { addRow(arrayOf("Music/Album/song.mp3")) }
        )
        cursors.forEach { cursor ->
            doReturn(cursor).`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())

            assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
            assertTrue(cursor.isClosed)
        }
    }

    @Test
    fun `a single MediaStore data row proves the file and is closed after recovery`() {
        val recovered = grant("primary:Music")
        val cursor = MatrixCursor(arrayOf("_data")).apply { addRow(arrayOf(path)) }
        doReturn(cursor).`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())

        val source = requireNotNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))

        assertEquals(recovered.toString(), source.reference)
        assertTrue(cursor.isClosed)
    }

    @Test
    fun `MediaStore permission and Provider failures cannot prove a folder alias`() {
        grant("primary:Music")
        listOf(SecurityException("read denied"), IllegalStateException("provider unavailable")).forEach { failure ->
            doAnswer { throw failure }.`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())

            assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
        }
    }

    @Test
    fun `missing and malformed persisted grants cannot prove a readable alias`() {
        grant("primary:Music")
        doReturn(emptyList<UriPermission>()).`when`(resolver).persistedUriPermissions
        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
        doReturn(null).`when`(resolver).persistedUriPermissions
        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
        val invalid = mock(UriPermission::class.java)
        doReturn(true).`when`(invalid).isReadPermission
        doReturn(Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic"))
            .`when`(invalid).uri
        doReturn(listOf(invalid)).`when`(resolver).persistedUriPermissions
        assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
    }

    @Test
    fun `lost read access and Provider failures while opening the recovered document cannot mint aliases`() {
        val recovered = grant("primary:Music")
        listOf(SecurityException("grant revoked"), IllegalStateException("provider unavailable")).forEach { failure ->
            doAnswer { throw failure }.`when`(resolver).openAssetFileDescriptor(recovered, "r")

            assertNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
        }
    }

    private fun grant(
        treeId: String,
        readGranted: Boolean = true,
        readable: Boolean = true,
        authority: String = "com.android.externalstorage.documents",
        documentId: String = "primary:Music/Album/song.mp3"
    ): Uri {
        val tree = DocumentsContract.buildTreeDocumentUri(authority, treeId)
        val recovered = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val permission = mock(UriPermission::class.java)
        doReturn(resolver).`when`(context).contentResolver
        doReturn(listOf(permission)).`when`(resolver).persistedUriPermissions
        doReturn(tree).`when`(permission).uri
        doReturn(readGranted).`when`(permission).isReadPermission
        doReturn(if (readable) mock(AssetFileDescriptor::class.java) else null)
            .`when`(resolver).openAssetFileDescriptor(recovered, "r")
        mediaStorePath(path)
        return recovered
    }

    private fun mediaStorePath(resolvedPath: String, reference: String = original) {
        doAnswer {
            MatrixCursor(arrayOf("_data")).apply { addRow(arrayOf(resolvedPath)) }
        }.`when`(resolver).query(eq(Uri.parse(reference)), any(), any(), any(), any())
    }
}
