package moe.ouom.neriplayer.data.local.playlist.system

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.util.Locale
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, qualifiers = "en")
class LocalFilesPlaylistLocaleReadTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var context: LocaleReadContext

    @Before
    fun setUp() {
        context = LocaleReadContext(RuntimeEnvironment.getApplication())
        context.getSharedPreferences("language_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("language_settings", Context.MODE_PRIVATE).edit().clear().commit()
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `reading album labels follows preference changes without changing platform language`() {
        Locale.setDefault(Locale.FRENCH)
        selectLanguage("en")
        assertEquals("Local Files", LocalFilesPlaylist.currentName(context))
        assertTrue(LocalFilesPlaylist.matches("LOCAL FILES", context))
        assertEquals("Night Album", song("Night Album").displayAlbum(context))

        selectLanguage("zh")
        assertEquals("本地文件", LocalFilesPlaylist.currentName(context))
        assertTrue(LocalFilesPlaylist.candidateNames(context).contains("本地文件"))
        assertTrue(LocalFilesPlaylist.matches("Local Files", context))
        assertEquals("Night Album", song("Night Album").displayAlbum(context))

        assertEquals(0, context.localeServiceReads)
        assertEquals(Locale.FRENCH, Locale.getDefault())
    }

    @Test
    fun `following system language reads the latest primary locale without platform synchronization`() {
        selectLanguage("")
        Locale.setDefault(Locale.FRENCH)
        assertEquals("Local Files", LocalFilesPlaylist.currentName(context))

        RuntimeEnvironment.setQualifiers("zh")
        Locale.setDefault(Locale.FRENCH)

        assertEquals("本地文件", LocalFilesPlaylist.currentName(context))
        assertEquals(0, context.localeServiceReads)
        assertEquals(Locale.FRENCH, Locale.getDefault())
    }

    private fun selectLanguage(code: String) {
        context.getSharedPreferences("language_settings", Context.MODE_PRIVATE)
            .edit().putString("selected_language", code).commit()
    }

    private fun song(album: String) = SongItem(
        id = 1L, name = "Song", artist = "Artist", album = album, albumId = 1L,
        durationMs = 1_000L, coverUrl = null, mediaUri = "https://example.test/song.mp3",
    )

    private class LocaleReadContext(base: Context) : ContextWrapper(base) {
        var localeServiceReads = 0
            private set

        override fun getSystemService(name: String): Any? {
            if (name == Context.LOCALE_SERVICE) localeServiceReads++
            return super.getSystemService(name)
        }
    }
}
