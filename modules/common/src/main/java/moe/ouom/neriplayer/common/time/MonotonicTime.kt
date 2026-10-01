package moe.ouom.neriplayer.common.time

/** 起点必须来自 System.nanoTime()，不能混用墙上时间 */
fun elapsedMillisSince(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000L
