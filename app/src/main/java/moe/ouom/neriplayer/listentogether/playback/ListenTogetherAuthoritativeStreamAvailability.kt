package moe.ouom.neriplayer.listentogether.playback

internal data class ListenTogetherAuthoritativeStreamTarget(
    val roomId: String,
    val stableKey: String
)

internal class ListenTogetherAuthoritativeStreamAvailability {
    @Volatile
    private var unavailableTarget: ListenTogetherAuthoritativeStreamTarget? = null

    fun markUnavailable(roomId: String?, stableKey: String?) {
        unavailableTarget = targetOrNull(roomId, stableKey)
    }

    fun reconcile(
        roomId: String?,
        stableKey: String?,
        hasAuthoritativeStream: Boolean
    ) {
        val unavailable = unavailableTarget ?: return
        val currentTarget = targetOrNull(roomId, stableKey)
        if (hasAuthoritativeStream || currentTarget != unavailable) {
            unavailableTarget = null
        }
    }

    fun isUnavailable(roomId: String?, stableKey: String?): Boolean {
        return unavailableTarget == targetOrNull(roomId, stableKey)
    }

    fun clear() {
        unavailableTarget = null
    }

    private fun targetOrNull(
        roomId: String?,
        stableKey: String?
    ): ListenTogetherAuthoritativeStreamTarget? {
        val normalizedRoomId = roomId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedStableKey = stableKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ListenTogetherAuthoritativeStreamTarget(
            roomId = normalizedRoomId,
            stableKey = normalizedStableKey
        )
    }
}
