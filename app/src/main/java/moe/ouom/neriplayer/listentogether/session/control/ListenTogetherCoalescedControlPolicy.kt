package moe.ouom.neriplayer.listentogether.session.control

import kotlinx.coroutines.Job

internal fun isCurrentListenTogetherCoalescedControlJob(
    currentJob: Job?,
    completingJob: Job
): Boolean = currentJob === completingJob
