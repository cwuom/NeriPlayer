package moe.ouom.neriplayer.util.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguageManagerTest {

    @Test
    fun systemLanguageUsesOnlyThePrimarySystemLocale() {
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            LanguageManager.resolveApplicationLocale(
                language = LanguageManager.Language.SYSTEM,
                systemLocales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH),
                fallbackLocale = Locale.ENGLISH
            )
        )
    }

    @Test
    fun changingThePrimarySystemLocaleChangesTheResolvedLanguage() {
        val beforeEnglishIsMovedToSecond = LanguageManager.resolveApplicationLocale(
            language = LanguageManager.Language.SYSTEM,
            systemLocales = listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE),
            fallbackLocale = Locale.SIMPLIFIED_CHINESE
        )
        val afterEnglishIsMovedToSecond = LanguageManager.resolveApplicationLocale(
            language = LanguageManager.Language.SYSTEM,
            systemLocales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH),
            fallbackLocale = Locale.ENGLISH
        )

        assertEquals(Locale.ENGLISH, beforeEnglishIsMovedToSecond)
        assertEquals(Locale.SIMPLIFIED_CHINESE, afterEnglishIsMovedToSecond)
    }

    @Test
    fun manualLanguageOverridesEverySystemLocale() {
        assertEquals(
            Locale.ENGLISH,
            LanguageManager.resolveApplicationLocale(
                language = LanguageManager.Language.ENGLISH,
                systemLocales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH),
                fallbackLocale = Locale.SIMPLIFIED_CHINESE
            )
        )
        assertEquals(
            Locale.forLanguageTag("zh"),
            LanguageManager.resolveApplicationLocale(
                language = LanguageManager.Language.CHINESE,
                systemLocales = listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE),
                fallbackLocale = Locale.ENGLISH
            )
        )
    }

    @Test
    fun systemLanguageUsesFallbackWhenTheSystemLocaleListIsEmpty() {
        assertEquals(
            Locale.ENGLISH,
            LanguageManager.resolveApplicationLocale(
                language = LanguageManager.Language.SYSTEM,
                systemLocales = emptyList(),
                fallbackLocale = Locale.ENGLISH
            )
        )
    }

}
