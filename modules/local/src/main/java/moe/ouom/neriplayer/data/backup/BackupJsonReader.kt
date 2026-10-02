package moe.ouom.neriplayer.data.backup

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.FilterReader
import java.io.Reader
import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSink
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource

internal class BackupJsonContent(
    val data: BackupManager.BackupData,
    val statistics: SyncPlaybackSource
) : Closeable {
    override fun close() = statistics.close()
}

internal class BackupJsonReader(
    private val store: SyncPlaybackDatasetStore,
    private val sanitizeTrack: (SyncTrackStat) -> SyncTrackStat? = { it },
    private val sanitizeBucket: (SyncPlaybackStatBucket) -> SyncPlaybackStatBucket? = { it }
) {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()

    suspend fun read(reader: Reader): BackupJsonContent = store.newSink().use { sink ->
        val data = readData(reader, sink)
        val source = sink.seal()
        try {
            // 所有 JSON 和临时页校验完后，调用方才取得应用主存的所有权
            sink.close()
            BackupJsonContent(data, source)
        } catch (failure: Throwable) {
            try { source.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    suspend fun readMetadata(reader: Reader): BackupManager.BackupData = readData(reader, null)

    private suspend fun readData(input: Reader, sink: SyncPlaybackSink?): BackupManager.BackupData {
        try {
            return readValidatedData(input, sink)
        } catch (failure: JsonParseException) {
            // Gson 将读取中的 IllegalStateException 包装为格式错误，取消仍须原样传播
            val cause = failure.cause
            if (cause is CancellationException) throw cause
            throw failure
        }
    }

    private suspend fun readValidatedData(input: Reader, sink: SyncPlaybackSink?): BackupManager.BackupData {
        val context = currentCoroutineContext()
        val cancellable = object : FilterReader(input) {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                context.ensureActive()
                return super.read(buffer, offset, length)
            }
        }
        val metadata = JsonObject()
        JsonReader(cancellable).use { reader ->
            reader.strictness = Strictness.STRICT
            val seen = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                context.ensureActive()
                val field = reader.nextName()
                if (!seen.add(field)) throw IOException("Duplicate backup field: $field")
                readField(reader, field, metadata, sink)
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing backup content")
        }
        val data = gson.fromJson(metadata, BackupManager.BackupData::class.java)
        if (data.version !in SupportedVersions) throw IOException("Unsupported backup version: ${data.version}")
        context.ensureActive()
        return data
    }

    private suspend fun readField(reader: JsonReader, field: String, metadata: JsonObject, sink: SyncPlaybackSink?) {
        when (field) {
            "playbackStats" -> if (sink == null) reader.skipValue()
                else readArray(reader, SyncTrackStat::class.java) { sink.appendTracks(it.mapNotNull(sanitizeTrack)) }
            "playbackStatBuckets" -> if (sink == null) reader.skipValue()
                else readArray(reader, SyncPlaybackStatBucket::class.java) { sink.appendBuckets(it.mapNotNull(sanitizeBucket)) }
            in MetadataFields -> metadata.add(field, gson.getAdapter(JsonElement::class.java).read(reader))
            else -> reader.skipValue()
        }
    }

    private suspend fun <T> readArray(reader: JsonReader, type: Class<T>, consume: suspend (List<T>) -> Unit) {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return }
        val adapter = gson.getAdapter(type)
        val page = ArrayList<T>(SYNC_PLAYBACK_PAGE_RECORDS)
        reader.beginArray()
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            page.add(adapter.read(reader) ?: throw IOException("Null playback backup record"))
            if (page.size == SYNC_PLAYBACK_PAGE_RECORDS) {
                consume(page.toList())
                page.clear()
            }
        }
        reader.endArray()
        if (page.isNotEmpty()) consume(page)
    }

    private companion object {
        val SupportedVersions = setOf("2.0", "2.1", "2.2", "2.3")
        val MetadataFields = setOf("version", "timestamp", "playlists", "recentPlays", "playbackStatsClearedAt",
            "legacyLyricCandidates", "lyricOverrides", "exportDate")
    }
}
