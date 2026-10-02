package moe.ouom.neriplayer.data.local.database.store


import moe.ouom.neriplayer.data.local.database.entity.stats.PlaylistUsageEntity
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.playlist.usage.usageKey
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import moe.ouom.neriplayer.data.model.sync.normalizedSyncCausalTokens
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import java.io.IOException
import java.io.StringReader

internal fun UsageEntry.toEntity(): PlaylistUsageEntity {
    return PlaylistUsageEntity(
        usageKey = usageKey(),
        id = id,
        name = name,
        picUrl = picUrl,
        trackCount = trackCount,
        source = source,
        lastOpened = lastOpened,
        openCount = openCount,
        firstOpened = firstOpened,
        counterBaseOpenCount = counterBaseOpenCount,
        fid = fid,
        mid = mid,
        browseId = browseId,
        playlistId = playlistId,
        subtype = subtype,
        subtitle = subtitle,
        usageDeletionTokensJson = encodeUsageDeletionTokens(observedDeletionTokens)
    )
}

private fun encodeUsageDeletionTokens(observed: List<SyncCausalToken>): String? {
    val tokens = observed.normalizedSyncCausalTokens()
    if (tokens.isEmpty()) return null
    val encoded = JsonArray()
    for (token in tokens) {
        val fields = JsonObject()
        fields.addProperty("deviceId", token.deviceId)
        fields.addProperty("counter", token.counter)
        encoded.add(fields)
    }
    return encoded.toString()
}

internal fun decodeUsageDeletionTokens(json: String?): List<SyncCausalToken> {
    if (json == null) return emptyList()
    try {
        return readUsageDeletionTokenArray(json).map(::readUsageDeletionToken).normalizedSyncCausalTokens()
    } catch (error: Exception) {
        throw IOException("Invalid playlist usage deletion proof", error)
    }
}

private fun readUsageDeletionTokenArray(json: String): JsonArray {
    return JsonReader(StringReader(json)).use { reader ->
        reader.strictness = Strictness.STRICT
        val parsed = JsonParser.parseReader(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing playlist usage deletion proof" }
        require(parsed.isJsonArray) { "Playlist usage deletion proof must be an array" }
        parsed.asJsonArray
    }
}

private fun readUsageDeletionToken(value: JsonElement): SyncCausalToken {
    require(value.isJsonObject) { "Playlist usage deletion token must be an object" }
    val fields = value.asJsonObject
    val device = fields.usageDeletionPrimitive("deviceId")
    val counter = fields.usageDeletionPrimitive("counter")
    require(device.isString) { "Playlist usage deletion device must be a string" }
    require(counter.isNumber) { "Playlist usage deletion counter must be a number" }
    val token = SyncCausalToken(device.asString, counter.asBigDecimal.longValueExact())
    require(token.isValid()) { "Invalid playlist usage deletion token" }
    return token
}

private fun JsonObject.usageDeletionPrimitive(name: String): JsonPrimitive {
    val value = requireNotNull(get(name)) { "Missing playlist usage deletion field: $name" }
    require(value.isJsonPrimitive) { "Playlist usage deletion field must be a primitive: $name" }
    return value.asJsonPrimitive
}
