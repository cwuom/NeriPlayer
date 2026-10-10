package moe.ouom.neriplayer.core.player.service.car.library

import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarMediaLibraryDirectoryItemTest {

    @Test
    fun `search directories are titled by their normalized query`() {
        val library = CarMediaLibrary(CarLibrarySnapshot())
        val mediaId = CarMediaIds.search("moon")

        assertEquals(CarLibraryItem(mediaId, "moon"), library.getItem(mediaId))
    }

    @Test
    fun `playlist directories resolve only playlists present in the snapshot`() {
        val library = CarMediaLibrary(
            CarLibrarySnapshot(playlists = listOf(LocalPlaylist(id = 42, name = "Road trip")))
        )

        assertEquals(
            CarLibraryItem(CarMediaIds.playlist(42), "Road trip"),
            library.getItem(CarMediaIds.playlist(42))
        )
        assertNull(library.getItem(CarMediaIds.playlist(7)))
    }
}
