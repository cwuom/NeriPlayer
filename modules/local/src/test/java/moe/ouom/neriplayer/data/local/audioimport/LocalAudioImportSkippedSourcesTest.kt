package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class LocalAudioImportSkippedSourcesTest {
    @Test
    fun `remote songs are returned untouched without inspecting media`() {
        val context = mock(Context::class.java)
        val song = SongItem(
            id = 7L,
            name = "Night Drive",
            artist = "Artist",
            album = "Album",
            albumId = 70L,
            durationMs = 180_000L,
            coverUrl = "https://img.example/7.jpg",
            mediaUri = "https://music.example/track/7"
        )

        assertSame(song, LocalAudioImportManager.hydrateLocalSongMetadata(context, song))
        assertSame(
            song,
            LocalAudioImportManager.hydrateLocalSongMetadata(context, song, includeEmbeddedAssets = false)
        )
        verifyNoInteractions(context)
    }

    @Test
    fun `folder children are not queried for a uri without a document id`() = runTest {
        val context = mock(Context::class.java)

        assertNull(LocalAudioImportManager.queryFolderChildren(context, mock(Uri::class.java)))
        verifyNoInteractions(context)
    }
}
