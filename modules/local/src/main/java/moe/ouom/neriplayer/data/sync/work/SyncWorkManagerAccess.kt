package moe.ouom.neriplayer.data.sync.work

import android.content.Context
import androidx.work.WorkManager

internal fun syncWorkManager(context: Context): WorkManager = WorkManager.getInstance(context)
