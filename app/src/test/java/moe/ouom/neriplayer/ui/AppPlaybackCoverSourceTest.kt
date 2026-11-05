package moe.ouom.neriplayer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.playback.visual.resolveUiCoverSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPlaybackCoverSourceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun realCoverResolverKeepsSelectedCoversAndRecognizesRestoredEmptyLocalArtwork() {
        val local = SongItem(
            id = 74_128L,
            name = "Restored local cover",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 60_000L,
            coverUrl = null,
            mediaUri = "content://real-cover-resolver/audio/74128",
            originalCoverUrl = "",
            channelId = "local",
            audioId = "74128"
        )

        assertNull(local.resolveUiCoverSource(context))
        assertNull(local.copy(coverUrl = " ", customCoverUrl = "").resolveUiCoverSource(context))
        assertEquals(
            "content://real-cover-resolver/manual-cover",
            local.copy(customCoverUrl = "content://real-cover-resolver/manual-cover").resolveUiCoverSource(context)
        )
        assertEquals(
            "neri-cover://current-local-cover",
            local.copy(coverUrl = "neri-cover://current-local-cover").resolveUiCoverSource(context)
        )
        val noSong: SongItem? = null
        assertNull(noSong.resolveUiCoverSource(context))
    }
}
