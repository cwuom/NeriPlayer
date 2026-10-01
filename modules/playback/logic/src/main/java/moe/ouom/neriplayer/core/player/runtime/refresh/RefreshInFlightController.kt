package moe.ouom.neriplayer.core.player.runtime.refresh

class RefreshInFlightController<T> {
    private class ActiveRefresh<T>(
        val request: RefreshRequestHandle,
        val operation: T,
        val cancel: (T) -> Unit
    )

    private var active: ActiveRefresh<T>? = null

    fun startOrReuse(
        semantics: RefreshRequestSemantics,
        start: (RefreshRequestHandle) -> T,
        cancel: (T) -> Unit
    ): RefreshInFlightStart<T> {
        return synchronized(this) {
            val current = active
            if (current?.request?.semantics == semantics) {
                return@synchronized RefreshInFlightStart(current.request, current.operation, startedNew = false)
            }
            cancelLocked()
            val request = RefreshRequestHandle(semantics)
            val operation = start(request)
            active = ActiveRefresh(request, operation, cancel)
            RefreshInFlightStart(request, operation, startedNew = true)
        }
    }

    fun cancelIfNotReusable(semantics: RefreshRequestSemantics): Boolean {
        return synchronized(this) {
            val current = active ?: return@synchronized false
            if (current.request.semantics == semantics) {
                return@synchronized false
            }
            cancelLocked()
            true
        }
    }

    fun cancelIfPlaybackIntentChanged(shouldResumePlayback: Boolean): Boolean {
        return synchronized(this) {
            val current = active ?: return@synchronized false
            if (current.request.semantics.resumePlaybackAfterRefresh == shouldResumePlayback) {
                return@synchronized false
            }
            cancelLocked()
            true
        }
    }

    fun isCurrent(request: RefreshRequestHandle): Boolean =
        synchronized(this) { active?.request === request }

    fun currentSemantics(): RefreshRequestSemantics? {
        return synchronized(this) { active?.request?.semantics }
    }

    fun clear(request: RefreshRequestHandle) {
        synchronized(this) {
            // 参数相同的新请求仍有独立身份，旧请求的结束回调不能清除它
            if (active?.request === request) active = null
        }
    }

    fun cancelCurrent() {
        synchronized(this) { cancelLocked() }
    }

    private fun cancelLocked() {
        val previous = active
        active = null
        previous?.cancel?.invoke(previous.operation)
    }
}
