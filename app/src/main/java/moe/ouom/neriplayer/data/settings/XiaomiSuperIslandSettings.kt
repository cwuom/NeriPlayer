package moe.ouom.neriplayer.data.settings

import org.json.JSONObject

data class XiaomiSuperIslandSettings(
    val lyricTextMode: Int = TEXT_ORIGINAL,
    val lyricMode: Int = LYRIC_MODE_FULL,
    val fullLyricShowLeftCover: Boolean = false,
    val scrollingEnabled: Boolean = true,
    val rightTextChars: Int = 7,
    val leftWithCoverTextChars: Int = 6,
    val leftWithoutCoverTextChars: Int = 8,
    val textColorEnabled: Boolean = false,
    val colorSource: Int = COLOR_SOURCE_ALBUM,
    val customColor: Int = DEFAULT_CUSTOM_COLOR,
    val progressColorEnabled: Boolean = false,
    val actionStyle: Int = ACTION_STYLE_DISABLED,
    val notificationStyle: Int = NOTIFICATION_STYLE_STANDARD,
    val mediaButtonLayout: Int = MEDIA_BUTTON_LAYOUT_TWO,
    val clickStyle: Int = CLICK_STYLE_DEFAULT,
    val shareEnabled: Boolean = true,
    val shareFormat: Int = SHARE_FORMAT_LYRIC_AND_SONG,
    val xmsfBypassMode: Int = XMSF_MODE_STANDARD,
    val xmsfCustomDurationMs: Int = XMSF_STANDARD_DURATION_MS,
    val dismissDelayMs: Int = 0
) {
    fun sanitized(): XiaomiSuperIslandSettings = copy(
        lyricTextMode = lyricTextMode.coerceIn(TEXT_ORIGINAL, TEXT_PRONUNCIATION),
        lyricMode = lyricMode.coerceIn(LYRIC_MODE_STANDARD, LYRIC_MODE_FULL),
        rightTextChars = rightTextChars.coerceIn(6, 14),
        leftWithCoverTextChars = leftWithCoverTextChars.coerceIn(4, 10),
        leftWithoutCoverTextChars = leftWithoutCoverTextChars.coerceIn(6, 14),
        colorSource = colorSource.coerceIn(COLOR_SOURCE_ALBUM, COLOR_SOURCE_CUSTOM),
        customColor = customColor or (0xFF shl 24),
        actionStyle = actionStyle.coerceIn(ACTION_STYLE_DISABLED, ACTION_STYLE_MEDIA_CONTROLS),
        notificationStyle = NOTIFICATION_STYLE_STANDARD,
        mediaButtonLayout = mediaButtonLayout.coerceIn(
            MEDIA_BUTTON_LAYOUT_TWO,
            MEDIA_BUTTON_LAYOUT_THREE
        ),
        clickStyle = clickStyle.coerceIn(CLICK_STYLE_DEFAULT, CLICK_STYLE_OPEN_APP),
        shareFormat = shareFormat.coerceIn(
            SHARE_FORMAT_LYRIC_AND_SONG,
            SHARE_FORMAT_ARTIST_AND_SONG
        ),
        xmsfBypassMode = xmsfBypassMode.coerceIn(XMSF_MODE_DISABLED, XMSF_MODE_AGGRESSIVE),
        xmsfCustomDurationMs = xmsfCustomDurationMs.coerceIn(
            XMSF_CUSTOM_DURATION_MIN_MS,
            XMSF_CUSTOM_DURATION_MAX_MS
        ),
        dismissDelayMs = dismissDelayMs.takeIf { it in DISMISS_DELAYS_MS } ?: 0
    )

    fun encode(): String = JSONObject().apply {
        put("text", lyricTextMode)
        put("mode", lyricMode)
        put("leftCover", fullLyricShowLeftCover)
        put("scroll", scrollingEnabled)
        put("rightChars", rightTextChars)
        put("leftCoverChars", leftWithCoverTextChars)
        put("leftChars", leftWithoutCoverTextChars)
        put("colorize", textColorEnabled)
        put("colorSource", colorSource)
        put("customColor", customColor)
        put("progressColor", progressColorEnabled)
        put("actions", actionStyle)
        put("notificationStyle", notificationStyle)
        put("buttonLayout", mediaButtonLayout)
        put("click", clickStyle)
        put("share", shareEnabled)
        put("shareFormat", shareFormat)
        put("xmsf", xmsfBypassMode)
        put("xmsfDuration", xmsfCustomDurationMs)
        put("dismissDelay", dismissDelayMs)
    }.toString()

    companion object {
        const val TEXT_ORIGINAL = 0
        const val TEXT_TRANSLATION = 1
        const val TEXT_PRONUNCIATION = 2

        const val LYRIC_MODE_STANDARD = 0
        const val LYRIC_MODE_FULL = 1

        const val COLOR_SOURCE_ALBUM = 0
        const val COLOR_SOURCE_CUSTOM = 1
        const val DEFAULT_CUSTOM_COLOR = -0x00CB7D01

        const val ACTION_STYLE_DISABLED = 0
        const val ACTION_STYLE_MEDIA_CONTROLS = 1
        const val NOTIFICATION_STYLE_STANDARD = 0
        const val MEDIA_BUTTON_LAYOUT_TWO = 0
        const val MEDIA_BUTTON_LAYOUT_THREE = 1

        const val CLICK_STYLE_DEFAULT = 0
        const val CLICK_STYLE_MEDIA_CONTROLS = 1
        const val CLICK_STYLE_OPEN_APP = 2

        const val SHARE_FORMAT_LYRIC_AND_SONG = 0
        const val SHARE_FORMAT_INLINE = 1
        const val SHARE_FORMAT_ARTIST_AND_SONG = 2

        const val XMSF_MODE_DISABLED = 0
        const val XMSF_MODE_STANDARD = 1
        const val XMSF_MODE_CUSTOM = 2
        const val XMSF_MODE_AGGRESSIVE = 3
        const val XMSF_STANDARD_DURATION_MS = 100
        const val XMSF_CUSTOM_DURATION_MIN_MS = 100
        const val XMSF_CUSTOM_DURATION_MAX_MS = 500
        const val XMSF_CUSTOM_DURATION_STEP_MS = 50

        val DISMISS_DELAYS_MS = setOf(0, 1_000, 3_000, 5_000)

        fun decode(value: String?): XiaomiSuperIslandSettings {
            if (value.isNullOrBlank()) return XiaomiSuperIslandSettings()
            return runCatching {
                val json = JSONObject(value)
                XiaomiSuperIslandSettings(
                    lyricTextMode = json.optInt("text", TEXT_ORIGINAL),
                    lyricMode = json.optInt("mode", LYRIC_MODE_FULL),
                    fullLyricShowLeftCover = json.optBoolean("leftCover", false),
                    scrollingEnabled = json.optBoolean("scroll", true),
                    rightTextChars = json.optInt("rightChars", 7),
                    leftWithCoverTextChars = json.optInt("leftCoverChars", 6),
                    leftWithoutCoverTextChars = json.optInt("leftChars", 8),
                    textColorEnabled = json.optBoolean("colorize", false),
                    colorSource = json.optInt("colorSource", COLOR_SOURCE_ALBUM),
                    customColor = json.optInt("customColor", DEFAULT_CUSTOM_COLOR),
                    progressColorEnabled = json.optBoolean("progressColor", false),
                    actionStyle = json.optInt("actions", ACTION_STYLE_DISABLED),
                    notificationStyle = NOTIFICATION_STYLE_STANDARD,
                    mediaButtonLayout = json.optInt("buttonLayout", MEDIA_BUTTON_LAYOUT_TWO),
                    clickStyle = json.optInt("click", CLICK_STYLE_DEFAULT),
                    shareEnabled = json.optBoolean("share", true),
                    shareFormat = json.optInt("shareFormat", SHARE_FORMAT_LYRIC_AND_SONG),
                    xmsfBypassMode = json.optInt("xmsf", XMSF_MODE_STANDARD),
                    xmsfCustomDurationMs = json.optInt("xmsfDuration", XMSF_STANDARD_DURATION_MS),
                    dismissDelayMs = json.optInt("dismissDelay", 0)
                ).sanitized()
            }.getOrDefault(XiaomiSuperIslandSettings())
        }
    }
}
