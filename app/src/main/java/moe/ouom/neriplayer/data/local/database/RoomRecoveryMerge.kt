package moe.ouom.neriplayer.data.local.database

internal fun <Key, Value> mergeRoomRecoverySnapshot(
    roomSnapshot: List<Value>,
    recoveryBaseline: List<Value>,
    currentSnapshot: List<Value>,
    keyOf: (Value) -> Key,
    mergeLocalChange: (roomValue: Value?, currentValue: Value) -> Value
): List<Value> {
    val recovered = LinkedHashMap<Key, Value>()
    roomSnapshot.forEach { value ->
        recovered[keyOf(value)] = value
    }
    val baselineByKey = recoveryBaseline.associateBy(keyOf)
    val currentByKey = currentSnapshot.associateBy(keyOf)
    (baselineByKey.keys + currentByKey.keys).forEach { key ->
        val baselineValue = baselineByKey[key]
        val currentValue = currentByKey[key]
        when {
            currentValue == null && baselineValue != null -> recovered.remove(key)
            currentValue != null && currentValue != baselineValue -> {
                recovered[key] = mergeLocalChange(recovered[key], currentValue)
            }
        }
    }
    return recovered.values.toList()
}
