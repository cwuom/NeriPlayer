package moe.ouom.neriplayer.core.player.service

internal fun isFloatingLyricsEffectivelyEnabled(
    enabled: Boolean,
    temporarilyHidden: Boolean,
): Boolean {
    return enabled && !temporarilyHidden
}
internal fun resolveFloatingLyricsExternalTargetEnabled(
    currentEnabled: Boolean,
    legacyHideAction: Boolean,
): Boolean {
    return if (legacyHideAction) {
        false
    } else {
        !currentEnabled
    }
}
