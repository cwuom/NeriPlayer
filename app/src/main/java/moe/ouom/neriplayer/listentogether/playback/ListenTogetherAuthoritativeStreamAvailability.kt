package moe.ouom.neriplayer.listentogether.playback

internal data class ListenTogetherAuthoritativeStreamTarget(
    val roomId: String,
    val stableKey: String
)

internal class ListenTogetherAuthoritativeStreamAvailability {
    private data class UnavailableRecord(
        val target: ListenTogetherAuthoritativeStreamTarget,
        val signalCount: Int,
        val lastSignalId: String?
    ) {
        val isConfirmed: Boolean
            get() = signalCount >= REQUIRED_UNAVAILABLE_SIGNAL_COUNT
    }

    private val lock = Any()
    private var unavailableRecord: UnavailableRecord? = null

    fun markUnavailable(
        roomId: String?,
        stableKey: String?,
        signalId: String? = null
    ): Boolean = synchronized(lock) {
        val target = targetOrNull(roomId, stableKey) ?: run {
            unavailableRecord = null
            return@synchronized false
        }
        val normalizedSignalId = signalId?.trim()?.takeIf { it.isNotEmpty() }
        val previous = unavailableRecord
        unavailableRecord = when {
            previous?.target != target -> UnavailableRecord(
                target = target,
                signalCount = 1,
                lastSignalId = normalizedSignalId
            )

            normalizedSignalId.isNullOrBlank() ||
                normalizedSignalId == previous.lastSignalId -> previous

            else -> previous.copy(
                signalCount = previous.signalCount + 1,
                lastSignalId = normalizedSignalId
            )
        }
        unavailableRecord?.isConfirmed == true
    }

    fun reconcile(
        roomId: String?,
        stableKey: String?,
        hasAuthoritativeStream: Boolean
    ) = synchronized(lock) {
        val unavailable = unavailableRecord ?: return@synchronized
        val currentTarget = targetOrNull(roomId, stableKey)
        if (hasAuthoritativeStream || currentTarget != unavailable.target) {
            unavailableRecord = null
        }
    }

    fun isUnavailable(roomId: String?, stableKey: String?): Boolean = synchronized(lock) {
        val currentTarget = targetOrNull(roomId, stableKey)
        return@synchronized unavailableRecord?.target == currentTarget &&
            unavailableRecord?.isConfirmed == true
    }

    fun isAwaitingConfirmation(roomId: String?, stableKey: String?): Boolean = synchronized(lock) {
        val currentTarget = targetOrNull(roomId, stableKey)
        return@synchronized unavailableRecord?.target == currentTarget &&
            unavailableRecord?.isConfirmed == false
    }

    fun clear() = synchronized(lock) {
        unavailableRecord = null
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

    private companion object {
        const val REQUIRED_UNAVAILABLE_SIGNAL_COUNT = 2
    }
}
