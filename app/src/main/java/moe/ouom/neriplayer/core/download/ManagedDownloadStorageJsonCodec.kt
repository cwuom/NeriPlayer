package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.json.JSONArray
import org.json.JSONObject

internal object ManagedDownloadStorageJsonCodec {
    fun storedEntriesToJsonArray(entries: List<ManagedDownloadStorage.StoredEntry>): JSONArray {
        return JSONArray().also { jsonArray ->
            entries.forEach { entry -> jsonArray.put(entry.toJson()) }
        }
    }

    fun storedEntriesFromJsonArray(jsonArray: JSONArray?): List<ManagedDownloadStorage.StoredEntry> {
        if (jsonArray == null) return emptyList()
        return buildList(jsonArray.length()) {
            for (index in 0 until jsonArray.length()) {
                jsonArray.optJSONObject(index)?.toStoredEntry()?.let(::add)
            }
        }
    }

    fun downloadedAudioMetadataToJson(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): JSONObject {
        return metadata.toJson()
    }

    fun downloadedAudioMetadataFromJsonObject(
        root: JSONObject
    ): ManagedDownloadStorage.DownloadedAudioMetadata {
        return root.toDownloadedAudioMetadata()
    }

    fun workingResumeMetadataToJson(song: SongItem): JSONObject {
        return song.toWorkingResumeMetadataJson()
    }

    fun workingResumeMetadataSongFromJson(rawJson: String): SongItem? {
        return JSONObject(rawJson).toWorkingResumeMetadataSong()
    }

    fun serializePendingDownloadQueuePayload(
        entries: List<ManagedDownloadStorage.PendingDownloadQueueEntry>,
        updatedAtMs: Long
    ): String {
        return JSONObject().apply {
            put("version", PENDING_DOWNLOAD_QUEUE_VERSION)
            put("updatedAtMs", updatedAtMs)
            put(
                "entries",
                JSONArray().also { entriesArray ->
                    entries
                        .sortedBy(ManagedDownloadStorage.PendingDownloadQueueEntry::order)
                        .forEach { entry ->
                            entriesArray.put(entry.toPendingDownloadQueueJson())
                        }
                }
            )
        }.toString()
    }

    fun parsePendingDownloadQueuePayload(
        rawJson: String
    ): List<ManagedDownloadStorage.PendingDownloadQueueEntry> {
        val root = JSONObject(rawJson)
        val entries = root.optJSONArray("entries") ?: return emptyList()
        val restoredEntries = mutableListOf<ManagedDownloadStorage.PendingDownloadQueueEntry>()
        for (index in 0 until entries.length()) {
            entries.optJSONObject(index)
                ?.toPendingDownloadQueueEntry()
                ?.let(restoredEntries::add)
        }
        return restoredEntries
            .sortedBy(ManagedDownloadStorage.PendingDownloadQueueEntry::order)
            .distinctBy(ManagedDownloadStorage.PendingDownloadQueueEntry::stableKey)
            .mapIndexed { index, entry -> entry.copy(order = index) }
    }

    fun serializeCancelledDownloadKeysPayload(
        songKeys: Set<String>,
        updatedAtMs: Long
    ): String {
        return JSONObject().apply {
            put("version", CANCELLED_DOWNLOAD_KEYS_VERSION)
            put("updatedAtMs", updatedAtMs)
            put(
                "keys",
                JSONArray().also { keysArray ->
                    songKeys
                        .filter(String::isNotBlank)
                        .sorted()
                        .forEach(keysArray::put)
                }
            )
        }.toString()
    }

