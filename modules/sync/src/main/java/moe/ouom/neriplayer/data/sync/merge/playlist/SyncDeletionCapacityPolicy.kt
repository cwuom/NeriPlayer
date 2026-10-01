package moe.ouom.neriplayer.data.sync.merge.playlist

import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion

internal object SyncDeletionCapacityPolicy {
    fun select(
        merged: List<SyncPlaylistSongDeletion>,
        maxCount: Int,
        order: Comparator<SyncPlaylistSongDeletion>
    ): List<SyncPlaylistSongDeletion> {
        val causal = merged.filter(::isCausal)
        val legacy = merged.filterNot(::isCausal)
        val preferredLegacy = preferredLegacyCapacity(causal.isEmpty(), legacy.isEmpty(), maxCount)
        var legacyCapacity = minOf(legacy.size, preferredLegacy)
        var causalCapacity = minOf(causal.size, maxCount - legacyCapacity)
        var remaining = maxCount - legacyCapacity - causalCapacity
        if (remaining > 0) {
            val extraLegacy = minOf(legacy.size - legacyCapacity, remaining)
            legacyCapacity += extraLegacy
            remaining -= extraLegacy
        }
        if (remaining > 0) causalCapacity += minOf(causal.size - causalCapacity, remaining)
        return (causal.take(causalCapacity) + legacy.take(legacyCapacity)).sortedWith(order)
    }

    private fun preferredLegacyCapacity(causalEmpty: Boolean, legacyEmpty: Boolean, maxCount: Int): Int = when {
        causalEmpty -> maxCount
        legacyEmpty -> 0
        else -> maxCount / 2
    }

    private fun isCausal(deletion: SyncPlaylistSongDeletion): Boolean = deletion.removedMembershipTokens.orEmpty().isNotEmpty()
}
