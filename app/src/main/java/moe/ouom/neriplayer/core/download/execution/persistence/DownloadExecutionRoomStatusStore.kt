package moe.ouom.neriplayer.core.download.execution.persistence

import android.content.Context
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.stableKey

/**
 * Room operation 的状态查询边界，避免状态读取和取消写入互相耦合
 */
internal object DownloadExecutionRoomStatusStore {
    suspend fun isStopped(
        context: Context,
        operationId: String
    ): Boolean {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .isUserStopped(operationId) == true
    }

    suspend fun isUserCancellationRequested(
        context: Context,
        operationId: String
    ): Boolean {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .isUserCancellationRequested(operationId)
    }

    suspend fun isExplicitResumePending(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return database.downloadOperationDao().isExplicitResumePending(operationId)
    }

    suspend fun isExecutionOwned(
        context: Context,
        operationId: String,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        return database.downloadOperationDao().isExecutionOwnedAnyLibrary(
            operationId = operationId,
            stableKey = normalizedKey
        )
    }

    suspend fun stoppedSongKeys(context: Context): Set<String> {
        val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
        return dao.findUserStoppedHeaders()
            .mapNotNull { header ->
                DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header).request?.song?.stableKey()
            }
            .toSet()
    }

}
