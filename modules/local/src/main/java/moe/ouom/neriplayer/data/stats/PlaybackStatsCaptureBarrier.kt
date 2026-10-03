package moe.ouom.neriplayer.data.stats

import android.content.Context

object PlaybackStatsCaptureBarrier {
    @Volatile
    private var binding = Binding({}, { _, block -> block() })

    fun install(flush: suspend (Context) -> Unit) {
        install(flush) { context, block -> flush(context); block() }
    }

    fun install(flush: suspend (Context) -> Unit, restore: suspend (Context, suspend () -> Unit) -> Unit) {
        binding = Binding(flush, restore)
    }

    suspend fun await(context: Context) {
        binding.flush(context)
    }

    suspend fun withRestore(context: Context, block: suspend () -> Unit) {
        binding.restore(context, block)
    }

    private class Binding(
        val flush: suspend (Context) -> Unit,
        val restore: suspend (Context, suspend () -> Unit) -> Unit
    )
}
