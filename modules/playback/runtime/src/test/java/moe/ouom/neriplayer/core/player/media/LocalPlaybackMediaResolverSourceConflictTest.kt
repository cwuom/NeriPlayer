package moe.ouom.neriplayer.core.player.media

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalPlaybackMediaResolverSourceConflictTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `a readable declared file cannot replace a different external storage document`() {
        assertConflictingFileKeepsPreferred(DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Music/Album/original.mp3"
        ))
    }

    @Test
    fun `a readable declared file cannot replace a different queried MediaStore file`() {
        assertConflictingFileKeepsPreferred(Uri.parse("content://media/external/audio/media/7"))
    }

    @Test
    fun `a readable raw file verified against MediaStore retains the preferred source anchor`() {
        assertReadableAliasKeepsPreferred(fileUri = false)
    }

    @Test
    fun `a readable file uri verified against MediaStore retains the preferred source anchor`() {
        assertReadableAliasKeepsPreferred(fileUri = true)
    }

    @Test
    fun `an unknown preferred provider cannot grant provenance to a readable fallback`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        doReturn(resolver).`when`(context).contentResolver
        val fallback = directory.newFile("unknown.mp3").apply { writeText("audio") }
        val song = SongItem(
            id = 7L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 180_000L, coverUrl = null, mediaUri = "content://unknown.provider/audio/7",
            localFilePath = fallback.absolutePath
        )

        val source = requireNotNull(LocalPlaybackMediaResolver.sourceContext(song, context))

        assertEquals(fallback.absolutePath, source.reference)
        assertNull(source.anchorFor(song, source.reference))
        assertEquals(song.mediaUri, source.unverifiedDeclaredReferenceFor(song, source.reference))
    }

    private fun assertReadableAliasKeepsPreferred(fileUri: Boolean) {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        doReturn(resolver).`when`(context).contentResolver
        val fallback = directory.newFile("selected.mp3").apply { writeText("audio") }
        val preferred = Uri.parse("content://media/external/audio/media/7")
        doAnswer {
            MatrixCursor(arrayOf("_data")).apply { addRow(arrayOf(fallback.absolutePath)) }
        }.`when`(resolver).query(eq(preferred), any(), any(), any(), any())
        val reference = if (fileUri) Uri.fromFile(fallback).toString() else fallback.absolutePath
        val song = SongItem(
            id = 7L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 180_000L, coverUrl = null, mediaUri = preferred.toString(), localFilePath = reference,
            channelId = "netease", audioId = "7", sourceStableKey = "7|netease|"
        )

        val source = requireNotNull(LocalPlaybackMediaResolver.sourceContext(song, context))

        assertEquals(reference, source.reference)
        assertEquals(preferred.toString(), source.anchorFor(song, source.reference))
        assertNull(source.unverifiedDeclaredReferenceFor(song, source.reference))
        assertNull(source.anchorFor(song.copy(name = "Changed song"), source.reference))
    }

    private fun assertConflictingFileKeepsPreferred(original: Uri) {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        doReturn(resolver).`when`(context).contentResolver
        doReturn(emptyList<android.content.UriPermission>()).`when`(resolver).persistedUriPermissions
        doAnswer {
            MatrixCursor(arrayOf("_data")).apply {
                addRow(arrayOf("/storage/emulated/0/Music/Album/original.mp3"))
            }
        }.`when`(resolver).query(eq(original), any(), any(), any(), any())
        val other = directory.newFile("different.mp3").apply { writeText("audio") }
        val song = SongItem(
            id = 7L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 180_000L, coverUrl = null, mediaUri = original.toString(),
            localFilePath = other.absolutePath, channelId = "netease", audioId = "7",
            sourceStableKey = "7|netease|"
        )

        val source = requireNotNull(LocalPlaybackMediaResolver.sourceContext(song, context))

        assertEquals(original.toString(), source.reference)
        assertEquals(original.toString(), source.anchorFor(song, source.reference))
    }
}
