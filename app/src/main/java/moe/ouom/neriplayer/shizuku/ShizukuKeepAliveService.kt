package moe.ouom.neriplayer.shizuku

import android.content.Context
import android.os.Binder
import androidx.annotation.Keep

/** Minimal daemon user service whose lifetime is owned by Shizuku. */
@Keep
class ShizukuKeepAliveService(@Suppress("UNUSED_PARAMETER") context: Context) : Binder()
