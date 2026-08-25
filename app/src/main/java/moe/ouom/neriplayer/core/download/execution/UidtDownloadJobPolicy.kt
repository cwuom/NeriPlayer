package moe.ouom.neriplayer.core.download.execution

import android.app.job.JobParameters
import android.os.Build
import androidx.annotation.RequiresApi

internal enum class UidtStopAction {
    RETRY_WITHOUT_CANCELLING_BACKENDS,
    STOP_AND_CANCEL_BACKENDS
}

@RequiresApi(Build.VERSION_CODES.S)
internal fun shouldRescheduleUidtJob(
    stopReason: Int,
    sdkInt: Int
): Boolean {
    return sdkInt < Build.VERSION_CODES.S ||
        stopReason != JobParameters.STOP_REASON_USER &&
        stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP
}

@RequiresApi(Build.VERSION_CODES.S)
internal fun shouldMarkUidtJobStopped(
    stopReason: Int,
    sdkInt: Int
): Boolean {
    return sdkInt >= Build.VERSION_CODES.S &&
        stopReason == JobParameters.STOP_REASON_USER
}

@RequiresApi(Build.VERSION_CODES.S)
internal fun resolveUidtStopAction(
    stopReason: Int,
    sdkInt: Int
): UidtStopAction {
    return if (shouldRescheduleUidtJob(stopReason, sdkInt)) {
        UidtStopAction.RETRY_WITHOUT_CANCELLING_BACKENDS
    } else {
        UidtStopAction.STOP_AND_CANCEL_BACKENDS
    }
}
