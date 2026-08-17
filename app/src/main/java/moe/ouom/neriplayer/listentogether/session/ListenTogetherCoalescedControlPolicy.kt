package moe.ouom.neriplayer.listentogether.session

import kotlinx.coroutines.Job

internal fun isCurrentListenTogetherCoalescedControlJob(
    currentJob: Job?,
    completingJob: Job
): Boolean = currentJob === completingJob
