package moe.ouom.neriplayer.core.player.policy.wake

internal const val PLAYBACK_TRANSITION_WAKE_LOCK_LEASE_MS = 30_000L

internal fun shouldReleasePlaybackTransitionWakeLock(
    requestToken: Long,
    activeRequestToken: Long?
): Boolean = activeRequestToken == requestToken
