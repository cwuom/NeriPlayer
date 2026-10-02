package moe.ouom.neriplayer.core.player.service.notification

import android.os.Build

/**
 * 魅族（Flyme）状态栏歌词使用的两个私有通知 flag 取值。
 *
 * 适配说明：https://open.flyme.cn/docs?id=239
 */
internal data class FlymeStatusBarLyricFlags(
    val alwaysShowTicker: Int,
    val onlyUpdateTicker: Int,
)

/**
 * 当前歌词状态下可以写入通知的魅族 ticker 参数。
 */
internal data class FlymeStatusBarLyricTicker(
    val text: String,
    val alwaysShowTicker: Int,
    val onlyUpdateTicker: Int,
)

/** 魅族通知的私有 extras key，见适配说明。 */
internal const val FLYME_TICKER_ICON_KEY = "ticker_icon"
internal const val FLYME_TICKER_ICON_SWITCH_KEY = "ticker_icon_switch"

internal const val FLYME_MANUFACTURER = "meizu"

/**
 * AOSP 从未在 `android.app.Notification` 上定义这两个常量（4.4 到 master 均无，
 * 且 AOSP 的通知 flag 只用到 0x00040000），它们是魅族在框架里私有新增的。
 *
 * 保留数值作为兜底：隐藏 API 反射在 Android 9+ 可能被拦，
 * 真魅族机上反射失败时不能把功能误判为不支持。
 */
internal const val FLYME_FLAG_ALWAYS_SHOW_TICKER_FALLBACK = 0x01000000
internal const val FLYME_FLAG_ONLY_UPDATE_TICKER_FALLBACK = 0x02000000

internal fun isFlymeDevice(manufacturer: String?, brand: String?): Boolean =
    listOf(manufacturer, brand).any { value ->
        value?.trim()?.equals(FLYME_MANUFACTURER, ignoreCase = true) == true
    }

/**
 * 反射读取魅族私有的两个 flag，取不到返回 null。
 *
 * 失败既可能是机型不支持，也可能是隐藏 API 限制，所以它只能作为正向证据，
 * 不能单独用来否定支持。
 */
internal fun readFlymeStatusBarLyricFlags(): FlymeStatusBarLyricFlags? = runCatching {
    val notificationClass = Class.forName("android.app.Notification")
    FlymeStatusBarLyricFlags(
        alwaysShowTicker = notificationClass.getField("FLAG_ALWAYS_SHOW_TICKER").getInt(null),
        onlyUpdateTicker = notificationClass.getField("FLAG_ONLY_UPDATE_TICKER").getInt(null),
    )
}.getOrNull()

/**
 * 反射成功即证明当前框架提供了这两个 flag；反射失败时以厂商名兜底。
 */
internal fun resolveFlymeStatusBarLyricFlags(
    reflected: FlymeStatusBarLyricFlags?,
    manufacturer: String?,
    brand: String?,
): FlymeStatusBarLyricFlags? {
    if (reflected != null) {
        return reflected
    }
    if (!isFlymeDevice(manufacturer, brand)) {
        return null
    }
    return FlymeStatusBarLyricFlags(
        alwaysShowTicker = FLYME_FLAG_ALWAYS_SHOW_TICKER_FALLBACK,
        onlyUpdateTicker = FLYME_FLAG_ONLY_UPDATE_TICKER_FALLBACK,
    )
}

/**
 * 只有「机型支持」「开关打开」「已加载到一行歌词」且「正在播放」时才写入 ticker，否则返回 null。
 *
 * 暂停必须收起歌词：暂停后播放位置不再推进，歌词行会停留在最后一行，
 * 若继续写 ticker，状态栏歌词就不会消失。魅族自带播放器与主流音乐应用都是暂停即收起的。
 */
internal fun resolveFlymeStatusBarLyricTicker(
    lyricState: StatusBarLyricNotificationState,
    flags: FlymeStatusBarLyricFlags?,
    playing: Boolean,
): FlymeStatusBarLyricTicker? {
    if (flags == null || !lyricState.hasTicker || !playing) {
        return null
    }
    return FlymeStatusBarLyricTicker(
        // hasTicker 已经保证 line 非空
        text = requireNotNull(lyricState.line),
        alwaysShowTicker = flags.alwaysShowTicker,
        onlyUpdateTicker = flags.onlyUpdateTicker,
    )
}

/**
 * 进程内缓存探测结果：适配说明要求反射字段只需访问一次。
 *
 * 设置界面要对不支持的设备隐藏状态栏歌词开关，所以 [isSupported] 是跨模块公开的。
 */
object FlymeStatusBarLyricSupport {

    internal val flags: FlymeStatusBarLyricFlags? by lazy {
        resolveFlymeStatusBarLyricFlags(
            reflected = readFlymeStatusBarLyricFlags(),
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
        )
    }

    val isSupported: Boolean
        get() = flags != null
}
