package moe.ouom.neriplayer.common.collections

/** 保留同一身份首次出现的条目，达到结果上限后停止读取后续输入 */
fun <T, K> Iterable<T>.mergeDistinctBy(
    incoming: Iterable<T>,
    limit: Int = Int.MAX_VALUE,
    keySelector: (T) -> K
): List<T> = (asSequence() + incoming.asSequence())
    .distinctBy(keySelector)
    .take(limit)
    .toList()
