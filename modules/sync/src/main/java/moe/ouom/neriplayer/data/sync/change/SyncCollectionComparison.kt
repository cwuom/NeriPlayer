package moe.ouom.neriplayer.data.sync.change

internal object SyncCollectionComparison {
    fun <T> orderedChanged(remote: List<T>, merged: List<T>, same: (T, T) -> Boolean): Boolean {
        if (remote.size != merged.size) return true
        return remote.indices.any { index -> !same(remote[index], merged[index]) }
    }

    fun <T, TKey> keyedChanged(
        remote: List<T>,
        merged: List<T>,
        key: (T) -> TKey,
        same: (T, T) -> Boolean
    ): Boolean {
        val remoteMap = remote.associateBy(key)
        val mergedMap = merged.associateBy(key)
        if (remoteMap.keys != mergedMap.keys) return true
        return remoteMap.any { (key, value) ->
            val mergedValue = mergedMap[key] ?: return@any true
            !same(value, mergedValue)
        }
    }
}
