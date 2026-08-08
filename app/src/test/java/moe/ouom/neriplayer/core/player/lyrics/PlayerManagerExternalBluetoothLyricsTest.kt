package moe.ouom.neriplayer.core.player.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerExternalBluetoothLyricsTest {
    @Test
    fun dynamicIslandForcesExternalTranslatedLyrics() {
        assertTrue(
            shouldProvideExternalTranslatedLyricLine(
                externalBluetoothTranslationEnabled = false,
                floatingLyricsEnabled = false,
                floatingLyricsShowTranslation = false,
                dynamicIslandLyricsEnabled = true
            )
        )
    }

    @Test
    fun externalTranslatedLyricsStillFollowNormalAndFloatingRules() {
        assertTrue(
            shouldProvideExternalTranslatedLyricLine(
                externalBluetoothTranslationEnabled = true,
                floatingLyricsEnabled = false,
                floatingLyricsShowTranslation = false,
                dynamicIslandLyricsEnabled = false
            )
        )
        assertTrue(
            shouldProvideExternalTranslatedLyricLine(
                externalBluetoothTranslationEnabled = false,
                floatingLyricsEnabled = true,
                floatingLyricsShowTranslation = true,
                dynamicIslandLyricsEnabled = false
            )
        )
        assertFalse(
            shouldProvideExternalTranslatedLyricLine(
                externalBluetoothTranslationEnabled = false,
                floatingLyricsEnabled = true,
                floatingLyricsShowTranslation = false,
                dynamicIslandLyricsEnabled = false
            )
        )
        assertFalse(
            shouldProvideExternalTranslatedLyricLine(
                externalBluetoothTranslationEnabled = false,
                floatingLyricsEnabled = false,
                floatingLyricsShowTranslation = false,
                dynamicIslandLyricsEnabled = false
            )
        )
    }
}
