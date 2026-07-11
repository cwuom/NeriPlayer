package moe.ouom.neriplayer.util

import java.util.Collections
import java.util.IdentityHashMap

fun Throwable.isTransientHttp2StreamReset(): Boolean {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        if (current.matchesTransientHttp2StreamReset()) return true
        current.cause?.let(pending::add)
        current.suppressed.forEach(pending::add)
    }
    return false
}

private fun Throwable.matchesTransientHttp2StreamReset(): Boolean {
    val message = message.orEmpty()
    val isStreamReset = this::class.java.name == "okhttp3.internal.http2.StreamResetException" ||
        message.startsWith("stream was reset:", ignoreCase = true)
    if (!isStreamReset) return false
    return message.contains("CANCEL", ignoreCase = true) ||
        message.contains("REFUSED_STREAM", ignoreCase = true)
}
