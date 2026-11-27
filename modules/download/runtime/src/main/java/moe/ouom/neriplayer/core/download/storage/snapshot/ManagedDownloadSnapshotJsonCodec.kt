package moe.ouom.neriplayer.core.download.storage.snapshot

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.Reader
import java.io.Writer
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.readJsonObject
import moe.ouom.neriplayer.core.download.storage.writeJsonValue
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata

internal object ManagedDownloadSnapshotJsonCodec {
    fun write(
        output: Writer,
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ) {
        // 快照可能包含整库歌词，逐条写入以避免分配整份 JSON 和 UTF-8 字节数组
        with(JsonWriter(output)) {
            beginObject()
            name("cacheKey").value(cacheKey)
            name("audioEntries").writeEntries(snapshot.audioEntries)
            name("pendingAudioEntries").writeEntries(snapshot.pendingAudioEntries)
            name("metadataEntries").writeEntries(snapshot.metadataEntriesByAudioName.values)
            name("metadataByAudioName").writeMetadata(snapshot.metadataByAudioName)
            name("pendingMetadataByAudioName").writeMetadata(snapshot.pendingMetadataByAudioName)
            name("coverEntries").writeEntries(snapshot.coverEntriesByName.values)
            name("lyricEntries").writeEntries(snapshot.lyricEntriesByName.values)
            name("rootEntriesComplete").value(snapshot.rootEntriesComplete)
            name("sidecarEntriesComplete").value(snapshot.sidecarEntriesComplete)
            endObject()
        }
    }

    fun read(
        input: Reader,
        expectedKey: String?
    ): Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? {
        val reader = JsonReader(input)
        var cacheKey: String? = null
        var audio = emptyList<ManagedDownloadStorage.StoredEntry>()
        var pendingAudio = emptyList<ManagedDownloadStorage.StoredEntry>()
        var metadataEntries = emptyList<ManagedDownloadStorage.StoredEntry>()
        var metadata = emptyMap<String, DownloadedAudioMetadata>()
        var pendingMetadata = emptyMap<String, DownloadedAudioMetadata>()
        var covers = emptyList<ManagedDownloadStorage.StoredEntry>()
        var lyrics = emptyList<ManagedDownloadStorage.StoredEntry>()
        var rootComplete = true
        var sidecarComplete = true
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "cacheKey" -> {
                    if (reader.peek() != JsonToken.STRING) return null
                    cacheKey = reader.nextString()
                    if (cacheKey.isNullOrBlank() || (expectedKey != null && expectedKey != cacheKey)) {
                        return null
                    }
                }
                "audioEntries" -> audio = reader.readEntries()
                "pendingAudioEntries" -> pendingAudio = reader.readEntries()
                "metadataEntries" -> metadataEntries = reader.readEntries()
                "metadataByAudioName" -> metadata = reader.readMetadata()
                "pendingMetadataByAudioName" -> pendingMetadata = reader.readMetadata()
                "coverEntries" -> covers = reader.readEntries()
                "lyricEntries" -> lyrics = reader.readEntries()
                "rootEntriesComplete" -> rootComplete = reader.readCompleteFlag()
                "sidecarEntriesComplete" -> sidecarComplete = reader.readCompleteFlag()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (cacheKey == null || reader.peek() != JsonToken.END_DOCUMENT) return null
        return cacheKey to ManagedDownloadSnapshotIndex.compose(
            audioEntries = audio,
            metadataEntries = metadataEntries,
            metadataByAudioName = metadata,
            coverEntries = covers,
            lyricEntries = lyrics,
            rootEntriesComplete = rootComplete,
            sidecarEntriesComplete = sidecarComplete,
            pendingAudioEntries = pendingAudio,
            pendingMetadataByAudioName = pendingMetadata
        )
    }

    private fun JsonWriter.writeEntries(entries: Collection<ManagedDownloadStorage.StoredEntry>) {
        beginArray()
        entries.forEach { entry -> writeJsonValue(ManagedDownloadStorageJsonCodec.storedEntryToJson(entry)) }
        endArray()
    }

    private fun JsonWriter.writeMetadata(entries: Map<String, DownloadedAudioMetadata>) {
        beginObject()
        entries.forEach { (name, metadata) ->
            name(name)
            writeJsonValue(ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(metadata))
        }
        endObject()
    }

    private fun JsonReader.readEntries(): List<ManagedDownloadStorage.StoredEntry> {
        if (peek() != JsonToken.BEGIN_ARRAY) {
            skipValue()
            return emptyList()
        }
        return buildList {
            beginArray()
            while (hasNext()) {
                if (peek() == JsonToken.BEGIN_OBJECT) {
                    ManagedDownloadStorageJsonCodec.storedEntryFromJson(readJsonObject())?.let(::add)
                } else {
                    skipValue()
                }
            }
            endArray()
        }
    }

    private fun JsonReader.readMetadata(): Map<String, DownloadedAudioMetadata> {
        if (peek() != JsonToken.BEGIN_OBJECT) {
            skipValue()
            return emptyMap()
        }
        return buildMap {
            beginObject()
            while (hasNext()) {
                val key = nextName()
                if (peek() == JsonToken.BEGIN_OBJECT) {
                    put(key, ManagedDownloadStorageJsonCodec.downloadedAudioMetadataFromJsonObject(readJsonObject()))
                } else {
                    skipValue()
                    remove(key)
                }
            }
            endObject()
        }
    }

    private fun JsonReader.readCompleteFlag(): Boolean {
        return when (peek()) {
            JsonToken.BOOLEAN -> nextBoolean()
            JsonToken.STRING -> !nextString().equals("false", ignoreCase = true)
            else -> {
                skipValue()
                true
            }
        }
    }
}
