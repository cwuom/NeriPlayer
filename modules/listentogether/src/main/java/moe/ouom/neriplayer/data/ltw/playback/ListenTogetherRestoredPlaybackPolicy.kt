package moe.ouom.neriplayer.data.ltw.playback

enum class ListenTogetherRestoredPlaybackAction {
    SKIP,
    RESUME_LOCAL_PLAYBACK,
    WAIT_FOR_AUTHORITATIVE_ROOM_STATE
}

fun resolveListenTogetherRestoredPlaybackAction(
    restoredPlaybackRequested: Boolean,
    listenTogetherSessionActive: Boolean,
    currentUserIsController: Boolean
): ListenTogetherRestoredPlaybackAction {
    if (!restoredPlaybackRequested) {
        return ListenTogetherRestoredPlaybackAction.SKIP
    }
    if (!listenTogetherSessionActive || currentUserIsController) {
        return ListenTogetherRestoredPlaybackAction.RESUME_LOCAL_PLAYBACK
    }
    return ListenTogetherRestoredPlaybackAction.WAIT_FOR_AUTHORITATIVE_ROOM_STATE
}
