package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import java.util.UUID

internal class SyncDeviceStateStore(private val encryptedPrefs: SharedPreferences) {

    fun saveDeviceId(deviceId: String) {
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        synchronized(syncCausalTokenLock) {
            check(
                encryptedPrefs.commitEdit {
                    putString(KEY_DEVICE_ID, deviceId)
                }
            ) { "Failed to persist sync causal device state" }
        }
    }

    fun getDeviceId(): String? {
        return encryptedPrefs.getString(KEY_DEVICE_ID, null)
    }

    fun getOrCreateDeviceId(): String {
        return synchronized(syncCausalTokenLock) {
            val deviceId = deviceIdCandidate()
            // commit 失败也会改变内存，重新读取同一值不能替代持久确认
            saveDeviceId(deviceId)
            deviceId
        }
    }

    fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> {
        require(count >= 0) { "Token count must not be negative" }
        if (count == 0) return emptyList()

        return synchronized(syncCausalTokenLock) {
            val deviceId = deviceIdCandidate()
            val currentCounter = encryptedPrefs.getLong(KEY_SYNC_CAUSAL_COUNTER, 0L)
            check(currentCounter >= 0L) { "Stored sync causal counter is invalid" }
            val nextCounter = Math.addExact(currentCounter, count.toLong())
            // token 范围必须先落盘，避免崩溃后重复分配
            check(
                encryptedPrefs.commitEdit {
                    putString(KEY_DEVICE_ID, deviceId)
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

    private fun deviceIdCandidate(): String =
        getDeviceId()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
}
