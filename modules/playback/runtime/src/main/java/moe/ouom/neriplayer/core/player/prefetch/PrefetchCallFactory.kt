package moe.ouom.neriplayer.core.player.prefetch

import okhttp3.Call
import okhttp3.Request

/** Cancels only calls belonging to one server prefetch, including a blocked open/read. */
internal class PrefetchCallFactory(private val delegate: Call.Factory) : Call.Factory {
    private val calls = mutableListOf<Call>()
    private var cancelled = false

    override fun newCall(request: Request): Call = synchronized(this) {
        delegate.newCall(request).also {
            if (cancelled) it.cancel() else calls.add(it)
        }
    }

    fun cancel() = synchronized(this) {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }
}
