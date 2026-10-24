package moe.ouom.neriplayer.util.platform

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import moe.ouom.neriplayer.common.locale.LanguageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28, 32],
    application = Application::class,
    qualifiers = "en-rUS-sw360dp-w360dp-h800dp-normal-port-notnight-560dpi"
)
class ConfigurationContextContinuityTest {
    @Test
    fun explicitLanguageDoesNotOverrideLaterWindowAndThemeChanges() {
        val baseContext = spy(ApplicationProvider.getApplicationContext<Application>())
        val localized = LanguageManager.localizedContext(baseContext, LanguageManager.Language.CHINESE)
        assertEquals("zh", localized.resources.configuration.locales[0].language)
        val override = ArgumentCaptor.forClass(Configuration::class.java)
        verify(baseContext).createConfigurationContext(override.capture())

        val effectiveConfiguration = laterConfiguration().apply { updateFrom(override.value) }

        assertLaterWindowAndTheme(effectiveConfiguration)
        assertEquals("zh", effectiveConfiguration.locales[0].language)
        assertEquals(480, effectiveConfiguration.densityDpi)
    }

    @Test
    fun onePlusDensityDoesNotOverrideLaterWindowThemeAndLanguageChanges() {
        ShadowBuild.setManufacturer("OnePlus")
        ShadowBuild.setBrand("OnePlus")
        val baseContext = spy(ApplicationProvider.getApplicationContext<Application>())
        val corrected = applyOnePlusHighDensityDisplayCorrection(baseContext)
        assertEquals(532, corrected.resources.displayMetrics.densityDpi)
        val override = ArgumentCaptor.forClass(Configuration::class.java)
        verify(baseContext).createConfigurationContext(override.capture())

        val effectiveConfiguration = laterConfiguration().apply { updateFrom(override.value) }

        assertLaterWindowAndTheme(effectiveConfiguration)
        assertEquals(Locale.JAPANESE, effectiveConfiguration.locales[0])
        assertEquals(532, effectiveConfiguration.densityDpi)
    }

    private fun laterConfiguration(): Configuration = Configuration().apply {
        orientation = Configuration.ORIENTATION_LANDSCAPE
        screenWidthDp = 820
        screenHeightDp = 400
        smallestScreenWidthDp = 400
        screenLayout = Configuration.SCREENLAYOUT_SIZE_LARGE or Configuration.SCREENLAYOUT_LONG_YES
        uiMode = Configuration.UI_MODE_TYPE_DESK or Configuration.UI_MODE_NIGHT_YES
        fontScale = 1.3f
        densityDpi = 480
        setLocales(LocaleList(Locale.JAPANESE))
    }

    private fun assertLaterWindowAndTheme(configuration: Configuration) {
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, configuration.orientation)
        assertEquals(820, configuration.screenWidthDp)
        assertEquals(400, configuration.screenHeightDp)
        assertEquals(400, configuration.smallestScreenWidthDp)
        assertEquals(
            Configuration.SCREENLAYOUT_SIZE_LARGE,
            configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        )
        assertEquals(
            Configuration.SCREENLAYOUT_LONG_YES,
            configuration.screenLayout and Configuration.SCREENLAYOUT_LONG_MASK
        )
        assertEquals(Configuration.UI_MODE_TYPE_DESK or Configuration.UI_MODE_NIGHT_YES, configuration.uiMode)
        assertEquals(1.3f, configuration.fontScale, 0f)
    }
}
