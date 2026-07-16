package moe.ouom.neriplayer.core.player.service

internal fun isFloatingLyricsEffectivelyEnabled(
    enabled: Boolean,
    temporarilyHidden: Boolean,
): Boolean {
    return enabled && !temporarilyHidden
}