    fun parseCancelledDownloadKeysPayload(rawJson: String): Set<String> {
        val root = JSONObject(rawJson)
        val keys = root.optJSONArray("keys") ?: return emptySet()
        return buildSet {
            for (index in 0 until keys.length()) {
                keys.optString(index)
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }
    }

    private fun ManagedDownloadStorage.StoredEntry.toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("reference", reference)
            put("mediaUri", mediaUri)
            put("localFilePath", localFilePath)
            put("sizeBytes", sizeBytes)
            put("lastModifiedMs", lastModifiedMs)
            put("isDirectory", isDirectory)
        }
    }

    private fun JSONObject.toStoredEntry(): ManagedDownloadStorage.StoredEntry? {
        val name = optString("name").takeIf(String::isNotBlank) ?: return null
        val reference = optString("reference").takeIf(String::isNotBlank) ?: return null
        val mediaUri = optString("mediaUri").takeIf(String::isNotBlank) ?: reference
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = mediaUri,
            localFilePath = optString("localFilePath").takeIf(String::isNotBlank),
            sizeBytes = optLong("sizeBytes"),
            lastModifiedMs = optLong("lastModifiedMs"),
            isDirectory = optBoolean("isDirectory")
        )
    }

    private fun ManagedDownloadStorage.DownloadedAudioMetadata.toJson(): JSONObject {
        return JSONObject().apply {
            put("stableKey", stableKey)
            put("songId", songId)
            put("identityAlbum", identityAlbum)
            put("name", name)
            put("artist", artist)
            put("coverUrl", coverUrl)
            put("matchedLyric", matchedLyric)
            put("matchedTranslatedLyric", matchedTranslatedLyric)
            put("matchedLyricSource", matchedLyricSource)
            put("matchedSongId", matchedSongId)
            put("userLyricOffsetMs", userLyricOffsetMs)
            put("customCoverUrl", customCoverUrl)
            put("customName", customName)
            put("customArtist", customArtist)
            put("originalName", originalName)
            put("originalArtist", originalArtist)
            put("originalCoverUrl", originalCoverUrl)
            put("originalLyric", originalLyric)
            put("originalTranslatedLyric", originalTranslatedLyric)
            put("mediaUri", mediaUri)
            put("channelId", channelId)
            put("audioId", audioId)
            put("subAudioId", subAudioId)
            put("coverPath", coverPath)
            put("lyricPath", lyricPath)
            put("translatedLyricPath", translatedLyricPath)
            put("durationMs", durationMs)
            put("downloadFinalized", downloadFinalized)
        }
    }

    private fun SongItem.toWorkingResumeMetadataJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("artist", artist)
            put("album", album)
            put("albumId", albumId)
            put("durationMs", durationMs)
            put("coverUrl", coverUrl)
            put("mediaUri", mediaUri)
            put("matchedLyric", matchedLyric)
            put("matchedTranslatedLyric", matchedTranslatedLyric)
            put("matchedLyricSource", matchedLyricSource?.name)
            put("matchedSongId", matchedSongId)
            put("userLyricOffsetMs", userLyricOffsetMs)
            put("customCoverUrl", customCoverUrl)
            put("customName", customName)
            put("customArtist", customArtist)
            put("originalName", originalName)
            put("originalArtist", originalArtist)
            put("originalCoverUrl", originalCoverUrl)
            put("originalLyric", originalLyric)
            put("originalTranslatedLyric", originalTranslatedLyric)
            put("localFileName", localFileName)
            put("localFilePath", localFilePath)
            put("channelId", channelId)
            put("audioId", audioId)
            put("subAudioId", subAudioId)
            put("playlistContextId", playlistContextId)
            put("streamUrl", streamUrl)
            put(
                "neteaseArtists",
                JSONArray().also { artistsArray ->
                    neteaseArtists.orEmpty().forEach { artistSummary ->
                        artistsArray.put(
                            JSONObject().apply {
                                put("id", artistSummary.id)
                                put("name", artistSummary.name)
                            }
                        )
                    }
                }
            )
        }
    }

    private fun JSONObject.toWorkingResumeMetadataSong(): SongItem? {
        val id = optLong("id").takeIf { has("id") } ?: return null
        val name = optString("name").takeIf(String::isNotBlank) ?: return null
        val artist = optString("artist").takeIf(String::isNotBlank) ?: return null
        val album = optString("album").takeIf(String::isNotBlank) ?: return null
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = optLong("albumId"),
            durationMs = optLong("durationMs"),
            coverUrl = optString("coverUrl").takeIf { has("coverUrl") && !isNull("coverUrl") },
            mediaUri = optString("mediaUri").takeIf(String::isNotBlank),
            matchedLyric = optPresentString("matchedLyric"),
            matchedTranslatedLyric = optPresentString("matchedTranslatedLyric"),
            matchedLyricSource = optString("matchedLyricSource")
                .takeIf(String::isNotBlank)
                ?.let { value -> runCatching { MusicPlatform.valueOf(value) }.getOrNull() },
            matchedSongId = optString("matchedSongId").takeIf(String::isNotBlank),
            userLyricOffsetMs = optLong("userLyricOffsetMs"),
            customCoverUrl = optString("customCoverUrl").takeIf(String::isNotBlank),
            customName = optString("customName").takeIf(String::isNotBlank),
            customArtist = optString("customArtist").takeIf(String::isNotBlank),
            originalName = optString("originalName").takeIf(String::isNotBlank),
            originalArtist = optString("originalArtist").takeIf(String::isNotBlank),
            originalCoverUrl = optString("originalCoverUrl").takeIf(String::isNotBlank),
            originalLyric = optPresentString("originalLyric"),
            originalTranslatedLyric = optPresentString("originalTranslatedLyric"),
            localFileName = optString("localFileName").takeIf(String::isNotBlank),
            localFilePath = optString("localFilePath").takeIf(String::isNotBlank),
            channelId = optString("channelId").takeIf(String::isNotBlank),
            audioId = optString("audioId").takeIf(String::isNotBlank),
            subAudioId = optString("subAudioId").takeIf(String::isNotBlank),
            playlistContextId = optString("playlistContextId").takeIf(String::isNotBlank),
            streamUrl = optString("streamUrl").takeIf(String::isNotBlank),
            neteaseArtists = optJSONArray("neteaseArtists").toNeteaseArtistSummaries()
        )
    }

    private fun JSONObject.toPendingDownloadQueueEntry(): ManagedDownloadStorage.PendingDownloadQueueEntry? {
        val song = optJSONObject("song")?.toWorkingResumeMetadataSong() ?: return null
        val stableKey = song.stableKey()
        return ManagedDownloadStorage.PendingDownloadQueueEntry(
            stableKey = stableKey,
            song = song,
            order = optInt("order", Int.MAX_VALUE),
            queuedAtMs = optLong("queuedAtMs").coerceAtLeast(0L)
        )
    }

    private fun ManagedDownloadStorage.PendingDownloadQueueEntry.toPendingDownloadQueueJson(): JSONObject {
        return JSONObject().apply {
            put("stableKey", stableKey)
            put("order", order)
            put("queuedAtMs", queuedAtMs)
            put("song", song.toWorkingResumeMetadataJson())
        }
    }

    private fun JSONObject.toDownloadedAudioMetadata(): ManagedDownloadStorage.DownloadedAudioMetadata {
        return ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = optString("stableKey").takeIf(String::isNotBlank),
            songId = optLong("songId").takeIf { it > 0L },
            identityAlbum = optString("identityAlbum").takeIf(String::isNotBlank),
            name = optString("name").takeIf(String::isNotBlank),
            artist = optString("artist").takeIf(String::isNotBlank),
            coverUrl = optString("coverUrl").takeIf(String::isNotBlank),
            matchedLyric = optPresentString("matchedLyric"),
            matchedTranslatedLyric = optPresentString("matchedTranslatedLyric"),
            matchedLyricSource = optString("matchedLyricSource").takeIf(String::isNotBlank),
            matchedSongId = optString("matchedSongId").takeIf(String::isNotBlank),
            userLyricOffsetMs = optLong("userLyricOffsetMs"),
            customCoverUrl = optString("customCoverUrl").takeIf(String::isNotBlank),
            customName = optString("customName").takeIf(String::isNotBlank),
            customArtist = optString("customArtist").takeIf(String::isNotBlank),
            originalName = optString("originalName").takeIf(String::isNotBlank),
            originalArtist = optString("originalArtist").takeIf(String::isNotBlank),
            originalCoverUrl = optString("originalCoverUrl").takeIf(String::isNotBlank),
            originalLyric = optPresentString("originalLyric"),
            originalTranslatedLyric = optPresentString("originalTranslatedLyric"),
            mediaUri = optString("mediaUri").takeIf(String::isNotBlank),
            channelId = optString("channelId").takeIf(String::isNotBlank),
            audioId = optString("audioId").takeIf(String::isNotBlank),
            subAudioId = optString("subAudioId").takeIf(String::isNotBlank),
            coverPath = optString("coverPath").takeIf(String::isNotBlank),
            lyricPath = optString("lyricPath").takeIf(String::isNotBlank),
            translatedLyricPath = optString("translatedLyricPath").takeIf(String::isNotBlank),
            durationMs = optLong("durationMs"),
            downloadFinalized = optOptionalBoolean("downloadFinalized")
        )
    }

    private fun JSONObject.optPresentString(fieldName: String): String? {
        if (!has(fieldName) || isNull(fieldName)) {
            return null
        }
        return optString(fieldName)
    }

    private fun JSONObject.optOptionalBoolean(fieldName: String): Boolean? {
        if (!has(fieldName) || isNull(fieldName)) {
            return null
        }
        return optBoolean(fieldName)
    }

    private fun JSONArray?.toNeteaseArtistSummaries(): List<NeteaseArtistSummary> {
        if (this == null) {
            return emptyList()
        }
        return buildList(length()) {
            for (index in 0 until length()) {
                val root = optJSONObject(index) ?: continue
                val artistId = root.optLong("id").takeIf { root.has("id") } ?: continue
                val artistName = root.optString("name").takeIf(String::isNotBlank) ?: continue
                add(
                    NeteaseArtistSummary(
                        id = artistId,
                        name = artistName
                    )
                )
            }
        }
    }
}
