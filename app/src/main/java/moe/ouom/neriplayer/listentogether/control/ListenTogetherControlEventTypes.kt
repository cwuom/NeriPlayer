package moe.ouom.neriplayer.listentogether.control

internal val controllerHeartbeatRecoveryTypes = setOf(
    "MEMBER_JOINED",
    "PLAY",
    "PAUSE",
    "SEEK",
    "PLAYBACK_MODE",
    "SET_TRACK",
    "SET_QUEUE",
    "REQUEST_PLAY",
    "REQUEST_PAUSE",
    "REQUEST_SEEK",
    "REQUEST_PLAYBACK_MODE",
    "REQUEST_SET_TRACK"
)

internal val passivePositionUpdateTypes = setOf(
    "HEARTBEAT",
    "WATCHDOG",
    "WATCHDOG_STALL",
    "LINK_READY",
    "MEMBER_JOINED",
    "MEMBER_LEFT"
)

internal val controlledPlaybackCommandTypes = setOf(
    "PLAY_PLAYLIST",
    "PLAY_FROM_QUEUE",
    "NEXT",
    "PREVIOUS",
    "PLAY",
    "PAUSE",
    "PLAYBACK_MODE",
    "SEEK"
)

internal val requestControlEventTypes = setOf(
    "REQUEST_PLAY",
    "REQUEST_PAUSE",
    "REQUEST_SEEK",
    "REQUEST_PLAYBACK_MODE",
    "REQUEST_SET_TRACK"
)

internal val trackBoundRequestControlEventTypes = setOf(
    "REQUEST_PLAY",
    "REQUEST_PAUSE",
    "REQUEST_SEEK"
)
