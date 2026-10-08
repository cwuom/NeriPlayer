package moe.ouom.neriplayer.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_ALIGNMENT_RIGHT
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_RENDER_STYLE_OUTLINE
import moe.ouom.neriplayer.data.model.settings.lyrics.FloatingLyricsPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingLyricsPreferencesMappingTest {
    @Test
    fun `missing floating lyric settings resolve to the model defaults`() {
        assertEquals(FloatingLyricsPreferences().normalized(), emptyPreferences().toFloatingLyricsPreferences())
    }

    @Test
    fun `landscape positions fall back to the saved portrait position`() {
        val portraitOnly = mutablePreferencesOf(
            SettingsKeys.FLOATING_LYRICS_POSITION_X to 0.3f,
            SettingsKeys.FLOATING_LYRICS_POSITION_Y to 0.4f
        ).toFloatingLyricsPreferences()
        val both = mutablePreferencesOf(
            SettingsKeys.FLOATING_LYRICS_POSITION_X to 0.3f,
            SettingsKeys.FLOATING_LYRICS_POSITION_Y to 0.4f,
            SettingsKeys.FLOATING_LYRICS_LANDSCAPE_POSITION_X to 0.6f,
            SettingsKeys.FLOATING_LYRICS_LANDSCAPE_POSITION_Y to 0.2f
        ).toFloatingLyricsPreferences()

        assertEquals(listOf(0.3f, 0.4f), listOf(portraitOnly.landscapePositionX, portraitOnly.landscapePositionY))
        assertEquals(listOf(0.6f, 0.2f), listOf(both.landscapePositionX, both.landscapePositionY))
    }

    @Test
    fun `saved floating lyric settings are read and normalized`() {
        val preferences = mutablePreferencesOf(
            SettingsKeys.FLOATING_LYRICS_ENABLED to true,
            SettingsKeys.FLOATING_LYRICS_HIDE_IN_APP to true,
            SettingsKeys.FLOATING_LYRICS_LONG_PRESS_DRAG_ENABLED to false,
            SettingsKeys.FLOATING_LYRICS_RENDER_STYLE to FLOATING_LYRICS_RENDER_STYLE_OUTLINE,
            SettingsKeys.FLOATING_LYRICS_FONT_SIZE_SP to 99f,
            SettingsKeys.FLOATING_LYRICS_OUTLINE_WIDTH_DP to 2f,
            SettingsKeys.FLOATING_LYRICS_MAX_WIDTH_DP to 300f,
            SettingsKeys.FLOATING_LYRICS_ALIGNMENT to FLOATING_LYRICS_ALIGNMENT_RIGHT,
            SettingsKeys.FLOATING_LYRICS_SHOW_TRANSLATION to false,
            SettingsKeys.FLOATING_LYRICS_REVEAL_ANIMATION_ENABLED to false
        ).toFloatingLyricsPreferences()

        assertEquals(true, preferences.enabled)
        assertEquals(true, preferences.hideInApp)
        assertEquals(false, preferences.longPressDragEnabled)
        assertEquals(FLOATING_LYRICS_RENDER_STYLE_OUTLINE, preferences.renderStyle)
        assertEquals(32f, preferences.fontSizeSp)
        assertEquals(2f, preferences.outlineWidthDp)
        assertEquals(300f, preferences.maxWidthDp)
        assertEquals(FLOATING_LYRICS_ALIGNMENT_RIGHT, preferences.alignment)
        assertEquals(false, preferences.showTranslation)
        assertEquals(false, preferences.revealAnimationEnabled)
    }
}
