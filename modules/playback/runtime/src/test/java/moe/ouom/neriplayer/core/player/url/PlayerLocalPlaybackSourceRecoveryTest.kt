package moe.ouom.neriplayer.core.player.url

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
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
class PlayerLocalPlaybackSourceRecoveryTest {
    private val original = "content://media/external/audio/media/7"
    private val song = SongItem(
        id = 7L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
        durationMs = 180_000L, coverUrl = null, mediaUri = original,
        localFilePath = "/storage/emulated/0/Music/Album/song.mp3",
        channelId = "netease", audioId = "7", sourceStableKey = "7|netease|"
    )

    @Test
    fun `missing verified aliases consult the original manual source and never recover remotely`() {
        val source = recoveredSource()
        val queried = mutableListOf<String>()

        val result = recoverNeteaseSourceAfterMissingLocal(song, source.reference, source) {
            queried += it
            it == original
        }

        assertNull(result)
        assertEquals(listOf(original), queried)
    }

    @Test
    fun `unknown verified alias provenance blocks remote recovery`() {
        val source = recoveredSource()

        assertNull(recoverNeteaseSourceAfterMissingLocal(song, source.reference, source) {
            if (it == original) null else false
        })
    }

    @Test
    fun `another song snapshot cannot borrow the same recovered reference context`() {
        val source = recoveredSource()

        assertNotNull(recoverNeteaseSourceAfterMissingLocal(song.copy(id = 8L), source.reference, source) {
            it == original
        })
        assertNull(recoverNeteaseSourceAfterMissingLocal(song, null, source) { false })
    }

    @Test
    fun `an unverified declared alias cannot replace a manual source remotely`() {
        val source = ResolvedLocalMediaSource.direct(song, requireNotNull(song.localFilePath))

        assertNull(recoverNeteaseSourceAfterMissingLocal(song, source.reference, source) { it == original })
    }

    @Test
    fun `an unverified declared alias cannot recover remotely while provenance is unknown`() {
        val source = ResolvedLocalMediaSource.direct(song, requireNotNull(song.localFilePath))

        assertNull(recoverNeteaseSourceAfterMissingLocal(song, source.reference, source) {
            if (it == original) null else false
        })
    }

    @Test
    fun `unverified declarations retain remote recovery when neither reference is manual`() {
        val source = ResolvedLocalMediaSource.direct(song, requireNotNull(song.localFilePath))

        assertNotNull(recoverNeteaseSourceAfterMissingLocal(song, source.reference, source) { false })
    }

    private fun recoveredSource(): ResolvedLocalMediaSource {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val permission = mock(UriPermission::class.java)
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Music/Album/song.mp3")
        doReturn(resolver).`when`(context).contentResolver
        doReturn(listOf(permission)).`when`(resolver).persistedUriPermissions
        doReturn(tree).`when`(permission).uri
        doReturn(true).`when`(permission).isReadPermission
        doReturn(mock(AssetFileDescriptor::class.java)).`when`(resolver).openAssetFileDescriptor(document, "r")
        doAnswer {
            MatrixCursor(arrayOf("_data")).apply { addRow(arrayOf(song.localFilePath)) }
        }.`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())
        return requireNotNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
    }
}
