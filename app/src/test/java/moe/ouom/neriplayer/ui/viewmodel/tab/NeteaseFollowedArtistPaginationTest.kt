package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.netease.mapping.NeteaseFollowedArtist
import moe.ouom.neriplayer.platform.netease.mapping.NeteaseFollowedArtistPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class NeteaseFollowedArtistPaginationTest {
    @Test
    fun `offset counts raw entries and overlapping artists are deduplicated`() = runTest {
        val offsets = mutableListOf<Int>()
        val artists = loadAllNeteaseFollowedArtists { offset ->
            offsets += offset
            when (offset) {
                0 -> NeteaseFollowedArtistPage(listOf(artist(1)), true, 2)
                2 -> NeteaseFollowedArtistPage(listOf(artist(1), artist(2)), false, 2)
                else -> error("Unexpected offset")
            }
        }
        assertEquals(listOf(0, 2), offsets)
        assertEquals(listOf(1L, 2L), artists.map { it.id })
        assertEquals("avatar", artists.first().coverUrl)
        assertEquals("alias", artists.first().subtitle)
    }

    @Test
    fun `repeated pages fail instead of silently importing an incomplete library`() {
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                loadAllNeteaseFollowedArtists {
                    NeteaseFollowedArtistPage(listOf(artist(1)), true, 1)
                }
            }
        }
    }

    @Test
    fun `later page failure returns no partial result`() {
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                loadAllNeteaseFollowedArtists { offset ->
                    if (offset != 0) throw IOException("network")
                    NeteaseFollowedArtistPage(listOf(artist(1)), true, 1)
                }
            }
        }
    }

    @Test
    fun `empty final page completes and empty nonfinal page fails`() = runTest {
        assertEquals(emptyList<Any>(), loadAllNeteaseFollowedArtists {
            NeteaseFollowedArtistPage(emptyList(), false, 0)
        })
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                loadAllNeteaseFollowedArtists {
                    NeteaseFollowedArtistPage(emptyList(), true, 0)
                }
            }
        }
    }

    private fun artist(id: Long) = NeteaseFollowedArtist(id, "Artist $id", "avatar", 3, "alias")
}
