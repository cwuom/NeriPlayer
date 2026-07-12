package moe.ouom.neriplayer.util

object NightModeHelper {
    fun applyNightMode(
        followSystemDark: Boolean,
        forceDark: Boolean
    ) {
        moe.ouom.neriplayer.util.platform.NightModeHelper.applyNightMode(
            followSystemDark = followSystemDark,
            forceDark = forceDark
        )
    }
}
