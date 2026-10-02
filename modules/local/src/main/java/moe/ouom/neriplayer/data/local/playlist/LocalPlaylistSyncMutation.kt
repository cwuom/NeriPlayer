package moe.ouom.neriplayer.data.local.playlist

import androidx.annotation.Keep
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.atomic.AtomicLong

@Keep
internal data class PlaylistSongDeletionRemoval(
    val playlistId: Long,
    val identities: List<SongIdentity>
)

@Keep
internal data class LocalPlaylistSyncMutation(
    val expectedPrimaryDigest: String = "",
    val addedSongDeletions: List<SyncPlaylistSongDeletion> = emptyList(),
    val removedSongDeletions: List<PlaylistSongDeletionRemoval> = emptyList(),
    val deletedPlaylistIds: List<Long> = emptyList(),
    val clearedPlaylistDeletionIds: List<Long> = emptyList(),
    val restoredPlaylistIds: List<Long> = emptyList()
) {
    val isEmpty: Boolean
        get() = addedSongDeletions.isEmpty() &&
            removedSongDeletions.isEmpty() &&
            deletedPlaylistIds.isEmpty() &&
            clearedPlaylistDeletionIds.isEmpty() &&
            restoredPlaylistIds.orEmpty().isEmpty()

    fun withExpectedPrimaryDigest(digest: String): LocalPlaylistSyncMutation {
        return copy(expectedPrimaryDigest = digest)
    }

    operator fun plus(other: LocalPlaylistSyncMutation): LocalPlaylistSyncMutation {
        if (isEmpty) return other
        if (other.isEmpty) return this
        return LocalPlaylistSyncMutation(
            addedSongDeletions = addedSongDeletions + other.addedSongDeletions,
            removedSongDeletions = removedSongDeletions + other.removedSongDeletions,
            deletedPlaylistIds = (deletedPlaylistIds + other.deletedPlaylistIds).distinct(),
            clearedPlaylistDeletionIds =
                (clearedPlaylistDeletionIds + other.clearedPlaylistDeletionIds).distinct(),
            restoredPlaylistIds = (
                restoredPlaylistIds.orEmpty() + other.restoredPlaylistIds.orEmpty()
            ).distinct()
        )
    }
}

@Keep
internal data class LocalPlaylistSyncMutationOutbox(
    val mutations: List<LocalPlaylistSyncMutation> = emptyList()
)

internal fun decodeLocalPlaylistSyncMutation(text: String): LocalPlaylistSyncMutation {
    return readPlaylistMutationDocument(text, ::readPlaylistMutation)
}

internal fun decodeLocalPlaylistSyncMutationOutbox(text: String): LocalPlaylistSyncMutationOutbox {
    return readPlaylistMutationDocument(text) { fields ->
        val mutations = if (fields.has("mutations")) {
            fields.mutationArray("mutations").map(::readPlaylistMutation)
        } else {
            listOf(readPlaylistMutation(fields))
        }
        LocalPlaylistSyncMutationOutbox(mutations)
    }
}

private fun <T> readPlaylistMutationDocument(text: String, decode: (JsonObject) -> T): T {
    try {
        return JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            val parsed = JsonParser.parseReader(reader)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing playlist sync mutation content" }
            decode(parsed.mutationObject())
        }
    } catch (error: Exception) {
        throw IOException("Invalid durable playlist sync mutation", error)
    }
}

private val PLAYLIST_MUTATION_DIGEST_PATTERN = Regex("[0-9a-f]{64}")

private fun readPlaylistMutation(value: JsonElement): LocalPlaylistSyncMutation {
    val fields = value.mutationObject()
    val digest = fields.mutationString("expectedPrimaryDigest")
    require(PLAYLIST_MUTATION_DIGEST_PATTERN.matches(digest)) { "Invalid playlist mutation commit digest" }
    return LocalPlaylistSyncMutation(
        expectedPrimaryDigest = digest,
        addedSongDeletions = fields.mutationArray("addedSongDeletions").map(::readPlaylistSongDeletion),
        removedSongDeletions = fields.mutationArray("removedSongDeletions").map(::readPlaylistSongDeletionRemoval),
        deletedPlaylistIds = fields.mutationArray("deletedPlaylistIds").map(::readPlaylistMutationLong),
        clearedPlaylistDeletionIds = fields.mutationArray("clearedPlaylistDeletionIds").map(::readPlaylistMutationLong),
        restoredPlaylistIds = fields.optionalMutationArray("restoredPlaylistIds").map(::readPlaylistMutationLong)
    )
}

private fun readPlaylistSongDeletion(value: JsonElement): SyncPlaylistSongDeletion {
    val fields = value.mutationObject()
    return SyncPlaylistSongDeletion(
        playlistId = fields.mutationLong("playlistId"),
        songId = fields.mutationLong("songId"),
        album = fields.mutationString("album"),
        mediaUri = fields.nullableMutationString("mediaUri"),
        deletedAt = fields.mutationLong("deletedAt"),
        deviceId = fields.mutationString("deviceId"),
        removedMembershipTokens = fields.optionalMutationArray("removedMembershipTokens").map(::readPlaylistMutationToken)
    )
}

private fun readPlaylistSongDeletionRemoval(value: JsonElement): PlaylistSongDeletionRemoval {
    val fields = value.mutationObject()
    return PlaylistSongDeletionRemoval(
        playlistId = fields.mutationLong("playlistId"),
        identities = fields.mutationArray("identities").map(::readPlaylistMutationIdentity)
    )
}

