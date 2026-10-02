package moe.ouom.neriplayer.data.backup

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.Reader
import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
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
    private val sanitizeBucket: (SyncPlaybackStatBucket) -> SyncPlaybackStatBucket? = { it },
    private val limits: BackupJsonLimits = BackupJsonLimits()
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
        var data = BackupManager.BackupData()
        val guardedInput = input.withBackupJsonBudget(limits) { context.ensureActive() }
        JsonReader(guardedInput).use { reader ->
            reader.strictness = Strictness.STRICT
            val seen = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                context.ensureActive()
                val field = reader.nextName()
                if (!seen.add(field)) throw IOException("Duplicate backup field: $field")
                data = readField(reader, field, data, sink) { guardedInput.charactersRead }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing backup content")
        }
        if (data.version !in SupportedVersions) throw IOException("Unsupported backup version: ${data.version}")
        context.ensureActive()
        return data
    }

    private suspend fun readField(reader: JsonReader, field: String, data: BackupManager.BackupData,
        sink: SyncPlaybackSink?, inputCharacters: () -> Long): BackupManager.BackupData = when (field) {
        "playbackStats" -> {
            if (sink == null) reader.skipValue()
            else readArray(reader, SyncTrackStat::class.java, inputCharacters) { sink.appendTracks(it.mapNotNull(sanitizeTrack)) }
            data
        }
        "playbackStatBuckets" -> {
            if (sink == null) reader.skipValue()
            else readArray(reader, SyncPlaybackStatBucket::class.java, inputCharacters) { sink.appendBuckets(it.mapNotNull(sanitizeBucket)) }
            data
        }
        in MetadataCollections -> readMetadataCollection(reader, field, data)
        else -> readMetadataScalar(reader, field, data)
    }

    private suspend fun readMetadataCollection(reader: JsonReader, field: String,
        data: BackupManager.BackupData): BackupManager.BackupData = when (field) {
        "playlists" -> data.copy(playlists = readMetadataArray(reader, SyncPlaylist::class.java))
        "recentPlays" -> data.copy(recentPlays = readMetadataArray(reader, SyncRecentPlay::class.java))
        "legacyLyricCandidates" -> data.copy(legacyLyricCandidates = readMetadataArray(reader, SyncSong::class.java))
        "lyricOverrides" -> data.copy(lyricOverrides = readMetadataArray(reader, SyncSong::class.java))
        else -> throw IOException("Unknown backup metadata collection")
    }

    private fun readMetadataScalar(reader: JsonReader, field: String,
        data: BackupManager.BackupData): BackupManager.BackupData = when (field) {
        "version" -> data.copy(version = gson.getAdapter(String::class.java).read(reader)
            ?: throw IOException("Unsupported backup version: null"))
        "timestamp" -> data.copy(timestamp = gson.getAdapter(Long::class.javaObjectType).read(reader) ?: data.timestamp)
        "playbackStatsClearedAt" -> data.copy(playbackStatsClearedAt =
            gson.getAdapter(Long::class.javaObjectType).read(reader) ?: data.playbackStatsClearedAt)
        "exportDate" -> data.copy(exportDate = gson.getAdapter(String::class.java).read(reader))
        else -> { reader.skipValue(); data }
    }

    private suspend fun <T> readMetadataArray(reader: JsonReader, type: Class<T>): List<T>? {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null }
        val records = ArrayList<T>()
        val adapter = gson.getAdapter(type)
        reader.beginArray()
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            records.add(adapter.read(reader))
        }
        reader.endArray()
        return records
    }

    private suspend fun <T> readArray(reader: JsonReader, type: Class<T>, inputCharacters: () -> Long,
        consume: suspend (List<T>) -> Unit) {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return }
        val adapter = gson.getAdapter(type)
        val page = ArrayList<T>(SYNC_PLAYBACK_PAGE_RECORDS)
        reader.beginArray()
        var pageStart = inputCharacters()
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            page.add(adapter.read(reader) ?: throw IOException("Null playback backup record"))
            // 输入计数包含少量预读，达到字符预算后先提交页，不让大记录按条数堆积
            if (page.size == SYNC_PLAYBACK_PAGE_RECORDS || inputCharacters() - pageStart >= MAX_STATISTICS_PAGE_CHARACTERS) {
                consume(page.toList())
                page.clear()
                pageStart = inputCharacters()
            }
        }
        reader.endArray()
        if (page.isNotEmpty()) consume(page)
    }

    private companion object {
        const val MAX_STATISTICS_PAGE_CHARACTERS = 4L * 1024 * 1024
        val SupportedVersions = setOf("2.0", "2.1", "2.2", "2.3")
        val MetadataCollections = setOf("playlists", "recentPlays", "legacyLyricCandidates", "lyricOverrides")
    }
}
