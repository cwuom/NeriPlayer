package moe.ouom.neriplayer.data.local.database.maintenance

import android.content.Context
import java.lang.ref.WeakReference

/** 仓库只发出维护请求，任务调度和下载升级由宿主负责 */
object LegacyJsonCleanupRequests {
    private val dispatcher = LegacyJsonCleanupDispatcher<Context>()

    fun bind(schedule: (Context, String) -> Unit) = dispatcher.bind(schedule)

    fun schedule(context: Context, reason: String = "repository-maintenance") {
        dispatcher.schedule(context.applicationContext, reason)
    }
}

internal class LegacyJsonCleanupDispatcher<T : Any> {
    private val lock = Any()
    private var scheduler: ((T, String) -> Unit)? = null
    private var pendingContext: WeakReference<T>? = null
    private var pendingReason: String? = null

    fun bind(schedule: (T, String) -> Unit) {
        val pending = synchronized(lock) {
            scheduler = schedule
            val context = pendingContext?.get()
            val reason = pendingReason
            pendingContext = null
            pendingReason = null
            if (context != null && reason != null) context to reason else null
        }
        pending?.let { (context, reason) -> schedule(context, reason) }
    }

    fun schedule(context: T, reason: String) {
        val schedule = synchronized(lock) {
            scheduler.also { installed ->
                if (installed == null) {
                    pendingContext = WeakReference(context)
                    pendingReason = reason
                }
            }
        }
        schedule?.invoke(context, reason)
    }
}
