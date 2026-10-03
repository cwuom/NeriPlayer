package moe.ouom.neriplayer.data.sync.merge.stats

object SyncPlaybackCounterArithmetic {
    fun add(left: Long, right: Long): Long {
        val a = left.coerceAtLeast(0L)
        val b = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }

    fun add(left: Int, right: Int): Int {
        val a = left.coerceAtLeast(0)
        val b = right.coerceAtLeast(0)
        return if (Int.MAX_VALUE - a < b) Int.MAX_VALUE else a + b
    }
}
