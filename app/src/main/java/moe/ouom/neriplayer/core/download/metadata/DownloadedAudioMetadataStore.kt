package moe.ouom.neriplayer.core.download.metadata

import android.content.Context
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.naming.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject

internal data class RestorableMetadataClearPolicy(
    val title: Boolean = false,
    val artist: Boolean = false,
    val cover: Boolean = false,
    val lyrics: Boolean = false
)

internal class DownloadedAudioMetadataStore(
    private val maxWriteAttempts: Int,
    private val writeRetryDelayMs: Long,
    private val loggerTag: String
) {
    suspend fun persist(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null,
        downloadFinalized: Boolean = true,
        resolveExistingSidecars: Boolean = true,
        artifactStateOverride: String? = null,
        operationId: String? = null,
        clearRestorableOverrides: RestorableMetadataClearPolicy =
            RestorableMetadataClearPolicy()
    ): Boolean {
        val identity = song.identity()
        val existingMetadata = read(context, audio)
        val sidecars = resolveSidecarReferences(
            context = context,
            audio = audio,
            song = song,
            sidecarReferences = sidecarReferences,
            existingMetadata = existingMetadata,
            resolveExistingSidecars = resolveExistingSidecars
        )
        val materializedCover = ManagedDownloadCoverAssetStore.materialize(
            context = context,
            reference = sidecars.coverReference
        )
        val persistedSidecars = sidecars.copy(
            coverReference = materializedCover?.reference ?: sidecars.coverReference
        )
        val metadataSong = preserveMissingDownloadedMetadataLyrics(song, existingMetadata)
        val createdAtMs = existingMetadata?.createdAtMs
            ?: existingMetadata?.downloadTimeMs
            ?: audio.lastModifiedMs.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val createdAtSource = existingMetadata?.createdAtSource
            ?: "MANAGED_COMMIT"
        val restorableMetadata = resolveRestorableMetadata(
            song = metadataSong,
            existing = existingMetadata?.restorableMetadata,
            coverReference = persistedSidecars.coverReference,
            coverAssetHash = materializedCover?.assetHash,
            createdAtMs = createdAtMs,
            clearRestorableOverrides = clearRestorableOverrides
        )
        val payload = buildMetadataPayload(
            song = metadataSong,
            coverReference = persistedSidecars.coverReference,
            lyricReference = persistedSidecars.lyricReference,
            translatedLyricReference = persistedSidecars.translatedLyricReference,
            romanizedLyricReference = persistedSidecars.romanizedLyricReference,
            // 元信息、歌词和封面写回不应把歌曲重新标记为最新下载
            downloadTimeMs = resolveDownloadedAudioTime(
                existingTimeMs = existingMetadata?.downloadTimeMs,
                fallbackTimeMs = createdAtMs
            ),
            downloadFinalized = downloadFinalized,
            createdAtMs = createdAtMs,
            createdAtSource = createdAtSource,
            artifactId = existingMetadata?.artifactId,
            operationId = operationId ?: existingMetadata?.operationId,
            artifactState = artifactStateOverride
                ?: if (downloadFinalized) {
                    "COMPLETE"
                } else {
                    existingMetadata?.artifactState?.takeUnless {
                        it == "COMMITTING"
                    } ?: "CORE_COMMITTED"
                },
            audioFileName = audio.logicalName,
            libraryId = existingMetadata?.libraryId,
            libraryAddedAtMs = existingMetadata?.libraryAddedAtMs
                ?: createdAtMs.takeIf { it > 0L },
            sourceCreatedAtMs = existingMetadata?.sourceCreatedAtMs,
            sourceModifiedAtMs = existingMetadata?.sourceModifiedAtMs,
            restorableMetadata = restorableMetadata
        )

        var lastError: Throwable? = null
        repeat(maxWriteAttempts) { attempt ->
            val result = runCatching {
                ManagedDownloadStorage.saveMetadata(context, audio, payload.toString())
            }
            if (result.getOrDefault(false)) {
                NPLogger.d(
                    loggerTag,
                    "保存下载 metadata: file=${audio.name}, stableKey=${identity.stableKey()}, finalized=$downloadFinalized, lyricPath=${persistedSidecars.lyricReference}, translatedLyricPath=${persistedSidecars.translatedLyricReference}, romanizedLyricPath=${persistedSidecars.romanizedLyricReference}, coverPath=${persistedSidecars.coverReference}"
                )
                return true
            }
            val error = result.exceptionOrNull()
                ?: IllegalStateException("下载元数据写入读回校验失败")
            lastError = error
            if (attempt < maxWriteAttempts - 1) {
                NPLogger.w(
                    loggerTag,
                    "写入下载元数据失败(第${attempt + 1}次): ${audio.name} - ${error.message}"
                )
                delay(writeRetryDelayMs)
            }
        }
        NPLogger.e(loggerTag, "写入下载元数据最终失败: ${audio.name} - ${lastError?.message}", lastError)
        return false
    }

    suspend fun read(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        metadataEntry: ManagedDownloadStorage.StoredEntry? = null
    ): ManagedDownloadStorage.DownloadedAudioMetadata? {
        val resolvedMetadataEntry = metadataEntry
            ?: ManagedDownloadStorage.findMetadataForAudio(context, audio)
            ?: return null
        val raw = ManagedDownloadStorage.readText(context, resolvedMetadataEntry.reference) ?: return null
        return ManagedDownloadStorage.parseDownloadedAudioMetadataJson(raw)
    }

    suspend fun persistCoverReference(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        coverReference: String
    ): Boolean {
        val metadataEntry = ManagedDownloadStorage.findMetadataForAudio(context, audio)
            ?: return false
        val raw = ManagedDownloadStorage.readText(context, metadataEntry.reference)
            ?: return false
        val materialized = ManagedDownloadCoverAssetStore.materialize(
            context = context,
            reference = coverReference
        )
        val effectiveReference = materialized?.reference ?: coverReference
        val patchedPayload = patchDownloadedMetadataCoverReference(
            rawMetadata = raw,
            coverReference = effectiveReference,
            coverAssetHash = materialized?.assetHash
        )
            ?: return false
        var lastError: Throwable? = null
        repeat(maxWriteAttempts) { attempt ->
            val result = runCatching {
                ManagedDownloadStorage.saveMetadata(context, audio, patchedPayload)
            }
            if (result.getOrDefault(false)) {
                NPLogger.d(loggerTag, "补写下载封面侧载引用: file=${audio.name}, coverPath=$effectiveReference")
                return true
            }
            lastError = result.exceptionOrNull()
            if (attempt < maxWriteAttempts - 1) {
                delay(writeRetryDelayMs)
            }
        }
        NPLogger.e(loggerTag, "补写下载封面侧载引用失败: ${audio.name}", lastError)
        return false
    }

    private suspend fun resolveSidecarReferences(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        existingMetadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        resolveExistingSidecars: Boolean
    ): DownloadedMetadataSidecarReferences {
        if (!resolveExistingSidecars) {
            return DownloadedMetadataSidecarReferences(
                coverReference = sidecarReferences?.coverReference,
                lyricReference = sidecarReferences?.lyricReference,
                translatedLyricReference = sidecarReferences?.translatedLyricReference,
                romanizedLyricReference = sidecarReferences?.romanizedLyricReference
            )
        }

        val candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension)
        val discoveredCoverReference = ManagedDownloadStorage.findCoverReference(context, audio)
            ?: existingMetadata?.coverPath
        return DownloadedMetadataSidecarReferences(
            coverReference = sidecarReferences?.coverReference
                ?: resolveDownloadedMetadataCoverReference(
                    existingCoverReference = discoveredCoverReference,
                    song = song,
                    previousCustomCoverReference = existingMetadata?.customCoverUrl
                ),
            lyricReference = sidecarReferences?.lyricReference
                ?: ManagedDownloadStorage.findLyricLocation(
                    context = context,
                    songId = song.id,
                    candidateBaseNames = candidateBaseNames,
                    translated = false
                )
                ?: existingMetadata?.lyricPath,
            translatedLyricReference = sidecarReferences?.translatedLyricReference
                ?: ManagedDownloadStorage.findLyricLocation(
                    context = context,
                    songId = song.id,
                    candidateBaseNames = candidateBaseNames,
                    translated = true
                )
                ?: existingMetadata?.translatedLyricPath,
            romanizedLyricReference = sidecarReferences?.romanizedLyricReference
                ?: ManagedDownloadStorage.findRomanizedLyricLocation(
                    context = context,
                    songId = song.id,
                    candidateBaseNames = candidateBaseNames
                )
                ?: existingMetadata?.romanizedLyricPath
        )
    }

    private fun buildMetadataPayload(
        song: SongItem,
        coverReference: String?,
        lyricReference: String?,
        translatedLyricReference: String?,
        romanizedLyricReference: String?,
        downloadTimeMs: Long?,
        downloadFinalized: Boolean,
        createdAtMs: Long,
        createdAtSource: String,
        artifactId: String?,
        operationId: String?,
        artifactState: String?,
        audioFileName: String?,
        libraryId: String?,
        libraryAddedAtMs: Long?,
        sourceCreatedAtMs: Long?,
        sourceModifiedAtMs: Long?,
        restorableMetadata: ManagedDownloadRestorableMetadata
    ): JSONObject {
        val identity = song.identity()
        return JSONObject().apply {
            put("schemaVersion", 5)
            put("stableKey", identity.stableKey())
            put("songId", song.id)
            put("identityAlbum", identity.album)
            put("album", song.album)
            put("name", song.name)
            put("artist", song.artist)
            put("coverUrl", song.coverUrl)
            put("matchedLyric", song.matchedLyric)
            put("matchedTranslatedLyric", song.matchedTranslatedLyric)
            put("matchedRomanizedLyric", song.matchedRomanizedLyric)
            put("matchedLyricSource", song.matchedLyricSource?.name)
            put("matchedSongId", song.matchedSongId)
            put("userLyricOffsetMs", song.userLyricOffsetMs)
            put("customCoverUrl", song.customCoverUrl)
            put("customName", song.customName)
            put("customArtist", song.customArtist)
            put("originalName", song.originalName)
            put("originalArtist", song.originalArtist)
            put("originalCoverUrl", song.originalCoverUrl)
            put("originalLyric", song.originalLyric)
            put("originalTranslatedLyric", song.originalTranslatedLyric)
            put("originalRomanizedLyric", song.originalRomanizedLyric)
            put("mediaUri", identity.mediaUri ?: song.mediaUri)
            put("channelId", song.channelId)
            put("audioId", song.audioId)
            put("subAudioId", song.subAudioId)
            put("playlistContextId", song.playlistContextId)
            put("coverPath", coverReference)
            put("lyricPath", lyricReference)
            put("translatedLyricPath", translatedLyricReference)
            put("romanizedLyricPath", romanizedLyricReference)
            put("durationMs", song.durationMs)
            put("downloadTimeMs", downloadTimeMs)
            put("downloadFinalized", downloadFinalized)
            put("createdAtMs", createdAtMs)
            put("createdAtSource", createdAtSource)
            put("artifactId", artifactId)
            put("operationId", operationId)
            put("artifactState", artifactState)
            put("audioFileName", audioFileName)
            put("libraryId", libraryId)
            put("libraryAddedAtMs", libraryAddedAtMs)
            put("sourceCreatedAtMs", sourceCreatedAtMs)
            put("sourceModifiedAtMs", sourceModifiedAtMs)
            put("restorableMetadata", restorableMetadata.toJson())
        }
    }

    private fun resolveRestorableMetadata(
        song: SongItem,
        existing: ManagedDownloadRestorableMetadata?,
        coverReference: String?,
        coverAssetHash: String?,
        createdAtMs: Long,
        clearRestorableOverrides: RestorableMetadataClearPolicy
    ): ManagedDownloadRestorableMetadata {
        val identity = song.identity()
        val baseline = existing?.baseline ?: ManagedDownloadRestorableMetadata.Baseline(
            title = song.originalName ?: song.name,
            artist = song.originalArtist ?: song.artist,
            album = song.album,
            // the first write must capture the source cover, never the current override
            coverReference = song.originalCoverUrl ?: song.coverUrl,
            originalLyric = song.originalLyric ?: song.matchedLyric,
            translatedLyric = song.originalTranslatedLyric ?: song.matchedTranslatedLyric,
            romanizedLyric = song.originalRomanizedLyric ?: song.matchedRomanizedLyric
        )
        val previous = existing ?: ManagedDownloadRestorableMetadata(
            sourceStableKey = identity.stableKey(),
            baseline = baseline,
            overrides = ManagedDownloadRestorableMetadata.Overrides(),
            createdAtMs = createdAtMs
        )
        return previous.copy(
            sourceStableKey = previous.sourceStableKey ?: identity.stableKey(),
            baseline = baseline,
            overrides = mergeRestorableOverrides(
                previous = previous.overrides,
                song = song,
                coverReference = coverReference,
                clearRestorableOverrides = clearRestorableOverrides
            ),
            baselineCoverAssetHash = previous.baselineCoverAssetHash ?:
                coverAssetHash.takeIf { song.customCoverUrl.isNullOrBlank() },
            currentCoverAssetHash = when {
                clearRestorableOverrides.cover -> {
                    coverAssetHash ?: previous.baselineCoverAssetHash
                }
                else -> coverAssetHash ?: previous.currentCoverAssetHash
            },
            createdAtMs = previous.createdAtMs ?: createdAtMs,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private data class DownloadedMetadataSidecarReferences(
        val coverReference: String?,
        val lyricReference: String?,
        val translatedLyricReference: String?,
        val romanizedLyricReference: String?
    )
}

internal fun mergeRestorableOverrides(
    previous: ManagedDownloadRestorableMetadata.Overrides,
    song: SongItem,
    coverReference: String?,
    clearRestorableOverrides: RestorableMetadataClearPolicy =
        RestorableMetadataClearPolicy()
): ManagedDownloadRestorableMetadata.Overrides {
    val customCover = song.customCoverUrl?.trim()?.takeIf(String::isNotBlank)
    val effectiveCoverReference = if (customCover != null) {
        customCover
    } else {
        null
    }
    return previous.copy(
        // null means the user restored the baseline; do not resurrect the old override
        title = if (clearRestorableOverrides.title) null else {
            song.customName ?: previous.title
        },
        artist = if (clearRestorableOverrides.artist) null else {
            song.customArtist ?: previous.artist
        },
        coverReference = if (clearRestorableOverrides.cover) {
            null
        } else {
            effectiveCoverReference ?: previous.coverReference
        },
        userLyricOffsetMs = if (song.userLyricOffsetMs != 0L) {
            song.userLyricOffsetMs
        } else {
            previous.userLyricOffsetMs
        },
        originalLyric = if (clearRestorableOverrides.lyrics) {
            null
        } else {
            song.matchedLyric ?: previous.originalLyric
        },
        translatedLyric = if (clearRestorableOverrides.lyrics) {
            null
        } else {
            song.matchedTranslatedLyric ?: previous.translatedLyric
        },
        romanizedLyric = if (clearRestorableOverrides.lyrics) {
            null
        } else {
            song.matchedRomanizedLyric ?: previous.romanizedLyric
        }
    )
}

internal fun resolveDownloadedAudioTime(
    existingTimeMs: Long?,
    fallbackTimeMs: Long?
): Long? {
    return existingTimeMs?.takeIf { it > 0L }
        ?: fallbackTimeMs?.takeIf { it > 0L }
}

internal fun preserveMissingDownloadedMetadataLyrics(
    song: SongItem,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
): SongItem {
    if (metadata == null) return song
    return song.copy(
        matchedLyric = song.matchedLyric ?: metadata.matchedLyric,
        matchedTranslatedLyric = song.matchedTranslatedLyric
            ?: metadata.matchedTranslatedLyric,
        matchedRomanizedLyric = song.matchedRomanizedLyric
            ?: metadata.matchedRomanizedLyric,
        matchedLyricSource = song.matchedLyricSource
            ?: metadata.matchedLyricSource?.let { value ->
                runCatching { MusicPlatform.valueOf(value) }.getOrNull()
            },
        matchedSongId = song.matchedSongId ?: metadata.matchedSongId,
        userLyricOffsetMs = resolveDownloadedUserLyricOffset(
            existingOffsetMs = metadata.userLyricOffsetMs,
            incomingOffsetMs = song.userLyricOffsetMs
        ),
        originalLyric = song.originalLyric ?: metadata.originalLyric,
        originalTranslatedLyric = song.originalTranslatedLyric
            ?: metadata.originalTranslatedLyric,
        originalRomanizedLyric = song.originalRomanizedLyric
            ?: metadata.originalRomanizedLyric
    )
}

internal fun resolveDownloadedUserLyricOffset(
    existingOffsetMs: Long?,
    incomingOffsetMs: Long?
): Long {
    return incomingOffsetMs?.takeIf { it != 0L }
        ?: existingOffsetMs
        ?: 0L
}

internal fun patchDownloadedMetadataCoverReference(
    rawMetadata: String,
    coverReference: String,
    coverAssetHash: String? = null
): String? {
    return runCatching {
        val root = JSONObject(rawMetadata).put("coverPath", coverReference)
        val restorable = root.optJSONObject("restorableMetadata")
        if (restorable != null) {
            val overrides = restorable.optJSONObject("overrides") ?: JSONObject()
            overrides.put("coverReference", coverReference)
            restorable.put("overrides", overrides)
            coverAssetHash?.let { hash ->
                val assets = restorable.optJSONObject("assetRefs") ?: JSONObject()
                assets.put("currentCoverHash", hash)
                restorable.put("assetRefs", assets)
            }
            root.put("restorableMetadata", restorable)
        }
        root.toString()
    }.getOrNull()
}

internal fun resolveDownloadedMetadataCoverReference(
    existingCoverReference: String?,
    song: SongItem,
    previousCustomCoverReference: String? = null
): String? {
    val customCover = song.customCoverUrl.normalizedCoverReference()
    val baseCandidates = if (customCover == null) {
        listOf(song.coverUrl, song.originalCoverUrl)
    } else {
        listOf(song.originalCoverUrl, song.coverUrl)
    }
    val restoredLocalCover = baseCandidates
        .asSequence()
        .mapNotNull(String?::normalizedCoverReference)
        .firstOrNull { reference ->
            reference != customCover && reference.isLocalCoverReference()
        }
    if (restoredLocalCover != null) {
        return restoredLocalCover
    }
    if (customCover == null && previousCustomCoverReference.normalizedCoverReference() != null) {
        return null
    }
    return existingCoverReference
        .normalizedCoverReference()
        ?.takeUnless { reference ->
            customCover == null &&
                reference == previousCustomCoverReference.normalizedCoverReference()
        }
}

private fun String?.normalizedCoverReference(): String? {
    return this?.trim()?.takeIf(String::isNotBlank)
}

private fun String.isLocalCoverReference(): Boolean {
    return startsWith("/") ||
        startsWith("file://", ignoreCase = true) ||
        startsWith("content://", ignoreCase = true)
}
