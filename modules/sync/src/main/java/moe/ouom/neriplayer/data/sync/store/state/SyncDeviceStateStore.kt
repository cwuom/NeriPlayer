package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import java.util.UUID

internal class SyncDeviceStateStore(private val encryptedPrefs: SharedPreferences) {

    fun saveDeviceId(deviceId: String) {
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        synchronized(syncCausalTokenLock) {
            val deviceChanged = getDeviceId() != deviceId
            check(
                encryptedPrefs.commitEdit {
                    putString(KEY_DEVICE_ID, deviceId)
                    if (deviceChanged) {
                        remove(KEY_SYNC_CAUSAL_COUNTER)
                    }
                }
            ) { "Failed to persist sync causal device state" }
        }
    }

    fun getDeviceId(): String? {
        return encryptedPrefs.getString(KEY_DEVICE_ID, null)
    }

    fun getOrCreateDeviceId(): String {
        return synchronized(syncCausalTokenLock) {
            getDeviceId()
                ?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also(::saveDeviceId)
        }
    }

    fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> {
        require(count >= 0) { "Token count must not be negative" }
        if (count == 0) return emptyList()

        return synchronized(syncCausalTokenLock) {
            val deviceId = getOrCreateDeviceId()
            val currentCounter = encryptedPrefs.getLong(KEY_SYNC_CAUSAL_COUNTER, 0L)
            check(currentCounter >= 0L) { "Stored sync causal counter is invalid" }
            val nextCounter = Math.addExact(currentCounter, count.toLong())
            // token 范围必须先落盘，避免崩溃后重复分配
            check(
                encryptedPrefs.commitEdit {
                    putLong(KEY_SYNC_CAUSAL_COUNTER, nextCounter)
                }
            ) { "Failed to persist sync causal counter" }

            List(count) { index ->
                SyncCausalToken(
                    deviceId = deviceId,
                    counter = currentCounter + index + 1L
                )
            }
        }
    }
}
