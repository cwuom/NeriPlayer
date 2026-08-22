package moe.ouom.neriplayer.core.download.execution

import android.app.job.JobParameters
import android.os.Build

internal fun shouldRescheduleUidtJob(
    stopReason: Int,
    sdkInt: Int
): Boolean {
    return sdkInt < Build.VERSION_CODES.S ||
        stopReason != JobParameters.STOP_REASON_USER
}
