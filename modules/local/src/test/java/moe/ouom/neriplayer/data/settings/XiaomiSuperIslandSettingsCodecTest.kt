package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class XiaomiSuperIslandSettingsCodecTest {
    @Test
    fun `missing or blank payloads decode to defaults`() {
        assertEquals(XiaomiSuperIslandSettings(), XiaomiSuperIslandSettings.decode(null))
        assertEquals(XiaomiSuperIslandSettings(), XiaomiSuperIslandSettings.decode("   "))
    }

    @Test
    fun `malformed payloads decode to defaults`() {
        assertEquals(XiaomiSuperIslandSettings(), XiaomiSuperIslandSettings.decode("{\"text\":"))
    }

    @Test
    fun `encoded settings survive a round trip`() {
        val settings = XiaomiSuperIslandSettings(
            lyricTextMode = XiaomiSuperIslandSettings.TEXT_TRANSLATION,
            lyricMode = XiaomiSuperIslandSettings.LYRIC_MODE_STANDARD,
            fullLyricShowLeftCover = true,
            scrollingEnabled = false,
            rightTextChars = 10,
            leftWithCoverTextChars = 5,
            leftWithoutCoverTextChars = 12,
            textColorEnabled = true,
            colorSource = XiaomiSuperIslandSettings.COLOR_SOURCE_CUSTOM,
            customColor = 0xFF123456.toInt(),
            progressColorEnabled = true,
            actionStyle = XiaomiSuperIslandSettings.ACTION_STYLE_MEDIA_CONTROLS,
            mediaButtonLayout = XiaomiSuperIslandSettings.MEDIA_BUTTON_LAYOUT_THREE,
            clickStyle = XiaomiSuperIslandSettings.CLICK_STYLE_OPEN_APP,
            shareEnabled = false,
            shareFormat = XiaomiSuperIslandSettings.SHARE_FORMAT_INLINE,
            xmsfBypassMode = XiaomiSuperIslandSettings.XMSF_MODE_CUSTOM,
            xmsfCustomDurationMs = 250,
            dismissDelayMs = 3_000
        )

        assertEquals(settings, XiaomiSuperIslandSettings.decode(settings.encode()))
    }

    @Test
    fun `decoded values are clamped to supported ranges`() {
        val decoded = XiaomiSuperIslandSettings.decode(
            """
            {"text":9,"mode":-1,"rightChars":99,"leftCoverChars":1,"leftChars":2,
             "colorSource":5,"customColor":1193046,"actions":7,"notificationStyle":3,
             "buttonLayout":4,"click":-3,"shareFormat":8,"xmsf":11,"xmsfDuration":10,
             "dismissDelay":2000}
            """.trimIndent()
        )

        assertEquals(
            XiaomiSuperIslandSettings(
                lyricTextMode = XiaomiSuperIslandSettings.TEXT_PRONUNCIATION,
                lyricMode = XiaomiSuperIslandSettings.LYRIC_MODE_STANDARD,
                rightTextChars = 14,
                leftWithCoverTextChars = 4,
                leftWithoutCoverTextChars = 6,
                colorSource = XiaomiSuperIslandSettings.COLOR_SOURCE_CUSTOM,
                customColor = 0xFF123456.toInt(),
                actionStyle = XiaomiSuperIslandSettings.ACTION_STYLE_MEDIA_CONTROLS,
                notificationStyle = XiaomiSuperIslandSettings.NOTIFICATION_STYLE_STANDARD,
                mediaButtonLayout = XiaomiSuperIslandSettings.MEDIA_BUTTON_LAYOUT_THREE,
                clickStyle = XiaomiSuperIslandSettings.CLICK_STYLE_DEFAULT,
                shareFormat = XiaomiSuperIslandSettings.SHARE_FORMAT_ARTIST_AND_SONG,
                xmsfBypassMode = XiaomiSuperIslandSettings.XMSF_MODE_AGGRESSIVE,
                xmsfCustomDurationMs = XiaomiSuperIslandSettings.XMSF_CUSTOM_DURATION_MIN_MS,
                dismissDelayMs = 0
            ),
            decoded
        )
    }

    @Test
    fun `sanitizing keeps supported dismiss delays and forces an opaque custom color`() {
        val sanitized = XiaomiSuperIslandSettings(customColor = 0x00ABCDEF, dismissDelayMs = 5_000).sanitized()

        assertEquals(0xFFABCDEF.toInt(), sanitized.customColor)
        assertEquals(5_000, sanitized.dismissDelayMs)
    }
}
