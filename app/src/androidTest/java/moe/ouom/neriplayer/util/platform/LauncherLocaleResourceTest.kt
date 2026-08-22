package moe.ouom.neriplayer.util.platform

import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LauncherLocaleResourceTest {

    @Test
    fun chinesePrimaryLocaleDoesNotUseEnglishForLauncherResources() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(baseContext.resources.configuration).apply {
            setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH))
        }
        val resources = baseContext.createConfigurationContext(configuration).resources

        assertEquals("音理音理!", resources.getString(R.string.app_name))
        assertEquals(
            "继续播放",
            resources.getString(R.string.launcher_shortcut_continue_short)
        )
        assertEquals(
            "打开探索",
            resources.getString(R.string.launcher_shortcut_explore_long)
        )
        assertEquals(
            "媒体库",
            resources.getString(R.string.launcher_shortcut_library_short)
        )
    }

    @Test
    fun manualLanguageSelectionUpdatesThePlatformApplicationLocale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
        val previousLanguage = LanguageManager.getCurrentLanguage(context)
        val previousLocales = localeManager.applicationLocales
        try {
            LanguageManager.setLanguage(context, LanguageManager.Language.ENGLISH)
            assertEquals(LocaleList(Locale.ENGLISH), localeManager.applicationLocales)

            LanguageManager.setLanguage(context, LanguageManager.Language.CHINESE)
            assertEquals(LocaleList(Locale.forLanguageTag("zh")), localeManager.applicationLocales)

            LanguageManager.setLanguage(context, LanguageManager.Language.SYSTEM)
            assertEquals(LocaleList.getEmptyLocaleList(), localeManager.applicationLocales)
        } finally {
            LanguageManager.setLanguage(context, previousLanguage)
            localeManager.applicationLocales = previousLocales
        }
    }
}
