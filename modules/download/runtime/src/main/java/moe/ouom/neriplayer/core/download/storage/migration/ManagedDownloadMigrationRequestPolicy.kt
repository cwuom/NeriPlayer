package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationRequest

internal fun mergeMigrationRequestForWorker(
    persisted: ManagedMigrationRequest?,
    input: ManagedMigrationRequest,
    inputKeys: Set<String>
): ManagedMigrationRequest {
    persisted ?: return input.normalized()

    fun hasInput(key: String): Boolean = key in inputKeys
    if (
        hasInput(ManagedDownloadMigrationWorker.KEY_FROM_DIRECTORY_URI) &&
            !ManagedDownloadStorage.areEquivalentDirectoryUris(
                persisted.fromDirectoryUri,
                input.fromDirectoryUri
            )
    ) {
        throw ManagedDownloadMigrationException.transient(
            "持久迁移请求与任务源目录不一致"
        )
    }
    if (
        hasInput(ManagedDownloadMigrationWorker.KEY_TO_DIRECTORY_URI) &&
            !ManagedDownloadStorage.areEquivalentDirectoryUris(
                persisted.toDirectoryUri,
                input.toDirectoryUri
            )
    ) {
        throw ManagedDownloadMigrationException.transient(
            "持久迁移请求与任务目标目录不一致"
        )
    }
    if (
        hasInput(ManagedDownloadMigrationWorker.KEY_TARGET_LABEL) &&
            persisted.targetLabel.isNotBlank() &&
            persisted.targetLabel != input.targetLabel
    ) {
        throw ManagedDownloadMigrationException.transient(
            "持久迁移请求与任务标签不一致"
        )
    }
    if (
        hasInput(ManagedDownloadMigrationWorker.KEY_RELEASE_PREVIOUS_PERMISSION) &&
            persisted.releasePreviousPermission != input.releasePreviousPermission
    ) {
        throw ManagedDownloadMigrationException.transient(
            "持久迁移请求与权限策略不一致"
        )
    }
    val checkpointWorkId = if (
        hasInput(ManagedDownloadMigrationWorker.KEY_CHECKPOINT_WORK_ID)
    ) {
        input.checkpointWorkId ?: persisted.checkpointWorkId
    } else {
        persisted.checkpointWorkId
    }
    return persisted.copy(
        workId = input.workId,
        fromDirectoryUri = if (
            hasInput(ManagedDownloadMigrationWorker.KEY_FROM_DIRECTORY_URI)
        ) {
            input.fromDirectoryUri
        } else {
            persisted.fromDirectoryUri
        },
        toDirectoryUri = if (
            hasInput(ManagedDownloadMigrationWorker.KEY_TO_DIRECTORY_URI)
        ) {
            input.toDirectoryUri
        } else {
            persisted.toDirectoryUri
        },
        targetLabel = if (
            hasInput(ManagedDownloadMigrationWorker.KEY_TARGET_LABEL)
        ) {
            input.targetLabel
        } else {
            persisted.targetLabel
        },
        releasePreviousPermission = if (
            hasInput(ManagedDownloadMigrationWorker.KEY_RELEASE_PREVIOUS_PERMISSION)
        ) {
            input.releasePreviousPermission
        } else {
            persisted.releasePreviousPermission
        },
        minimumSourceEntryCount = maxOf(
            persisted.minimumSourceEntryCount,
            input.minimumSourceEntryCount
        ),
        checkpointWorkId = checkpointWorkId,
        // 终态请求可能是上一次 Worker 被杀后重新执行，不能在合并输入时重新打开自动恢复
        autoResume = persisted.autoResume
    ).normalized()
}
