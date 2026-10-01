package moe.ouom.neriplayer.core.download.execution.scheduling.ids

import androidx.work.Configuration

private const val WORK_MANAGER_JOB_ID_MIN = 1_000
private const val WORK_MANAGER_JOB_ID_MAX = 99_999

// WorkManager 与 UIDT 共用系统调度器，编号范围必须互不重叠
fun newDownloadWorkManagerConfigurationBuilder(): Configuration.Builder = Configuration.Builder()
    .setJobSchedulerJobIdRange(WORK_MANAGER_JOB_ID_MIN, WORK_MANAGER_JOB_ID_MAX)
