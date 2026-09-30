package moe.ouom.neriplayer.core.player.service.notification

internal fun isFloatingLyricsEffectivelyEnabled(
    enabled: Boolean,
): Boolean {
    return enabled
}

internal fun nextFloatingLyricsEnabled(currentEnabled: Boolean): Boolean {
    return !currentEnabled
}

internal fun resolveFloatingLyricsExternalTargetEnabled(
    currentEnabled: Boolean,
    legacyHideAction: Boolean,
): Boolean {
    return if (legacyHideAction) {
        false
    } else {
        nextFloatingLyricsEnabled(currentEnabled)
    }
}
