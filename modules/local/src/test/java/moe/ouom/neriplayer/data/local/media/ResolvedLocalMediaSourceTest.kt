package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import moe.ouom.neriplayer.data.local.media.source.ResolvedLocalMediaSource
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
    @get:Rule
    val directory = TemporaryFolder()

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
    fun `readable declared raw and file references retain the proven MediaStore preferred anchor`() {
        val file = directory.newFile("declared.mp3").apply { writeText("audio") }
        listOf(file.absolutePath, Uri.fromFile(file).toString()).forEach { reference ->
            val candidate = readableDeclaredSong(file).copy(localFilePath = reference)

            val source = requireNotNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, reference))

            assertEquals(reference, source.reference)
            assertEquals(original, source.anchorFor(candidate, reference))
            assertNull(source.unverifiedDeclaredReferenceFor(candidate, reference))
        }
    }

    @Test
    fun `readable declared aliases compare complete canonical paths rather than their spelling`() {
        val file = directory.newFile("canonical.mp3").apply { writeText("audio") }
        val child = directory.newFolder("child")
        val reference = File(child, "../${file.name}").absolutePath
        val candidate = readableDeclaredSong(file).copy(localFilePath = reference)

        val source = requireNotNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, reference))

        assertEquals(original, source.anchorFor(candidate, reference))
    }

    @Test
    fun `readable declared alias provenance remains bound to its complete song and actual reference`() {
        val file = directory.newFile("bound.mp3").apply { writeText("audio") }
        val candidate = readableDeclaredSong(file)
        val source = requireNotNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, file.path))

        listOf(candidate.copy(id = 8L), candidate.copy(localFilePath = "${file.path}.other"),
            candidate.copy(mediaUri = "content://media/external/audio/media/8")).forEach { other ->
            assertNull(source.anchorFor(other, file.path))
            assertNull(source.unverifiedDeclaredReferenceFor(other, file.path))
        }
        assertNull(source.anchorFor(candidate, original))
        assertNull(source.anchorFor(candidate, Uri.fromFile(file).toString()))
    }

    @Test
    fun `an undeclared file or alternate spelling cannot acquire preferred provenance`() {
        val file = directory.newFile("undeclared.mp3").apply { writeText("audio") }
        val candidate = readableDeclaredSong(file)

        assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context,
            candidate.copy(localFilePath = null), file.path))
        assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context,
            candidate, Uri.fromFile(file).toString()))
        assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, original))
    }

    @Test
    fun `unknown unsupported or absent preferred paths cannot prove a readable declared alias`() {
        val file = directory.newFile("unknown.mp3").apply { writeText("audio") }
        val candidate = readableDeclaredSong(file)
        listOf(null, " ", "content://other.documents/document/7", "https://example.org/song.mp3")
            .forEach { preferred ->
                assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context,
                    candidate.copy(mediaUri = preferred), file.path))
            }
        assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context,
            candidate.copy(localFilePath = null, mediaUri = null), file.path))
        doReturn(null).`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())
        assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, file.path))
    }

    @Test
    fun `Provider failures cannot prove a readable declared file alias`() {
        val file = directory.newFile("provider-failure.mp3").apply { writeText("audio") }
        val candidate = readableDeclaredSong(file)
        listOf(SecurityException("read denied"), IllegalStateException("provider unavailable")).forEach { failure ->
            doAnswer { throw failure }.`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())

            assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, file.path))
        }
    }

    @Test
    fun `collections and multiple MediaStore rows cannot prove a readable declared file alias`() {
        val file = directory.newFile("ambiguous.mp3").apply { writeText("audio") }
        val collection = "content://media/external/audio/media"
        val candidate = readableDeclaredSong(file)
        mediaStorePath(file.path, collection)
        assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context,
            candidate.copy(mediaUri = collection), file.path))
        val cursor = MatrixCursor(arrayOf("_data")).apply {
            addRow(arrayOf(file.path))
            addRow(arrayOf(file.path))
        }
        doReturn(cursor).`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())

        assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, file.path))
        assertTrue(cursor.isClosed)
    }

    @Test
    fun `missing or relative MediaStore data cannot prove a readable declared file alias`() {
        val file = directory.newFile("missing-data.mp3").apply { writeText("audio") }
        val candidate = readableDeclaredSong(file)
        listOf(MatrixCursor(arrayOf("_data")),
            MatrixCursor(arrayOf("_data")).apply { addRow(arrayOf("Music/song.mp3")) },
            MatrixCursor(arrayOf("_data")).apply { addRow(arrayOfNulls<String>(1)) }).forEach { cursor ->
            doReturn(cursor).`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())

            assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, file.path))
            assertTrue(cursor.isClosed)
        }
    }

    @Test
    fun `a different preferred file cannot lend provenance to a readable declared file`() {
        val file = directory.newFile("actual.mp3").apply { writeText("audio") }
        val other = directory.newFile("preferred.mp3").apply { writeText("other audio") }
        val candidate = readableDeclaredSong(file)
        mediaStorePath(other.path)

        assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, file.path))
        val document = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Music/Album/song.mp3"
        ).toString()
        assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context,
            candidate.copy(mediaUri = document), file.path))
    }

    @Test
    fun `missing files and directories cannot acquire readable declared alias provenance`() {
        listOf(File(directory.root, "missing.mp3"), directory.newFolder("not-audio")).forEach { file ->
            val candidate = readableDeclaredSong(file)

            assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, file.path))
        }
    }

    @Test
    fun `a readable canonical target cannot prove an unopenable declared path`() {
        val file = directory.newFile("canonical-target.mp3").apply { writeText("audio") }
        val reference = File(File(directory.root, "missing-directory"), "../${file.name}")
        val candidate = readableDeclaredSong(file).copy(localFilePath = reference.path)
        assertEquals(file.canonicalFile, reference.canonicalFile)
        assertFalse(reference.isFile)

        assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, reference.path))
    }

    @Test
    fun `a declared file must actually open before it can carry preferred provenance`() {
        val file = directory.newFile("unreadable.mp3").apply { writeText("audio") }
        val candidate = readableDeclaredSong(file)
        try {
            assertTrue(file.setReadable(false, false))
            assertFalse("测试文件必须实际不可读", file.canRead())

            assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, candidate, file.path))
        } finally {
            check(file.setReadable(true, false))
        }
    }

    @Test
    fun `declared provider references and malformed files cannot become ordinary file aliases`() {
        val file = directory.newFile("invalid-reference.mp3").apply { writeText("audio") }
        val candidate = readableDeclaredSong(file)
        listOf("content://other.documents/document/7", "https://example.org/song.mp3", "relative.mp3", "file:",
            "${file.path}\u0000").forEach { reference ->
            assertNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context,
                candidate.copy(localFilePath = reference), reference))
        }
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

    private fun readableDeclaredSong(file: File): SongItem {
        doReturn(resolver).`when`(context).contentResolver
        mediaStorePath(file.canonicalPath)
        return song.copy(localFilePath = file.path)
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
