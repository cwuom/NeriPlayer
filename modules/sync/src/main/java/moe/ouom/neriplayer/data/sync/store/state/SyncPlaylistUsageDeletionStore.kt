package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletion
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletionPolicy
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import java.util.UUID

internal class SyncPlaylistUsageDeletionStore(private val encryptedPrefs: SharedPreferences, private val files: SyncDeletionStateStorage) {
    private val mutation = SyncMutationVersionStore(encryptedPrefs)

    fun getPlaylistUsageDeletionBarriersConfirmed(): List<SyncPlaylistUsageDeletion> = synchronized(syncMutationLock) {
        check(files.confirm()) { "Failed to confirm playlist usage deletion barriers" }
        readBarriersLocked()
    }

    fun mergePlaylistUsageDeletionBarriers(deletions: List<SyncPlaylistUsageDeletion>) = synchronized(syncMutationLock) {
        persistBarriersLocked(SyncPlaylistUsageDeletionPolicy.merge(readBarriersLocked() + deletions))
    }

    fun mergePlaylistUsageDeletionBarriersIfMutationVersion(expected: Long, deletions: List<SyncPlaylistUsageDeletion>): Boolean = synchronized(syncMutationLock) {
        if (mutation.getSyncMutationVersion() != expected) return@synchronized false
        persistBarriersLocked(SyncPlaylistUsageDeletionPolicy.merge(readBarriersLocked() + deletions))
        true
    }

    private fun readBarriersLocked(): List<SyncPlaylistUsageDeletion> = SyncPlaylistUsageDeletionPolicy.merge(
        readStoredBarriersLocked() + SyncPlaylistUsageDeletionPolicy.fromLegacy(getPlaylistUsageDeletions())
    )

    private fun readStoredBarriersLocked(): List<SyncPlaylistUsageDeletion> {
        val stored = mutableListOf<SyncPlaylistUsageDeletion>()
        files.visitObjectArray(KEY_PLAYLIST_USAGE_DELETION_BARRIERS, JsonObject::class.java, {}) { fields ->
            stored.add(readBarrier(fields))
        }
        return SyncPlaylistUsageDeletionPolicy.merge(stored)
    }

    private fun readBarrier(fields: JsonObject): SyncPlaylistUsageDeletion {
        val key = fields.requiredText("playlistKey")
        check(key.isNotBlank()) { "Missing playlist usage deletion key" }
        return SyncPlaylistUsageDeletion(key, readDeletionTokens(fields), fields.requiredLong("deletedAt"))
    }

    private fun readDeletionTokens(fields: JsonObject): List<SyncCausalToken> {
        val tokens = checkNotNull(fields.get("deletionTokens")) { "Missing playlist usage deletion tokens" }
        check(tokens.isJsonArray) { "Playlist usage deletion tokens must be an array" }
        check(tokens.asJsonArray.size() > 0) { "Playlist usage deletion tokens must not be empty" }
        return tokens.asJsonArray.map(::readDeletionToken)
    }

    private fun readDeletionToken(value: JsonElement): SyncCausalToken {
        check(value.isJsonObject) { "Playlist usage deletion token must be an object" }
        val fields = value.asJsonObject
        val token = SyncCausalToken(fields.requiredText("deviceId"), fields.requiredLong("counter"))
        check(token.isValid()) { "Invalid playlist usage deletion token" }
        return token
    }

    private fun JsonObject.requiredPrimitive(name: String): JsonPrimitive {
        val value = checkNotNull(get(name)) { "Missing playlist usage deletion field: $name" }
        check(value.isJsonPrimitive) { "Playlist usage deletion field must be a primitive: $name" }
        return value.asJsonPrimitive
    }

    private fun JsonObject.requiredText(name: String): String {
        val value = requiredPrimitive(name)
        check(value.isString) { "Playlist usage deletion field must be a string: $name" }
        return value.asString
    }

    private fun JsonObject.requiredLong(name: String): Long {
        val value = requiredPrimitive(name)
        check(value.isNumber) { "Playlist usage deletion field must be a number: $name" }
        return value.asBigDecimal.longValueExact()
    }

    private fun encodeBarriers(deletions: List<SyncPlaylistUsageDeletion>): JsonArray? {
        if (deletions.isEmpty()) return null
        return JsonArray().apply {
            deletions.forEach { deletion -> add(JsonObject().apply {
                addProperty("playlistKey", deletion.playlistKey)
                addProperty("deletedAt", deletion.deletedAt)
                add("deletionTokens", JsonArray().apply {
                    deletion.deletionTokens.forEach { token -> add(JsonObject().apply {
                        addProperty("deviceId", token.deviceId)
                        addProperty("counter", token.counter)
                    }) }
                })
            }) }
        }
    }

    private fun persistBarriersLocked(deletions: List<SyncPlaylistUsageDeletion>) {
        val changed = deletions != readStoredBarriersLocked()
        check(files.commitEdit {
            if (changed) files.write(this, KEY_PLAYLIST_USAGE_DELETION_BARRIERS, encodeBarriers(deletions))
        }) { "Failed to persist playlist usage deletion barriers" }
    }

