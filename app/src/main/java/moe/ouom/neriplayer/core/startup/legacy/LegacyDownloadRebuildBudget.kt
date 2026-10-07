package moe.ouom.neriplayer.core.startup.legacy

import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity

/**
 * 旧下载载荷已结清、但目录扫描持续无法发布时，限制升级横幅停留的启动次数
 *
 * 升级状态会挡住下载目录切换，Provider 长期列举不全或 metadata 不可读时用户无法自行脱困；
 * 后续任意一次成功扫描仍会正常发布目录
 */
internal class LegacyDownloadRebuildBudget(
    private val dao: SyncMetadataDao,
    private val counter: LegacyUpgradeLaunchCounter = LegacyUpgradeLaunchCounter()
) {
    /** 记录本次启动的发布失败，返回 true 表示已连续多次启动失败，应结束升级状态 */
    suspend fun recordFailureAndCheckExhausted(operationId: String): Boolean {
        val key = KEY_PREFIX + operationId
        val persisted = dao.getMigrationMetadata(key)?.value
        if (counter.failedLaunchesBeforeThisProcess(persisted) >= FINISH_AFTER_FAILED_LAUNCHES) return true
        counter.recordFailure(persisted)?.let { next ->
            dao.upsertMigrationMetadata(
                MigrationMetadataEntity(key = key, value = next, updatedAt = System.currentTimeMillis())
            )
        }
        return false
    }

    suspend fun forget(operationId: String) {
        dao.deleteMigrationMetadata(listOf(KEY_PREFIX + operationId))
    }

    private companion object {
        const val KEY_PREFIX = "legacy_download_upgrade_rebuild_failures:"
        const val FINISH_AFTER_FAILED_LAUNCHES = 2
    }
}
