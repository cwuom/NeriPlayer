package moe.ouom.neriplayer.core.download.storage

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
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

    fun workingResumeMetadataToJson(
        song: SongItem,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint? = null,
        operationId: String? = null
    ): JSONObject {
        return song.toWorkingResumeMetadataJson().apply {
            operationId?.takeIf(String::isNotBlank)?.let { put("operationId", it) }
            fingerprint?.toJson()?.let { fingerprintJson ->
                put("resumeFingerprint", fingerprintJson)
            }
        }
    }

    fun workingResumeMetadataSongFromJson(rawJson: String): SongItem? {
        return JSONObject(rawJson).toWorkingResumeMetadataSong()
    }

    fun workingResumeFingerprintFromJson(rawJson: String): ManagedDownloadStorage.WorkingResumeFingerprint? {
        return JSONObject(rawJson)
            .optJSONObject("resumeFingerprint")
            ?.toWorkingResumeFingerprint()
    }

    fun workingResumeOperationIdFromJson(rawJson: String): String? {
        return JSONObject(rawJson).optString("operationId")
            .takeIf(String::isNotBlank)
    }

    fun mergeWorkingResumeFingerprint(
        rawJson: String?,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): JSONObject {
        val root = rawJson
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        root.remove("resumeFingerprint")
        fingerprint?.toJson()?.let { fingerprintJson ->
            root.put("resumeFingerprint", fingerprintJson)
        }
        return root
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
            put("sizeKnown", sizeKnown)
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
            sizeKnown = optBoolean("sizeKnown", optLong("sizeBytes") > 0L),
            isDirectory = optBoolean("isDirectory")
        )
    }

    private fun ManagedDownloadStorage.DownloadedAudioMetadata.toJson(): JSONObject {
        val restorable = restorableMetadata ?: toLegacyRestorableMetadata()
        return JSONObject().apply {
            put("schemaVersion", 6)
            put("stableKey", stableKey)
            put("songId", songId)
            put("identityAlbum", identityAlbum)
            put("album", album)
            put("name", name)
            put("artist", artist)
            put("coverUrl", coverUrl)
            put("matchedLyric", matchedLyric)
            put("matchedTranslatedLyric", matchedTranslatedLyric)
            put("matchedRomanizedLyric", matchedRomanizedLyric)
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
            put("originalRomanizedLyric", originalRomanizedLyric)
            put("mediaUri", mediaUri)
            put("channelId", channelId)
            put("audioId", audioId)
            put("subAudioId", subAudioId)
            put("playlistContextId", playlistContextId)
            put("coverPath", coverPath)
            put("lyricPath", lyricPath)
            put("translatedLyricPath", translatedLyricPath)
            put("romanizedLyricPath", romanizedLyricPath)
            put("durationMs", durationMs)
            put("downloadTimeMs", downloadTimeMs)
            put("downloadFinalized", downloadFinalized)
            put("metadataEmbeddingState", metadataEmbeddingState?.name)
            put("createdAtMs", createdAtMs)
            put("createdAtSource", createdAtSource)
            put("createdAtConfidence", createdAtConfidence)
            put("artifactId", artifactId)
            put("operationId", operationId)
            put("terminalTemporaryWriteCleanupToken", terminalTemporaryWriteCleanupToken)
            put("artifactState", artifactState)
            put("audioFileName", audioFileName)
            put("libraryId", libraryId)
            put("libraryAddedAtMs", libraryAddedAtMs)
            put("sourceCreatedAtMs", sourceCreatedAtMs)
            put("sourceModifiedAtMs", sourceModifiedAtMs)
            put("restorableMetadata", restorable.toJson())
        }
    }

    private fun ManagedDownloadStorage.DownloadedAudioMetadata.toLegacyRestorableMetadata():
        ManagedDownloadRestorableMetadata {
        return ManagedDownloadRestorableMetadata(
            sourceStableKey = stableKey,
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                title = originalName ?: name,
                artist = originalArtist ?: artist,
                album = album,
                coverReference = originalCoverUrl ?: coverPath ?: coverUrl,
                originalLyric = originalLyric ?: matchedLyric,
                translatedLyric = originalTranslatedLyric ?: matchedTranslatedLyric,
                romanizedLyric = originalRomanizedLyric ?: matchedRomanizedLyric
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(
                title = customName,
                artist = customArtist,
                coverReference = coverPath ?: customCoverUrl,
                originalLyric = matchedLyric,
                translatedLyric = matchedTranslatedLyric,
                romanizedLyric = matchedRomanizedLyric
            ),
            createdAtMs = createdAtMs ?: downloadTimeMs,
            updatedAtMs = createdAtMs ?: downloadTimeMs
        )
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
            put("matchedRomanizedLyric", matchedRomanizedLyric)
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
            put("originalRomanizedLyric", originalRomanizedLyric)
            put("localFileName", localFileName)
            put("localFilePath", localFilePath)
            put("channelId", channelId)
            put("audioId", audioId)
            put("subAudioId", subAudioId)
            put("playlistContextId", playlistContextId)
            put("logicalCreatedAtMs", logicalCreatedAtMs)
            put("createdAtSource", createdAtSource)
            put("createdAtConfidence", createdAtConfidence)
            put("membershipAddedAtMs", membershipAddedAtMs)
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

    private fun ManagedDownloadStorage.WorkingResumeFingerprint.toJson(): JSONObject? {
        val normalizedExpectedLength = expectedContentLength?.takeIf { it > 0L }
        if (
            sourceUrl.isNullOrBlank() &&
            etag.isNullOrBlank() &&
            lastModified.isNullOrBlank() &&
            normalizedExpectedLength == null
        ) {
            return null
        }
        return JSONObject().apply {
            put("sourceUrl", sourceUrl)
            put("etag", etag)
            put("lastModified", lastModified)
            put("expectedContentLength", normalizedExpectedLength)
        }
    }

    private fun JSONObject.toWorkingResumeFingerprint(): ManagedDownloadStorage.WorkingResumeFingerprint? {
        val expectedLength = optLong("expectedContentLength")
            .takeIf { has("expectedContentLength") && it > 0L }
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = optString("sourceUrl").takeIf(String::isNotBlank),
            etag = optString("etag").takeIf(String::isNotBlank),
            lastModified = optString("lastModified").takeIf(String::isNotBlank),
            expectedContentLength = expectedLength
        )
        return fingerprint.takeIf {
            !it.sourceUrl.isNullOrBlank() ||
                !it.etag.isNullOrBlank() ||
                !it.lastModified.isNullOrBlank() ||
                it.expectedContentLength != null
        }
    }

    private fun JSONObject.toWorkingResumeMetadataSong(): SongItem? {
        val id = optLong("id").takeIf { has("id") } ?: return null
        val name = optString("name").takeIf(String::isNotBlank) ?: return null
        // 旧 operation 载荷可能还没有远程歌手或专辑信息
        // 只要保留按频道生成的稳定键，就仍然可以恢复传输
        val artist = optString("artist")
        val album = optString("album")
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = optLong("albumId"),
            durationMs = optLong("durationMs"),
            coverUrl = optString("coverUrl").takeIf { has("coverUrl") && !isNull("coverUrl") },
            mediaUri = optPresentString("mediaUri"),
            matchedLyric = optPresentString("matchedLyric"),
            matchedTranslatedLyric = optPresentString("matchedTranslatedLyric"),
            matchedRomanizedLyric = optPresentString("matchedRomanizedLyric"),
            matchedLyricSource = optPresentString("matchedLyricSource")
                ?.let { value -> runCatching { MusicPlatform.valueOf(value) }.getOrNull() },
            matchedSongId = optPresentString("matchedSongId"),
            userLyricOffsetMs = optLong("userLyricOffsetMs"),
            customCoverUrl = optPresentString("customCoverUrl"),
            customName = optPresentString("customName"),
            customArtist = optPresentString("customArtist"),
            originalName = optPresentString("originalName"),
            originalArtist = optPresentString("originalArtist"),
            originalCoverUrl = optPresentString("originalCoverUrl"),
            originalLyric = optPresentString("originalLyric"),
            originalTranslatedLyric = optPresentString("originalTranslatedLyric"),
            originalRomanizedLyric = optPresentString("originalRomanizedLyric"),
            localFileName = optPresentString("localFileName"),
            localFilePath = optPresentString("localFilePath"),
            channelId = optPresentString("channelId"),
            audioId = optPresentString("audioId"),
            subAudioId = optPresentString("subAudioId"),
            playlistContextId = optPresentString("playlistContextId"),
            logicalCreatedAtMs = optLong("logicalCreatedAtMs")
                .takeIf { has("logicalCreatedAtMs") && it > 0L },
            createdAtSource = optPresentString("createdAtSource"),
            createdAtConfidence = optPresentString("createdAtConfidence"),
            membershipAddedAtMs = optLong("membershipAddedAtMs")
                .takeIf { has("membershipAddedAtMs") && it > 0L },
            streamUrl = optPresentString("streamUrl"),
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
            queuedAtMs = optLong("queuedAtMs").coerceAtLeast(0L),
            operationId = optString("operationId").takeIf(String::isNotBlank),
            requiresWifiNetwork = if (has("requiresWifiNetwork")) {
                optBoolean("requiresWifiNetwork", true)
            } else {
                true
            }
        )
    }

    private fun ManagedDownloadStorage.PendingDownloadQueueEntry.toPendingDownloadQueueJson(): JSONObject {
        return JSONObject().apply {
            put("stableKey", stableKey)
            put("order", order)
            put("queuedAtMs", queuedAtMs)
            operationId?.takeIf(String::isNotBlank)?.let { put("operationId", it) }
            put("requiresWifiNetwork", requiresWifiNetwork)
            put("song", song.toWorkingResumeMetadataJson())
        }
    }

    private fun JSONObject.toDownloadedAudioMetadata(): ManagedDownloadStorage.DownloadedAudioMetadata {
        val restorable = ManagedDownloadRestorableMetadata.fromJson(
            optJSONObject("restorableMetadata")
        )
        val baseline = restorable?.baseline
        val overrides = restorable?.overrides
        val declaredDownloadFinalized = optOptionalBoolean("downloadFinalized")
        val declaredEmbeddingState = DownloadedAudioEmbeddingState.fromPersisted(
            optString("metadataEmbeddingState").takeIf {
                has("metadataEmbeddingState") && !isNull("metadataEmbeddingState")
            }
        )
        val isShippedV15Completion = declaredDownloadFinalized == true &&
            declaredEmbeddingState == null
        val isPreviouslyDowngradedV15Completion =
            declaredDownloadFinalized == false &&
                declaredEmbeddingState == DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED &&
                optString("createdAtSource").equals("LEGACY_V15", ignoreCase = true) &&
                optString("stableKey").isNotBlank() &&
                optString("audioFileName").isNotBlank() &&
                optLong("downloadTimeMs") > 0L &&
                optString("operationId").isBlank() &&
                optString("artifactState").let { state ->
                    state.isBlank() || state.equals("FINALIZED", ignoreCase = true) ||
                        state.equals("COMPLETE", ignoreCase = true)
                }
        val acceptsLegacyV15Completion =
            isShippedV15Completion || isPreviouslyDowngradedV15Completion
        return ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = optString("stableKey").takeIf(String::isNotBlank)
                ?: restorable?.sourceStableKey,
            songId = optLong("songId").takeIf { it > 0L },
            identityAlbum = optString("identityAlbum").takeIf(String::isNotBlank),
            album = optString("album").takeIf(String::isNotBlank),
            name = optString("name").takeIf(String::isNotBlank) ?: baseline?.title,
            artist = optString("artist").takeIf(String::isNotBlank) ?: baseline?.artist,
            coverUrl = optString("coverUrl").takeIf(String::isNotBlank),
            matchedLyric = overrides?.originalLyric ?: optPresentString("matchedLyric"),
            matchedTranslatedLyric = overrides?.translatedLyric
                ?: optPresentString("matchedTranslatedLyric"),
            matchedRomanizedLyric = overrides?.romanizedLyric
                ?: optPresentString("matchedRomanizedLyric"),
            matchedLyricSource = optString("matchedLyricSource").takeIf(String::isNotBlank),
            matchedSongId = optString("matchedSongId").takeIf(String::isNotBlank),
            userLyricOffsetMs = optLong("userLyricOffsetMs")
                .takeIf { has("userLyricOffsetMs") && !isNull("userLyricOffsetMs") }
                ?.takeUnless { it == 0L }
                ?: overrides?.userLyricOffsetMs
                ?: 0L,
            customCoverUrl = optString("customCoverUrl").takeIf(String::isNotBlank),
            customName = optString("customName").takeIf(String::isNotBlank)
                ?: overrides?.title,
            customArtist = optString("customArtist").takeIf(String::isNotBlank)
                ?: overrides?.artist,
            originalName = optString("originalName").takeIf(String::isNotBlank)
                ?: baseline?.title,
            originalArtist = optString("originalArtist").takeIf(String::isNotBlank)
                ?: baseline?.artist,
            originalCoverUrl = optString("originalCoverUrl").takeIf(String::isNotBlank)
                ?: baseline?.coverReference,
            originalLyric = optPresentString("originalLyric") ?: baseline?.originalLyric,
            originalTranslatedLyric = optPresentString("originalTranslatedLyric")
                ?: baseline?.translatedLyric,
            originalRomanizedLyric = optPresentString("originalRomanizedLyric")
                ?: baseline?.romanizedLyric,
            mediaUri = optString("mediaUri").takeIf(String::isNotBlank),
            channelId = optString("channelId").takeIf(String::isNotBlank),
            audioId = optString("audioId").takeIf(String::isNotBlank),
            subAudioId = optString("subAudioId").takeIf(String::isNotBlank),
            playlistContextId = optString("playlistContextId").takeIf(String::isNotBlank),
            coverPath = optString("coverPath").takeIf(String::isNotBlank)
                ?: overrides?.coverReference ?: baseline?.coverReference,
            lyricPath = optString("lyricPath").takeIf(String::isNotBlank),
            translatedLyricPath = optString("translatedLyricPath").takeIf(String::isNotBlank),
            romanizedLyricPath = optString("romanizedLyricPath").takeIf(String::isNotBlank),
            durationMs = optLong("durationMs"),
            downloadTimeMs = optLong("downloadTimeMs")
                .takeIf { has("downloadTimeMs") && it > 0L },
            downloadFinalized = if (acceptsLegacyV15Completion) {
                true
            } else {
                declaredDownloadFinalized
            },
            metadataEmbeddingState = if (acceptsLegacyV15Completion) {
                DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED
            } else {
                declaredEmbeddingState
            },
            createdAtMs = optLong("createdAtMs")
                .takeIf { has("createdAtMs") && it > 0L }
                ?: restorable?.createdAtMs,
            createdAtSource = optString("createdAtSource")
                .takeIf(String::isNotBlank),
            createdAtConfidence = optString("createdAtConfidence")
                .takeIf(String::isNotBlank),
            artifactId = optString("artifactId").takeIf(String::isNotBlank),
            operationId = optString("operationId").takeIf(String::isNotBlank),
            terminalTemporaryWriteCleanupToken = optString(
                "terminalTemporaryWriteCleanupToken"
            ).takeIf(String::isNotBlank),
            artifactState = optString("artifactState").takeIf(String::isNotBlank),
            audioFileName = optString("audioFileName").takeIf(String::isNotBlank),
            libraryId = optString("libraryId").takeIf(String::isNotBlank),
            libraryAddedAtMs = optLong("libraryAddedAtMs")
                .takeIf { has("libraryAddedAtMs") && it > 0L },
            sourceCreatedAtMs = optLong("sourceCreatedAtMs")
                .takeIf { has("sourceCreatedAtMs") && it > 0L },
            sourceModifiedAtMs = optLong("sourceModifiedAtMs")
                .takeIf { has("sourceModifiedAtMs") && it > 0L },
            restorableMetadata = restorable
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
