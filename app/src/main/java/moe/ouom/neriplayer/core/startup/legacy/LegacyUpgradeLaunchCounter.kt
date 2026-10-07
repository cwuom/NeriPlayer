package moe.ouom.neriplayer.core.startup.legacy

import java.util.UUID

/** 按启动次数记录失败；同一进程内的退避重试只计一次，持久值格式为 "次数|进程标识" */
internal class LegacyUpgradeLaunchCounter(
    private val processToken: String = PROCESS_TOKEN
) {
    fun failedLaunchesBeforeThisProcess(persisted: String?): Int {
        val count = persisted?.substringBefore('|')?.toIntOrNull()?.coerceAtLeast(0) ?: return 0
        return if (countedInThisProcess(persisted)) (count - 1).coerceAtLeast(0) else count
    }

    fun countedInThisProcess(persisted: String?): Boolean =
        persisted?.substringAfter('|', "") == processToken

    /** 当前进程第一次失败时返回新的持久值，已计过则返回 null */
    fun recordFailure(persisted: String?): String? {
        if (countedInThisProcess(persisted)) return null
        return "${failedLaunchesBeforeThisProcess(persisted) + 1}|$processToken"
    }

    companion object {
        private val PROCESS_TOKEN = UUID.randomUUID().toString()
    }
}
