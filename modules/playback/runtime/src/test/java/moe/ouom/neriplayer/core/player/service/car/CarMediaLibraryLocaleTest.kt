package moe.ouom.neriplayer.core.player.service.car

import android.app.Application
import android.content.Context
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CarMediaLibraryLocaleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val savedLanguage = LanguageManager.getCurrentLanguage(context)
    private val savedLocale = Locale.getDefault()

    @After
    fun restoreLanguage() {
        LanguageManager.setLanguage(context, savedLanguage)
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `car browse labels follow current language before player repositories are ready`() {
        for ((language, labels) in listOf(
            LanguageManager.Language.ENGLISH to listOf("Play Queue", "Playlists", "Recent", "Local Files"),
            LanguageManager.Language.CHINESE to listOf("播放列表", "歌单", "最近播放", "本地文件"),
            LanguageManager.Language.ENGLISH to listOf("Play Queue", "Playlists", "Recent", "Local Files")
        )) {
            LanguageManager.setLanguage(context, language)
            assertEquals(labels, androidCarMediaLibrary(context).children(CarMediaIds.ROOT).map { it.title })
        }
    }
}
