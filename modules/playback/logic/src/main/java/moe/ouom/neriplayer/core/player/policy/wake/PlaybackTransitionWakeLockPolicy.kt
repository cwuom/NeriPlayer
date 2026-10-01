package moe.ouom.neriplayer.core.player.policy.wake

const val PLAYBACK_TRANSITION_WAKE_LOCK_LEASE_MS = 30_000L

fun shouldReleasePlaybackTransitionWakeLock(
    requestToken: Long,
    activeRequestToken: Long?
): Boolean = activeRequestToken == requestToken
