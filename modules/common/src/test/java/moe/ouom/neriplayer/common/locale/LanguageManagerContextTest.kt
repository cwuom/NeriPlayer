package moe.ouom.neriplayer.common.locale

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager.Language
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LanguageManagerContextTest {

    private val originalLocale = Locale.getDefault()

    @Before
    fun startWithoutCachedApplicationContext() {
        LanguageManager.setLanguage(Fixture(stored = "en").context, Language.ENGLISH)
    }

    @After
    fun restoreLanguageState() {
        LanguageManager.setLanguage(Fixture(stored = "en").context, Language.ENGLISH)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `stored language codes select a language and anything else follows the system`() {
        val cases = listOf(
            "zh" to Language.CHINESE,
            "en" to Language.ENGLISH,
            "" to Language.SYSTEM,
            null to Language.SYSTEM,
            "fr" to Language.SYSTEM
        )

        cases.forEach { (code, language) ->
            assertEquals(language, LanguageManager.getCurrentLanguage(Fixture(stored = code).context))
        }
    }

    @Test
    fun `display names come from the language string resources`() {
        val context = mock(Context::class.java)
        `when`(context.getString(CoreCommonR.string.language_display_chinese)).thenReturn("简体中文")
        `when`(context.getString(CoreCommonR.string.language_display_english)).thenReturn("English")
        `when`(context.getString(CoreCommonR.string.language_display_system)).thenReturn("Follow system")

        assertEquals(
            listOf("简体中文", "English", "Follow system"),
            Language.entries.map { it.getDisplayName(context) }
        )
    }

    @Test
    fun `a context already showing the selected locale is returned unchanged`() {
        val fixture = Fixture(stored = "en", currentLocales = listOf(ENGLISH))
        Locale.setDefault(CHINESE)

        assertSame(fixture.context, LanguageManager.applyLanguage(fixture.context))
        assertEquals(ENGLISH, Locale.getDefault())
        verify(fixture.context, never()).createConfigurationContext(any())
    }

    @Test
    fun `contexts showing other locales get a localized configuration context`() {
        val multiLocale = Fixture(stored = "zh", currentLocales = listOf(CHINESE, ENGLISH))
        val otherLocale = Fixture(stored = "zh", currentLocales = listOf(ENGLISH))

        assertSame(multiLocale.localized, LanguageManager.localizedContext(multiLocale.context, Language.CHINESE))
        assertSame(otherLocale.localized, LanguageManager.localizedContext(otherLocale.context, Language.CHINESE))
    }

    @Test
    fun `init applies the stored language`() {
        val fixture = Fixture(stored = "en", currentLocales = listOf(CHINESE))

        LanguageManager.init(fixture.context)

        assertEquals(ENGLISH, Locale.getDefault())
        verify(fixture.context).createConfigurationContext(any())
    }

    @Test
    fun `application contexts are localized once and reused until the locale changes`() {
        val fixture = Fixture(stored = "zh", currentLocales = listOf(ENGLISH), isApplicationContext = true)

        val first = LanguageManager.applyLanguage(fixture.context)
        Locale.setDefault(ENGLISH)
        val cached = LanguageManager.applyLanguage(fixture.context)
        val cachedAgain = LanguageManager.applyLanguage(fixture.context)

        assertSame(fixture.localized, first)
        assertSame(first, cached)
        assertSame(first, cachedAgain)
        assertEquals(CHINESE, Locale.getDefault())
        verify(fixture.context, times(1)).createConfigurationContext(any())

        fixture.stored = "en"
        assertSame(fixture.context, LanguageManager.applyLanguage(fixture.context))
        assertEquals(ENGLISH, Locale.getDefault())
    }

    @Test
    fun `setting a language persists it and forces the application context to be rebuilt`() {
        val fixture = Fixture(stored = "zh", currentLocales = listOf(ENGLISH), isApplicationContext = true)
        val rebuilt = mock(Context::class.java)
        `when`(fixture.context.createConfigurationContext(any())).thenReturn(fixture.localized, rebuilt)

        assertSame(fixture.localized, LanguageManager.applyLanguage(fixture.context))
        LanguageManager.setLanguage(fixture.context, Language.CHINESE)

        verify(fixture.editor).putString("selected_language", "zh")
        verify(fixture.editor).apply()
        assertEquals(CHINESE, Locale.getDefault())
        assertSame(rebuilt, LanguageManager.applyLanguage(fixture.context))
    }

    @Test
    fun `system language follows the primary system locale`() {
        val fixture = Fixture(stored = "", currentLocales = listOf(FRENCH))

        mockStatic(Resources::class.java).use { resources ->
            val system = resourcesWith(listOf(FRENCH, GERMAN))
            resources.`when`<Resources?> { Resources.getSystem() }.thenReturn(system)

            assertSame(fixture.context, LanguageManager.applyLanguage(fixture.context))
            assertEquals(FRENCH, Locale.getDefault())
        }
    }

    @Test
    fun `system language falls back to the context locales without usable system resources`() {
        val fixture = Fixture(stored = "", currentLocales = listOf(GERMAN))
        val withoutConfiguration = mock(Resources::class.java)
        val systemResources = listOf(null, withoutConfiguration, resourcesWith(locales = null))

        mockStatic(Resources::class.java).use { resources ->
            systemResources.forEach { system ->
                Locale.setDefault(ENGLISH)
                resources.`when`<Resources?> { Resources.getSystem() }.thenReturn(system)

                LanguageManager.applyLanguage(fixture.context)

                assertEquals(GERMAN, Locale.getDefault())
            }
        }
    }

    private class Fixture(
        var stored: String?,
        currentLocales: List<Locale> = listOf(ENGLISH),
        isApplicationContext: Boolean = false
    ) {
        val context: Context = mock(Context::class.java)
        val localized: Context = mock(Context::class.java)
        val editor: SharedPreferences.Editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)

        init {
            val preferences = mock(SharedPreferences::class.java)
            val resources = resourcesWith(currentLocales)
            `when`(context.getSharedPreferences("language_settings", Context.MODE_PRIVATE)).thenReturn(preferences)
            `when`(preferences.getString("selected_language", "")).thenAnswer { stored }
            `when`(preferences.edit()).thenReturn(editor)
            `when`(context.resources).thenReturn(resources)
            `when`(context.applicationContext).thenReturn(
                if (isApplicationContext) context else mock(Context::class.java)
            )
            `when`(context.createConfigurationContext(any())).thenReturn(localized)
        }
    }

    private companion object {
        val ENGLISH: Locale = Locale.forLanguageTag("en")
        val CHINESE: Locale = Locale.forLanguageTag("zh")
        val FRENCH: Locale = Locale.forLanguageTag("fr-FR")
        val GERMAN: Locale = Locale.forLanguageTag("de-DE")

        fun resourcesWith(locales: List<Locale>?): Resources {
            val resources = mock(Resources::class.java)
            val configuration = mock(Configuration::class.java)
            `when`(resources.configuration).thenReturn(configuration)
            if (locales != null) {
                val localeList = mock(LocaleList::class.java)
                `when`(localeList.size()).thenReturn(locales.size)
                locales.forEachIndexed { index, locale -> `when`(localeList.get(index)).thenReturn(locale) }
                `when`(configuration.locales).thenReturn(localeList)
            }
            return resources
        }
    }
}