    fun getPlaylistUsageDeletions(): Map<String, Long> {
        return synchronized(syncMutationLock) {
            val type = object : TypeToken<Map<String?, Long?>>() {}.type
            val parsed = files.read<Map<String?, Long?>>(KEY_PLAYLIST_USAGE_DELETIONS, type).orEmpty()
            normalizePlaylistUsageDeletions(parsed)
        }
    }

    fun getPlaylistUsageDeletionsConfirmed(): Map<String, Long> {
        return synchronized(syncMutationLock) {
            // prefs 提交失败仍可能修改 RAM，先确认完整内存状态已写入磁盘
            check(files.confirm()) { "Failed to confirm playlist usage deletion state" }
            getPlaylistUsageDeletions()
        }
    }

    fun addPlaylistUsageDeletion(playlistKey: String, deletedAt: Long = System.currentTimeMillis()) {
        val normalizedKey = playlistKey.trim()
        if (normalizedKey.isEmpty()) {
            return
        }
        synchronized(syncMutationLock) {
            val current = getPlaylistUsageDeletions().toMutableMap()
            val normalizedTimestamp = deletedAt.coerceAtLeast(1L)
            current[normalizedKey] = maxOf(current[normalizedKey] ?: 0L, normalizedTimestamp)
            synchronized(syncCausalTokenLock) {
                val allocation = allocateDeletionTokenLocked()
                val token = allocation.copy(deviceId = "usage-delete:${allocation.deviceId}")
                val barriers = SyncPlaylistUsageDeletionPolicy.merge(
                    readBarriersLocked() + SyncPlaylistUsageDeletionPolicy.fromLegacy(current) +
                        SyncPlaylistUsageDeletion(normalizedKey, listOf(token), normalizedTimestamp)
                )
                commitDeletionLocked(allocation, current, barriers)
            }
        }
    }

    private fun allocateDeletionTokenLocked(): SyncCausalToken {
        val deviceId = encryptedPrefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val previousCounter = encryptedPrefs.getLong(KEY_SYNC_CAUSAL_COUNTER, 0L)
        check(previousCounter >= 0L) { "Stored sync causal counter is invalid" }
        return SyncCausalToken(deviceId, Math.addExact(previousCounter, 1L))
    }

    private fun commitDeletionLocked(
        allocation: SyncCausalToken,
        deletions: Map<String, Long>,
        barriers: List<SyncPlaylistUsageDeletion>
    ) {
        // token 分配和删除状态必须一起提交，失败时不能提前确认其中任何一部分
        check(files.commitEdit {
            putString(KEY_DEVICE_ID, allocation.deviceId)
            putLong(KEY_SYNC_CAUSAL_COUNTER, allocation.counter)
            files.write(this, KEY_PLAYLIST_USAGE_DELETIONS, normalizePlaylistUsageDeletions(deletions))
            files.write(this, KEY_PLAYLIST_USAGE_DELETION_BARRIERS, encodeBarriers(barriers))
            mutation.bump(this)
        }) { "Failed to persist playlist usage deletion state" }
    }

    fun removePlaylistUsageDeletion(playlistKey: String, bumpVersion: Boolean = true) {
        val normalizedKey = playlistKey.trim()
        if (normalizedKey.isEmpty()) {
            return
        }
        synchronized(syncMutationLock) {
            // 清除旧展示标记前固化桥接 token，真正的删除屏障永久保留
            val barriers = readBarriersLocked()
            val current = getPlaylistUsageDeletions().toMutableMap()
            val changed = current.remove(normalizedKey) != null
            persistPlaylistUsageDeletionsLocked(current, bumpVersion, changed, barriers)
        }
    }

    private fun persistPlaylistUsageDeletionsLocked(
        deletions: Map<String, Long>,
        bumpVersion: Boolean,
        changed: Boolean,
        barriers: List<SyncPlaylistUsageDeletion>
    ) {
        val barriersChanged = barriers != readStoredBarriersLocked()
        check(
            files.commitEdit {
                if (barriersChanged) files.write(this, KEY_PLAYLIST_USAGE_DELETION_BARRIERS, encodeBarriers(barriers))
                if (changed) {
                    val normalized = normalizePlaylistUsageDeletions(deletions)
                    files.write(this, KEY_PLAYLIST_USAGE_DELETIONS, normalized.takeIf { it.isNotEmpty() })
                    if (bumpVersion) mutation.bump(this)
                }
            }
        ) { "Failed to persist playlist usage deletion state" }
    }

    private fun normalizePlaylistUsageDeletions(
        deletions: Map<out String?, Long?>
    ): Map<String, Long> {
        val normalized = mutableMapOf<String, Long>()
        deletions.forEach { (key, timestamp) ->
            val normalizedKey = key?.trim()
            if (normalizedKey.isNullOrEmpty() || timestamp == null) {
                return@forEach
            }
            val normalizedTimestamp = timestamp.coerceAtLeast(1L)
            normalized[normalizedKey] = maxOf(
                normalized[normalizedKey] ?: 0L,
                normalizedTimestamp
            )
        }
        return normalized.entries
            .sortedByDescending { it.value }
            .associate { it.key to it.value }
    }
}
