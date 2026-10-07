package moe.ouom.neriplayer.core.player.service.car

import android.media.browse.MediaBrowser
import android.os.Bundle
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class CarBrowserRootSelectionTest {

    private val noHints = hints()
    private val offline = hints(offline = true)
    private val recent = hints(recent = true)
    private val offlineAndRecent = hints(offline = true, recent = true)

    @Test
    fun `root hints pick the offline or recent shortcut before the catalogue root`() {
        assertEquals(CarMediaIds.ROOT, carRequestedBrowserRootId(null))
        assertEquals(CarMediaIds.ROOT, carRequestedBrowserRootId(noHints))
        assertEquals(CarMediaIds.OFFLINE, carRequestedBrowserRootId(offline))
        assertEquals(CarMediaIds.HISTORY, carRequestedBrowserRootId(recent))
        assertEquals(CarMediaIds.OFFLINE, carRequestedBrowserRootId(offlineAndRecent))
    }

    @Test
    fun `browse only controllers always receive the catalogue root`() {
        val browseOnly = MediaBrowser.MediaItem.FLAG_BROWSABLE

        assertEquals(CarMediaIds.ROOT, carBrowserRootId(offline, browseOnly))
        assertEquals(CarMediaIds.ROOT, carBrowserRootId(recent, browseOnly))
    }

    @Test
    fun `controllers that can play items or report no flags follow the root hints`() {
        val browseAndPlay = MediaBrowser.MediaItem.FLAG_BROWSABLE or MediaBrowser.MediaItem.FLAG_PLAYABLE

        assertEquals(CarMediaIds.OFFLINE, carBrowserRootId(offline, browseAndPlay))
        assertEquals(CarMediaIds.HISTORY, carBrowserRootId(recent, MediaBrowser.MediaItem.FLAG_PLAYABLE))
        assertEquals(CarMediaIds.HISTORY, carBrowserRootId(recent, null))
        assertEquals(CarMediaIds.ROOT, carBrowserRootId(null, null))
    }

    private fun hints(offline: Boolean = false, recent: Boolean = false): Bundle =
        mock(Bundle::class.java).also {
            `when`(it.getBoolean("android.service.media.extra.OFFLINE", false)).thenReturn(offline)
            `when`(it.getBoolean("android.service.media.extra.RECENT", false)).thenReturn(recent)
        }
}
