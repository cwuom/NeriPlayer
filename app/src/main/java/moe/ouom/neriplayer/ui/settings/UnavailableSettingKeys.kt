package moe.ouom.neriplayer.ui.settings

import moe.ouom.neriplayer.core.player.service.notification.FlymeStatusBarLyricSupport

/** `status_bar_lyrics_enabled`，定义见 AutoSettingsSchema.lyrics.statusBarLyrics。 */
internal const val STATUS_BAR_LYRICS_SETTING_KEY = "status_bar_lyrics_enabled"

/**
 * 设备能力决定哪些设置项不提供入口 —— 与用户偏好无关。
 *
 * 探测逻辑见 [FlymeStatusBarLyricSupport]：只有魅族框架支持状态栏歌词时，
 * 这个开关才有意义，其余机型上直接不展示。
 */
internal fun resolveUnavailableSettingKeys(statusBarLyricsSupported: Boolean): Set<String> = buildSet {
    if (!statusBarLyricsSupported) {
        add(STATUS_BAR_LYRICS_SETTING_KEY)
    }
}

/** 当前设备上不提供入口的设置项；设备能力不会在运行期改变，解析一次即可。 */
internal val unavailableSettingKeys: Set<String> =
    resolveUnavailableSettingKeys(FlymeStatusBarLyricSupport.isSupported)
