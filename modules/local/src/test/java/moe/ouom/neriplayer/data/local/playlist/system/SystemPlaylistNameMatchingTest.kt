package moe.ouom.neriplayer.data.local.playlist.system

import android.content.Context
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class SystemPlaylistNameMatchingTest {
    @Test
    fun `favorites match canonical names case insensitively`() {
        assertTrue(FavoritesPlaylist.matches("我喜欢的音乐"))
        assertTrue(FavoritesPlaylist.matches("my favorite music"))
        assertTrue(FavoritesPlaylist.matches("鎴戝枩娆㈢殑闊充箰"))
        assertFalse(FavoritesPlaylist.matches("Road Trip"))
        assertFalse(FavoritesPlaylist.matches(null))
        assertFalse(FavoritesPlaylist.matches("  "))
    }

    @Test
    fun `favorites are found by system id or by a legacy negative id with its name`() {
        val user = playlist(id = 7L, name = "My Favorite Music")
        val legacy = playlist(id = -3L, name = "我喜欢的音乐")
        val system = playlist(id = FavoritesPlaylist.SYSTEM_ID, name = "Renamed")
        val otherNegative = playlist(id = -4L, name = "Road Trip")

        assertSame(legacy, FavoritesPlaylist.firstOrNull(listOf(user, otherNegative, legacy, system)))
        assertSame(system, FavoritesPlaylist.firstOrNull(listOf(user, system)))
        assertNull(FavoritesPlaylist.firstOrNull(listOf(user, otherNegative)))
    }

    @Test
    fun `favorites system checks short circuit before resolving localized names`() {
        val context = mock(Context::class.java)

        assertTrue(FavoritesPlaylist.isSystemPlaylist(playlist(FavoritesPlaylist.SYSTEM_ID, "Renamed"), context))
        assertFalse(FavoritesPlaylist.isSystemPlaylist(playlist(12L, "My Favorite Music"), context))
        verifyNoInteractions(context)
    }

    @Test
    fun `local files match canonical names and their legacy mojibake`() {
        assertTrue(LocalFilesPlaylist.matches("本地文件"))
        assertTrue(LocalFilesPlaylist.matches("LOCAL FILES"))
        assertFalse(LocalFilesPlaylist.matches("Downloads"))
        assertFalse(LocalFilesPlaylist.matches(""))
    }

    @Test
    fun `local files are found by system id or by a legacy negative id with its name`() {
        val user = playlist(id = 9L, name = "Local Files")
        val legacy = playlist(id = -8L, name = "本地文件")
        val system = playlist(id = LocalFilesPlaylist.SYSTEM_ID, name = "Renamed")

        assertSame(legacy, LocalFilesPlaylist.firstOrNull(listOf(user, legacy, system)))
        assertSame(system, LocalFilesPlaylist.firstOrNull(listOf(system, legacy)))
        assertNull(LocalFilesPlaylist.firstOrNull(listOf(user)))
    }

    @Test
    fun `candidate names include utf8 text misread as gbk`() {
        assertEquals(
            setOf("我喜欢的音乐", "My Favorite Music", "Localized", "鎴戝枩娆㈢殑闊充箰"),
            buildSystemPlaylistCandidateNames("我喜欢的音乐", "My Favorite Music", "Localized")
        )
    }

    @Test
    fun `candidate names skip variants that are unchanged or empty after decoding`() {
        assertEquals(
            setOf("ascii-name", "English", "Localized ascii"),
            buildSystemPlaylistCandidateNames("ascii-name", "English", "Localized ascii")
        )
        assertEquals(
            setOf("\u0000", "English", "Localized nul"),
            buildSystemPlaylistCandidateNames("\u0000", "English", "Localized nul")
        )
        assertEquals(
            setOf(" ", "English", "Localized blank"),
            buildSystemPlaylistCandidateNames(" ", "English", "Localized blank")
        )
    }

    private fun playlist(id: Long, name: String) = LocalPlaylist(id = id, name = name, songs = mutableListOf())
}
