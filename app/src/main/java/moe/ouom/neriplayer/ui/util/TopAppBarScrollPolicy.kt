package moe.ouom.neriplayer.ui.util

internal fun shouldAllowCollapsingTopAppBar(
    canScrollForward: Boolean,
    canScrollBackward: Boolean
): Boolean = canScrollForward || canScrollBackward
