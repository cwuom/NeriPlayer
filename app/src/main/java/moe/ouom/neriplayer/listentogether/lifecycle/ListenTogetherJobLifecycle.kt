package moe.ouom.neriplayer.listentogether.lifecycle

import kotlinx.coroutines.Job

internal fun cancelListenTogetherBackgroundJobs(vararg jobs: Job?) {
    jobs.forEach { it?.cancel() }
}
