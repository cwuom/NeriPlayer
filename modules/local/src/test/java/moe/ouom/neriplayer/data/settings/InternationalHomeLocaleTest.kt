package moe.ouom.neriplayer.data.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.common.locale.LanguageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InternationalHomeLocaleTest {
    @Test
    @Config(qualifiers = "en-rUS")
    fun chineseAppLanguageLeavesAnEnglishSystemOnInternationalHome() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val original = Locale.getDefault()
        val language = LanguageManager.getCurrentLanguage(context)
        try {
            LanguageManager.setLanguage(context, LanguageManager.Language.CHINESE)
            IsolatedSettingsDataStore.withEmptySettings(context) {
                val repo = SettingsRepository(context)
                assertTrue(repo.internationalizationEnabledFlow.first())
                repo.setInternationalizationEnabled(false)
                assertFalse(repo.internationalizationEnabledFlow.first())
            }
        } finally {
            LanguageManager.setLanguage(context, language)
            Locale.setDefault(original)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun englishAppLanguageLeavesAChineseSystemOnDomesticHome() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val original = Locale.getDefault()
        val language = LanguageManager.getCurrentLanguage(context)
        try {
            LanguageManager.setLanguage(context, LanguageManager.Language.ENGLISH)
            IsolatedSettingsDataStore.withEmptySettings(context) {
                val repo = SettingsRepository(context)
                assertFalse(repo.internationalizationEnabledFlow.first())
                repo.setInternationalizationEnabled(true)
                assertTrue(repo.internationalizationEnabledFlow.first())
            }
        } finally {
            LanguageManager.setLanguage(context, language)
            Locale.setDefault(original)
        }
    }
}
