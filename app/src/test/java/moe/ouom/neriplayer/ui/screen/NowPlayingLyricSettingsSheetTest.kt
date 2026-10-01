package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.graphics.Color
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.lyrics.offset.LYRIC_DEFAULT_OFFSET_STEP_MS
import moe.ouom.neriplayer.lyrics.offset.MAX_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.lyrics.offset.MIN_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.data.settings.lyrics.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.lyrics.MIN_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricBehaviorSheetState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricFontSizeSheetState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricTranslationToggleCopy
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.booleanToggleClick
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.formatLyricOffset
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.isLyricPhoneticSwitchChecked
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.isLyricPhoneticSwitchEnabled
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.lyricFontInputSyncAction
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.lyricFontSizeDoneAction
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.lyricOffsetTextColor
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.lyricPhoneticHint
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.lyricPhoneticSwitchAction
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.lyricSecondaryToggleDescription
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.lyricSecondaryToggleTitle
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.lyricTranslationToggleCopy
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveLyricOffsetSliderRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingLyricSettingsSheetTest {
    @Test
    fun `offset owner keeps an out of range saved value reachable and snaps slider changes`() {
        val outside = MAX_LYRIC_DEFAULT_OFFSET_MS + LYRIC_DEFAULT_OFFSET_STEP_MS
        val state = LyricBehaviorSheetState(outside)
        assertEquals(outside, state.sliderRange.max)
        assertEquals(MIN_LYRIC_DEFAULT_OFFSET_MS, state.sliderRange.min)

        state.setSliderValue(LYRIC_DEFAULT_OFFSET_STEP_MS * 1.6f)
        assertEquals(LYRIC_DEFAULT_OFFSET_STEP_MS * 2, state.currentOffset)
        assertEquals(resolveLyricOffsetSliderRange(state.currentOffset), state.sliderRange)

        val below =
            LyricBehaviorSheetState(MIN_LYRIC_DEFAULT_OFFSET_MS - LYRIC_DEFAULT_OFFSET_STEP_MS)
        assertEquals(MIN_LYRIC_DEFAULT_OFFSET_MS - LYRIC_DEFAULT_OFFSET_STEP_MS, below.sliderRange.min)
        assertEquals(MAX_LYRIC_DEFAULT_OFFSET_MS, below.sliderRange.max)
    }

    @Test
    fun `offset text and color preserve signed neutral behavior`() {
        assertEquals("+50 ms", formatLyricOffset(50))
        assertEquals("-50 ms", formatLyricOffset(-50))
        assertEquals("0 ms", formatLyricOffset(0))
        assertEquals(Color.Red, lyricOffsetTextColor(50, Color.Red, Color.Green, Color.Blue))
        assertEquals(Color.Green, lyricOffsetTextColor(-50, Color.Red, Color.Green, Color.Blue))
        assertEquals(Color.Blue, lyricOffsetTextColor(0, Color.Red, Color.Green, Color.Blue))
    }

    @Test
    fun `secondary lyric controls only advertise available content`() {
        assertEquals(CoreCommonR.string.lyrics_secondary_mode_phonetic,
            lyricSecondaryToggleTitle(false, true)
        )
        assertEquals(CoreCommonR.string.lyrics_phonetic_only_desc,
            lyricSecondaryToggleDescription(false, true)
        )
        assertEquals(
            LyricTranslationToggleCopy(
                CoreCommonR.string.lyrics_secondary_mode_phonetic,
                CoreCommonR.string.lyrics_phonetic_only_desc
            ),
            lyricTranslationToggleCopy(false, true)
        )
        assertEquals(CoreCommonR.string.settings_show_lyric_translation,
            lyricSecondaryToggleTitle(true, true)
        )
        assertEquals(CoreCommonR.string.settings_show_lyric_translation_desc,
            lyricSecondaryToggleDescription(false, false)
        )
        assertEquals(CoreCommonR.string.lyrics_translation_use_phonetic_requires_translation,
            lyricPhoneticHint(false, true)
        )
        assertEquals(CoreCommonR.string.lyrics_translation_use_phonetic_unavailable,
            lyricPhoneticHint(true, false)
        )
        assertEquals(CoreCommonR.string.lyrics_translation_use_phonetic_desc, lyricPhoneticHint(true, true))
        assertFalse(isLyricPhoneticSwitchEnabled(false, true))
        assertFalse(isLyricPhoneticSwitchEnabled(true, false))
        assertTrue(isLyricPhoneticSwitchEnabled(true, true))
        assertFalse(isLyricPhoneticSwitchChecked(true, true, false))
        assertTrue(isLyricPhoneticSwitchChecked(true, true, true))

        var changes = 0
        lyricPhoneticSwitchAction(false) { changes++ }(true)
        lyricPhoneticSwitchAction(true) { changes++ }(true)
        assertEquals(1, changes)
        var toggled: Boolean? = null
        booleanToggleClick(true) { toggled = it }()
        assertEquals(false, toggled)
    }

    @Test
    fun `font size owner normalizes both values and later external updates`() {
        val state = LyricFontSizeSheetState(0.1f, 2f)
        assertEquals(MIN_LYRIC_FONT_SCALE, state.lyricValue, 0.0001f)
        assertEquals(MAX_LYRIC_FONT_SCALE, state.translationValue, 0.0001f)
        state.updateTranslationFromSlider(1.3f)
        state.syncExternalInputs(1.1f, 2f)
        assertEquals(1.3f, state.translationValue, 0.0001f)
        state.syncExternalInputs(1.1f, 1.2f)
        assertEquals(1.1f, state.lyricValue, 0.0001f)
        assertEquals(1.2f, state.translationValue, 0.0001f)
        state.updateLyricFromSlider(1.3f)
        lyricFontInputSyncAction(state, 1.1f, 1.2f)()
        assertEquals(1.3f, state.lyricValue, 0.0001f)

        val calls = mutableListOf<String>()
        lyricFontSizeDoneAction(
            state,
            { calls += "lyric:$it" },
            { calls += "translation:$it" },
            { calls += "dismiss" }
        )()
        assertEquals(listOf("lyric:1.3", "translation:1.2", "dismiss"), calls)
    }
}
