package moe.ouom.neriplayer.data.local.playlist.system

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LEGACY_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class LocalFilesPlaylistMergeTest {

    private val originalLocale = Locale.getDefault()
    private val languagePreferences = mock(SharedPreferences::class.java)
    private val resources = mock(Resources::class.java)
    private val configuration = mock(Configuration::class.java)
    private val locales = mock(LocaleList::class.java)
    private val localizedContext = mock(Context::class.java)
    private val context = mock(Context::class.java)

    @Before
    fun stubSystemLanguageContext() {
        doReturn(languagePreferences).`when`(context)
            .getSharedPreferences("language_settings", Context.MODE_PRIVATE)
        doReturn("").`when`(languagePreferences).getString("selected_language", "")
        doReturn(resources).`when`(context).resources
        doReturn(configuration).`when`(resources).configuration
        doReturn(locales).`when`(configuration).locales
        doReturn(localizedContext).`when`(context)
            .createConfigurationContext(any(Configuration::class.java))
        doReturn("Local Files").`when`(localizedContext).getString(CoreCommonR.string.local_files)
    }

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `merging no local files playlists yields an empty localized playlist stamped with the current time`() {
        val before = System.currentTimeMillis()

        val merged = LocalFilesPlaylist.merge(emptyList(), context)

        val after = System.currentTimeMillis()
        assertEquals(LocalFilesPlaylist.SYSTEM_ID, merged.id)
        assertEquals("Local Files", merged.name)
        assertEquals(emptyList<SongItem>(), merged.songs)
        assertTrue(merged.modifiedAt in before..after)
        assertNull(merged.customCoverUrl)
        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, merged.songOrderVersion)
    }

    @Test
    fun `merging local files playlists keeps the newest modification time and fills duplicate song gaps`() {
        val nightDrive = remoteSong(id = 1L, name = "Night Drive", durationMs = 1_000L)
        val nightDriveWithCover = remoteSong(id = 1L, name = "Night Drive (copy)", durationMs = 1_000L)
            .copy(coverUrl = COVER_URL)
        val morning = remoteSong(id = 2L, name = "Morning", durationMs = 2_000L)
        val evening = remoteSong(id = 3L, name = "Evening", durationMs = 3_000L)

        val merged = LocalFilesPlaylist.merge(
            listOf(
                playlist(id = 41L, modifiedAt = 2_000L, nightDrive),
                playlist(id = 42L, modifiedAt = 9_000L, nightDriveWithCover, morning),
                playlist(id = 43L, modifiedAt = 5_000L, evening)
            ),
            context
        )

        assertEquals(LocalFilesPlaylist.SYSTEM_ID, merged.id)
        assertEquals("Local Files", merged.name)
        assertEquals(9_000L, merged.modifiedAt)
        assertEquals(listOf(1L, 2L, 3L), merged.songs.map { it.id })
        assertEquals("Night Drive", merged.songs[0].name)
        assertEquals(COVER_URL, merged.songs[0].coverUrl)
        assertEquals(listOf(morning, evening), merged.songs.drop(1))
        assertNull(merged.customCoverUrl)
        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, merged.songOrderVersion)
    }

    private fun playlist(id: Long, modifiedAt: Long, vararg songs: SongItem): LocalPlaylist {
        return LocalPlaylist(
            id = id,
            name = "Local Files",
            songs = songs.toMutableList(),
            modifiedAt = modifiedAt,
            customCoverUrl = "content://covers/$id",
            songOrderVersion = LEGACY_SONG_ORDER_VERSION
        )
    }

    private fun remoteSong(id: Long, name: String, durationMs: Long): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "artist",
            album = "NeteaseAlbum",
            albumId = 7L,
            durationMs = durationMs,
            coverUrl = null,
            channelId = "netease",
            audioId = id.toString()
        )
    }

    private companion object {
        const val COVER_URL = "https://img.example/night-drive.jpg"
    }
}
