package moe.ouom.neriplayer.core.download.presentation.progress

import android.content.Context
import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearProgressStore
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow
import moe.ouom.neriplayer.data.model.download.DownloadExecutionRequest

object DownloadProgressReadAccess {
    const val WAITING_STORAGE_MUTATION_STATE = WAITING_STORAGE_MUTATION_OPERATION_STATE
    val reusableOperationStates: List<String> get() = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES

    fun isTaskProgressClearActive(context: Context): Boolean =
        PersistentDownloadClearFenceStore.isTaskProgressActive(context)

    fun readClearProgress(context: Context): DownloadClearVisibility.ClearProgress? =
        PersistentDownloadClearProgressStore.read(context)

    suspend fun readOperationHeaders(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase
    ): Map<String, DownloadOperationHeaderRow> =
        DownloadExecutionRoomStore.readOperationHeaders(context, operationIds, database)

    suspend fun listPendingRequests(
        context: Context,
        states: List<String>,
        excludeUserStoppedOperations: Boolean,
        database: NeriUserDataDatabase
    ): List<DownloadExecutionRequest> = DownloadExecutionRoomStore.listByStates(
        context, states, excludeUserStoppedOperations, database
    ).map { entry -> entry.request }
}
