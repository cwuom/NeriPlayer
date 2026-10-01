package moe.ouom.neriplayer.data.ltw.session.control

import kotlinx.coroutines.Job

internal fun isCurrentListenTogetherCoalescedControlJob(
    currentJob: Job?,
    completingJob: Job
): Boolean = currentJob === completingJob