private fun readPlaylistMutationIdentity(value: JsonElement): SongIdentity {
    val fields = value.mutationObject()
    return SongIdentity(fields.mutationLong("id"), fields.mutationString("album"), fields.nullableMutationString("mediaUri"))
}

private fun readPlaylistMutationToken(value: JsonElement): SyncCausalToken {
    val fields = value.mutationObject()
    val token = SyncCausalToken(fields.mutationString("deviceId"), fields.mutationLong("counter"))
    require(token.isValid()) { "Invalid playlist mutation membership token" }
    return token
}

private fun JsonElement.mutationObject(): JsonObject {
    require(isJsonObject) { "Playlist mutation record must be an object" }
    return asJsonObject
}

private fun JsonObject.mutationField(name: String): JsonElement {
    return requireNotNull(get(name)) { "Missing playlist mutation field: $name" }
}

private fun JsonObject.mutationArray(name: String): List<JsonElement> {
    val value = mutationField(name)
    require(value.isJsonArray) { "Playlist mutation field must be an array: $name" }
    return value.asJsonArray.toList()
}

private fun JsonObject.optionalMutationArray(name: String): List<JsonElement> {
    return if (has(name)) mutationArray(name) else emptyList()
}

private fun JsonObject.mutationString(name: String): String {
    return readPlaylistMutationString(mutationField(name))
}

private fun JsonObject.nullableMutationString(name: String): String? {
    val value = get(name) ?: return null
    return if (value.isJsonNull) null else readPlaylistMutationString(value)
}

private fun readPlaylistMutationString(value: JsonElement): String {
    require(value.isJsonPrimitive) { "Playlist mutation string must be a primitive" }
    require(value.asJsonPrimitive.isString) { "Playlist mutation field must be a string" }
    return value.asString
}

private fun JsonObject.mutationLong(name: String): Long = readPlaylistMutationLong(mutationField(name))

private fun readPlaylistMutationLong(value: JsonElement): Long {
    require(value.isJsonPrimitive) { "Playlist mutation number must be a primitive" }
    require(value.asJsonPrimitive.isNumber) { "Playlist mutation field must be a number" }
    return value.asBigDecimal.longValueExact()
}

internal interface LocalPlaylistSyncMutationStore {
    fun getOrCreateDeviceId(): String

    fun nextSyncCausalTokens(count: Int): List<SyncCausalToken>

    fun getSyncMutationVersion(): Long

    fun markSyncMutation(): Long

    fun apply(mutation: LocalPlaylistSyncMutation)

    /** 将墓碑落盘和本地版本推进绑定到同一次存储提交 */
    fun applyAndMarkMutation(mutation: LocalPlaylistSyncMutation): Long {
        apply(mutation)
        return markSyncMutation()
    }
}

internal class SecureLocalPlaylistSyncMutationStore(
    private val storage: SecureTokenStorage
) : LocalPlaylistSyncMutationStore {
    override fun getOrCreateDeviceId(): String = storage.getOrCreateDeviceId()

    override fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> {
        return storage.nextSyncCausalTokens(count)
    }

    override fun getSyncMutationVersion(): Long = storage.getSyncMutationVersion()

    override fun markSyncMutation(): Long = storage.markSyncMutation()

    override fun apply(mutation: LocalPlaylistSyncMutation) {
        if (mutation.addedSongDeletions.isNotEmpty()) {
            storage.addPlaylistSongDeletions(mutation.addedSongDeletions)
        }
        mutation.removedSongDeletions.forEach { removal ->
            storage.removePlaylistSongDeletions(removal.playlistId, removal.identities)
        }
        mutation.deletedPlaylistIds.forEach(storage::addDeletedPlaylistId)
        mutation.clearedPlaylistDeletionIds.forEach(storage::removePlaylistSongDeletionsForPlaylist)
        storage.removeDeletedPlaylistIds(mutation.restoredPlaylistIds.orEmpty().toSet())
    }

    override fun applyAndMarkMutation(mutation: LocalPlaylistSyncMutation): Long {
        return storage.applyPlaylistSyncMutation(
            addedSongDeletions = mutation.addedSongDeletions,
            removedSongDeletions = mutation.removedSongDeletions.map { removal ->
                removal.playlistId to removal.identities
            },
            deletedPlaylistIds = mutation.deletedPlaylistIds,
            clearedPlaylistDeletionIds = mutation.clearedPlaylistDeletionIds,
            restoredPlaylistIds = mutation.restoredPlaylistIds.orEmpty().toSet()
        )
    }
}

internal class InMemoryLocalPlaylistSyncMutationStore : LocalPlaylistSyncMutationStore {
    private val nextCounter = AtomicLong(1L)
    private val mutationVersion = AtomicLong(0L)

    override fun getOrCreateDeviceId(): String = "in-memory-sync-device"

    override fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> {
        require(count >= 0)
        return List(count) {
            SyncCausalToken(
                deviceId = getOrCreateDeviceId(),
                counter = nextCounter.getAndIncrement()
            )
        }
    }

    override fun getSyncMutationVersion(): Long = mutationVersion.get()

    override fun markSyncMutation(): Long = mutationVersion.incrementAndGet()

    override fun apply(mutation: LocalPlaylistSyncMutation) = Unit

    override fun applyAndMarkMutation(mutation: LocalPlaylistSyncMutation): Long {
        return markSyncMutation()
    }
}
