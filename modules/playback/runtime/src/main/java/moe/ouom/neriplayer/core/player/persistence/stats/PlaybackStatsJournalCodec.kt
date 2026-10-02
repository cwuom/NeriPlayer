package moe.ouom.neriplayer.core.player.persistence.stats

import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsSnapshot
import moe.ouom.neriplayer.data.model.SongItem

internal object PlaybackStatsJournalCodec {
    private const val MAGIC = 0x4e505354
    private const val VERSION = 1
    private const val HEADER_BYTES = 16
    private const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private val gson = GsonBuilder().serializeNulls().create()

    fun payload(snapshot: PlaybackStatsSnapshot): ByteArray {
        if (snapshot.eventId.isBlank() || snapshot.eventId.length > 512 || snapshot.listenedMs < 0 || snapshot.playCountIncrement < 0) {
            throw IOException("Invalid playback journal event")
        }
        val value = JsonObject().apply {
            addProperty("eventId", snapshot.eventId)
            addProperty("listenedMs", snapshot.listenedMs)
            addProperty("playCountIncrement", snapshot.playCountIncrement)
            addProperty("scheduleSync", snapshot.scheduleSync)
            addProperty("playedAt", snapshot.playedAt)
            addProperty("observedClearedAt", snapshot.observedClearedAt)
            add("localPlaylistId", snapshot.localPlaylistId?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            add("song", songPayload(snapshot.song))
        }
        val text = StringWriter()
        val bounded = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {
                if (length > MAX_PAYLOAD_BYTES - text.buffer.length) throw IOException("Playback statistics metadata exceeds its journal budget")
                text.write(chars, offset, length)
            }
            override fun write(value: String, offset: Int, length: Int) {
                if (length > MAX_PAYLOAD_BYTES - text.buffer.length) throw IOException("Playback statistics metadata exceeds its journal budget")
                text.write(value, offset, length)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        try { gson.toJson(value, bounded) } catch (error: com.google.gson.JsonIOException) {
            throw IOException("Cannot encode playback statistics metadata", error)
        }
        val bytes = text.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PAYLOAD_BYTES) throw IOException("Playback statistics metadata exceeds its journal budget")
        return bytes
    }

    fun frame(payload: ByteArray): ByteArray = ByteArrayOutputStream(HEADER_BYTES + payload.size).also { bytes ->
        DataOutputStream(bytes).use {
            it.writeInt(MAGIC)
            it.writeInt(VERSION)
            it.writeInt(payload.size)
            it.writeInt(CRC32().apply { update(payload) }.value.toInt())
            it.write(payload)
        }
    }.toByteArray()

    fun read(file: File): PlaybackStatsSnapshot = decode(readPayload(file))

    fun readPayload(file: File): ByteArray = DataInputStream(file.inputStream().buffered()).use { input ->
        if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Unsupported playback journal frame")
        val length = input.readInt()
        val checksum = input.readInt()
        if (length <= 0 || length > MAX_PAYLOAD_BYTES || file.length() != HEADER_BYTES.toLong() + length) {
            throw IOException("Invalid playback journal frame length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        if (CRC32().apply { update(payload) }.value.toInt() != checksum) throw IOException("Playback journal checksum mismatch")
        payload
    }

    fun hash(payload: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decode(bytes: ByteArray): PlaybackStatsSnapshot {
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val value = JsonParser.parseString(text).asJsonObject
            val count = value.number("playCountIncrement")
            if (count !in 0..Int.MAX_VALUE.toLong()) throw IOException("Invalid playback journal play count")
            val snapshot = PlaybackStatsSnapshot(
                song = readSong(value.getAsJsonObject("song")),
                listenedMs = value.number("listenedMs"), playCountIncrement = count.toInt(),
                scheduleSync = value.get("scheduleSync").takeIf { it?.isJsonPrimitive == true && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean ?: throw IOException("Invalid playback journal sync flag"),
                localPlaylistId = value.optionalNumber("localPlaylistId"),
                eventId = value.text("eventId"), playedAt = value.number("playedAt"),
                observedClearedAt = value.number("observedClearedAt")
            )
            if (snapshot.eventId.isBlank() || snapshot.eventId.length > 512 || snapshot.listenedMs < 0) {
                throw IOException("Invalid playback journal event")
            }
            return snapshot
        } catch (error: RuntimeException) {
            throw IOException("Malformed playback journal payload", error)
        }
    }

    private fun songPayload(song: SongItem) = JsonObject().apply {
        addProperty("id", song.id)
        addProperty("albumId", song.albumId)
        addProperty("durationMs", song.durationMs)
        addProperty("name", song.name)
        addProperty("artist", song.artist)
        addProperty("album", song.album)
        for ((key, text) in listOf("coverUrl" to song.coverUrl, "mediaUri" to song.mediaUri,
            "customCoverUrl" to song.customCoverUrl, "customName" to song.customName,
            "customArtist" to song.customArtist, "localFileName" to song.localFileName,
            "localFilePath" to song.localFilePath, "channelId" to song.channelId,
            "audioId" to song.audioId, "subAudioId" to song.subAudioId, "sourceStableKey" to song.sourceStableKey)) {
            addProperty(key, text)
        }
    }

    private fun readSong(value: JsonObject) = SongItem(value.number("id"), value.text("name"),
        value.text("artist"), value.text("album"), value.number("albumId"), value.number("durationMs"),
        value.optionalText("coverUrl"), mediaUri = value.optionalText("mediaUri"),
        customCoverUrl = value.optionalText("customCoverUrl"), customName = value.optionalText("customName"),
        customArtist = value.optionalText("customArtist"), localFileName = value.optionalText("localFileName"),
        localFilePath = value.optionalText("localFilePath"), channelId = value.optionalText("channelId"),
        audioId = value.optionalText("audioId"), subAudioId = value.optionalText("subAudioId"),
        sourceStableKey = value.optionalText("sourceStableKey"))

    private fun JsonObject.text(key: String): String = optionalText(key)
        ?: throw IOException("Missing playback journal string: $key")

    private fun JsonObject.optionalText(key: String): String? {
        val value = get(key) ?: throw IOException("Missing playback journal field: $key")
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw IOException("Invalid playback journal string: $key")
        return value.asString
    }

    private fun JsonObject.number(key: String): Long = optionalNumber(key)
        ?: throw IOException("Missing playback journal number: $key")

    private fun JsonObject.optionalNumber(key: String): Long? {
        val value = get(key) ?: throw IOException("Missing playback journal field: $key")
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw IOException("Invalid playback journal number: $key")
        return value.asString.toLongOrNull() ?: throw IOException("Invalid playback journal integer: $key")
    }
}
