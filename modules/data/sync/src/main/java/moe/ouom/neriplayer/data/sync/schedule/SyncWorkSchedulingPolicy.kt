package moe.ouom.neriplayer.data.sync.schedule

object SyncWorkSchedulingPolicy {
    fun canSchedule(triggerByUserAction: Boolean, configured: Boolean, autoSyncEnabled: Boolean): Boolean =
        triggerByUserAction || (configured && autoSyncEnabled)

    fun append(triggerByUserAction: Boolean, appendToCurrentWork: Boolean): Boolean =
        triggerByUserAction || appendToCurrentWork
}
