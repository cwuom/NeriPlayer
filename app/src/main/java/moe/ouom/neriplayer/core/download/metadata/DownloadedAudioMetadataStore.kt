package moe.ouom.neriplayer.core.download.metadata

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import moe.ouom.neriplayer.core.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.resolvePersistedDownloadedAudioEmbeddingState
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
import java.util.Locale

/**
 * 以固定并发度读取独立侧载, 保持返回顺序并避免阻塞下一首歌曲
 */
internal suspend fun readRestorableSidecarLyricsConcurrently(
    references: List<String?>,
    parallelism: Int = 2,
    read: suspend (String) -> String?
): List<String?> {
    require(parallelism > 0) { "parallelism must be positive" }
    if (references.isEmpty()) return emptyList()
    val limiter = Semaphore(parallelism)
    return coroutineScope {
        references.map { reference ->
            async(Dispatchers.IO) {
                val normalized = reference?.trim()?.takeIf(String::isNotBlank)
                    ?: return@async null
                limiter.withPermit {
                    read(normalized)?.takeIf(String::isNotBlank)
                }
            }
        }.awaitAll()
    }
}

internal fun shouldReadRestorableSidecarLyric(
    baselineValue: String?,
    songValue: String?
): Boolean {
    return baselineValue == null && songValue == null
}

internal fun resolveCreatedAtConfidence(source: String?): String {
    return when (source?.trim()?.uppercase(Locale.ROOT)) {
        "CORE_COMMIT", "MANAGED_COMMIT", "MIGRATION_LOGICAL", "FILESYSTEM_BIRTH" -> "EXACT"
        "MEDIASTORE_DATE_ADDED", "PROVIDER_CREATED_AT", "PROVIDER_NATIVE" ->
            "PROVIDER_REPORTED"
        "MTIME", "MTIME_FALLBACK", "SAF_LAST_MODIFIED", "MEDIASTORE_DATE_MODIFIED",
        "INDEX_PREVIEW", "LEGACY_V15", "DOWNLOAD_TIME", "IMPORT_TIME" -> "INFERRED"
        else -> "UNKNOWN"
    }
}

internal data class RestorableMetadataClearPolicy(
    val title: Boolean = false,
    val artist: Boolean = false,
    val cover: Boolean = false,
    val lyrics: Boolean = false
)

internal data class RestorableCoverAssetRefs(
    val baselineHash: String?,
    val currentHash: String?,
    val baselineFileName: String?,
    val currentFileName: String?
)

internal fun mergeRestorableCoverAssetRefs(
    existing: ManagedDownloadRestorableMetadata?,
    hasCustomCover: Boolean,
    coverAssetHash: String?,
    coverAssetFileName: String?,
    clearCoverOverride: Boolean
): RestorableCoverAssetRefs {
    val incomingHash = coverAssetHash?.trim()?.takeIf(String::isNotBlank)
    val incomingFileName = coverAssetFileName
        ?.trim()
        ?.takeIf { name ->
            name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                '/' !in name &&
                '\\' !in name
        }
    val baselineHash = existing?.baselineCoverAssetHash
        ?: incomingHash.takeUnless { hasCustomCover }
    val baselineFileName = existing?.baselineCoverAssetFileName
        ?: incomingFileName.takeUnless { hasCustomCover }
    return RestorableCoverAssetRefs(
        baselineHash = baselineHash,
        currentHash = when {
            clearCoverOverride -> incomingHash ?: baselineHash
            else -> incomingHash ?: existing?.currentCoverAssetHash
        },
        baselineFileName = baselineFileName,
        currentFileName = when {
            clearCoverOverride -> incomingFileName ?: baselineFileName
            else -> incomingFileName ?: existing?.currentCoverAssetFileName
        }
    )
}

internal suspend fun resolveDownloadedMetadataCoverAsset(
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
    coverReference: String?,
    inspect: suspend (String) -> ManagedDownloadCoverAssetStore.MaterializedCover?,
    materialize: suspend (String) -> ManagedDownloadCoverAssetStore.MaterializedCover?
): ManagedDownloadCoverAssetStore.MaterializedCover? {
    val normalizedReference = coverReference?.trim()?.takeIf(String::isNotBlank) ?: return null
    val createdReference = sidecarReferences?.coverReference
        ?.trim()
        ?.takeIf(String::isNotBlank)
    return if (sidecarReferences?.createdCover == true && createdReference == normalizedReference) {
        inspect(normalizedReference)
    } else {
        materialize(normalizedReference)
    }
}

internal class DownloadedAudioMetadataStore(
    private val maxWriteAttempts: Int,
    private val writeRetryDelayMs: Long,
    private val loggerTag: String
) {
    private val writeAttempts: Int
        get() = maxWriteAttempts.coerceAtLeast(1)

    private companion object {
        private const val SIDECAR_READ_PARALLELISM = 2
    }

    suspend fun persist(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null,
        downloadFinalized: Boolean = true,
        metadataEmbeddingState: DownloadedAudioEmbeddingState? = null,
        resolveExistingSidecars: Boolean = true,
        artifactStateOverride: String? = null,
        operationId: String? = null,
        clearRestorableOverrides: RestorableMetadataClearPolicy =
            RestorableMetadataClearPolicy(),
        existingMetadataHint: ManagedDownloadStorage.DownloadedAudioMetadata? = null
    ): Boolean {
        val startedAtNs = System.nanoTime()
        val identity = song.identity()
        val existingMetadata = existingMetadataHint ?: retryMetadataPreparation(
            phase = "READ_BASELINE"
        ) {
            read(context, audio)
        }.getOrElse { error ->
            NPLogger.e(
                loggerTag,
                "读取下载元数据基线失败，保留核心音频等待重试: " +
                    "file=${audio.name}, error=${error.message}",
                error
            )
            return false
        }
        val metadataReadMs = elapsedMs(startedAtNs)
        val resolvedEmbeddingState = resolvePersistedDownloadedAudioEmbeddingState(
            downloadFinalized = downloadFinalized,
            requestedState = metadataEmbeddingState,
            existingState = existingMetadata?.metadataEmbeddingState
        )
        val sidecars = retryMetadataPreparation(phase = "READ_SIDECARS") {
            resolveSidecarReferences(
                context = context,
                audio = audio,
                song = song,
                sidecarReferences = sidecarReferences,
                existingMetadata = existingMetadata,
                resolveExistingSidecars = resolveExistingSidecars
            )
        }.getOrElse { error ->
            NPLogger.e(
                loggerTag,
                "读取下载侧载失败，保留核心音频等待重试: " +
                    "file=${audio.name}, error=${error.message}",
                error
            )
            return false
        }
        val sidecarResolveMs = elapsedMs(startedAtNs) - metadataReadMs
        val materializedCover = retryMetadataPreparation(phase = "BUILD_COVER") {
            resolveDownloadedMetadataCoverAsset(
                sidecarReferences = sidecarReferences,
                coverReference = sidecars.coverReference,
                inspect = { reference ->
                    ManagedDownloadCoverAssetStore.inspect(
                        context = context,
                        reference = reference
                    )
                },
                materialize = { reference ->
                    ManagedDownloadCoverAssetStore.materialize(
                        context = context,
                        reference = reference,
                        preferredFileName = null
                    )
                }
            )
        }.getOrElse { error ->
            NPLogger.e(
                loggerTag,
                "整理下载封面失败，保留核心音频等待重试: " +
                    "file=${audio.name}, error=${error.message}",
                error
            )
            return false
        }
        val coverResolveMs = elapsedMs(startedAtNs) -
            metadataReadMs - sidecarResolveMs
        val persistedSidecars = sidecars.copy(
            coverReference = materializedCover?.reference ?: sidecars.coverReference
        )
        val metadataSong = preserveMissingDownloadedMetadataLyrics(song, existingMetadata)
        val existingBaseline = existingMetadata?.restorableMetadata?.baseline
        val sidecarLyrics = if (downloadFinalized) {
            retryMetadataPreparation(phase = "READ_LYRICS") {
                readRestorableSidecarLyrics(
                    context = context,
                    sidecars = persistedSidecars,
                    readOriginal = shouldReadRestorableSidecarLyric(
                        baselineValue = existingBaseline?.originalLyric,
                        songValue = metadataSong.originalLyric
                    ),
                    readTranslated = shouldReadRestorableSidecarLyric(
                        baselineValue = existingBaseline?.translatedLyric,
                        songValue = metadataSong.originalTranslatedLyric
                    ),
                    readRomanized = shouldReadRestorableSidecarLyric(
                        baselineValue = existingBaseline?.romanizedLyric,
                        songValue = metadataSong.originalRomanizedLyric
                    )
                )
            }.getOrElse { error ->
                NPLogger.e(
                    loggerTag,
                    "读取下载歌词失败，保留核心音频等待重试: " +
                        "file=${audio.name}, error=${error.message}",
                    error
                )
                return false
            }
        } else {
            RestorableSidecarLyrics()
        }
        val lyricReadMs = elapsedMs(startedAtNs) -
            metadataReadMs - sidecarResolveMs - coverResolveMs
        val createdAt = resolveDownloadedMetadataCreatedAt(
            existing = existingMetadata,
            song = song,
            audioLastModifiedMs = audio.lastModifiedMs
        )
        val createdAtMs = createdAt.timestampMs
        val createdAtSource = createdAt.source
        val createdAtConfidence = createdAt.confidence
        val restorableMetadata = resolveRestorableMetadata(
            song = metadataSong,
            existing = existingMetadata?.restorableMetadata,
            coverReference = persistedSidecars.coverReference,
            coverAssetHash = materializedCover?.assetHash,
            coverAssetFileName = materializedCover?.fileName,
            sidecarOriginalLyric = sidecarLyrics.original,
            sidecarTranslatedLyric = sidecarLyrics.translated,
            sidecarRomanizedLyric = sidecarLyrics.romanized,
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
            metadataEmbeddingState = resolvedEmbeddingState,
            createdAtMs = createdAtMs,
            createdAtSource = createdAtSource,
            createdAtConfidence = createdAtConfidence,
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
                ?: song.membershipAddedAtMs?.takeIf { it > 0L }
                ?: createdAtMs.takeIf { it > 0L },
            sourceCreatedAtMs = existingMetadata?.sourceCreatedAtMs
                ?: song.logicalCreatedAtMs?.takeIf { it > 0L },
            sourceModifiedAtMs = existingMetadata?.sourceModifiedAtMs,
            restorableMetadata = restorableMetadata
        )

        var lastError: Throwable? = null
        repeat(writeAttempts) { attempt ->
            val result = try {
                Result.success(ManagedDownloadStorage.saveMetadata(context, audio, payload.toString()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (result.getOrDefault(false)) {
                NPLogger.d(
                    loggerTag,
                    "保存下载 metadata: file=${audio.name}, stableKey=${identity.stableKey()}, finalized=$downloadFinalized, lyricPath=${persistedSidecars.lyricReference}, translatedLyricPath=${persistedSidecars.translatedLyricReference}, romanizedLyricPath=${persistedSidecars.romanizedLyricReference}, coverPath=${persistedSidecars.coverReference}, prepareMs=${elapsedMs(startedAtNs)}, metadataReadMs=$metadataReadMs, sidecarResolveMs=$sidecarResolveMs, coverResolveMs=$coverResolveMs, lyricReadMs=$lyricReadMs"
                )
                return true
            }
            val error = result.exceptionOrNull()
                ?: IllegalStateException("下载元数据写入读回校验失败")
            lastError = error
            if (attempt < writeAttempts - 1) {
                NPLogger.w(
                    loggerTag,
                    "写入下载元数据失败(第${attempt + 1}次): ${audio.name} - ${error.message}"
                )
                delay(writeRetryDelayMs)
            }
        }
        NPLogger.e(
            loggerTag,
            "写入下载元数据最终失败: ${audio.name} - ${lastError?.message}, " +
                "prepareMs=${elapsedMs(startedAtNs)}, metadataReadMs=$metadataReadMs, " +
                "sidecarResolveMs=$sidecarResolveMs, coverResolveMs=$coverResolveMs, " +
                "lyricReadMs=$lyricReadMs",
            lastError
        )
        return false
    }

    private suspend fun <T> retryMetadataPreparation(
        phase: String,
        block: suspend () -> T
    ): Result<T> {
        var lastError: Throwable? = null
        repeat(maxWriteAttempts.coerceAtLeast(1)) { attempt ->
            try {
                return Result.success(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                if (attempt < maxWriteAttempts.coerceAtLeast(1) - 1) {
                    val retryMessage =
                        "元数据准备阶段失败，准备重试: phase=$phase, " +
                            "attempt=${attempt + 1}, error=${error.message}"
                    NPLogger.w(
                        loggerTag,
                        retryMessage
                    )
                    val multiplier = 1L shl attempt.coerceAtMost(4)
                    delay((writeRetryDelayMs.coerceAtLeast(0L) * multiplier)
                        .coerceAtMost(2_000L))
                }
            }
        }
        return Result.failure(
            lastError ?: IllegalStateException("元数据准备阶段失败: $phase")
        )
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
        val metadataEntry = retryMetadataPreparation(phase = "READ_COVER_BASELINE") {
            ManagedDownloadStorage.findMetadataForAudio(context, audio)
        }.getOrElse { error ->
            NPLogger.e(
                loggerTag,
                "读取封面回写基线失败: file=${audio.name}, error=${error.message}",
                error
            )
            return false
        } ?: return false
        val raw = retryMetadataPreparation(phase = "READ_COVER_METADATA") {
            ManagedDownloadStorage.readText(context, metadataEntry.reference)
        }.getOrElse { error ->
            NPLogger.e(
                loggerTag,
                "读取封面回写 metadata 失败: file=${audio.name}, " +
                    "error=${error.message}",
                error
            )
            return false
        } ?: return false
        val materialized = retryMetadataPreparation(phase = "BUILD_COVER_REFERENCE") {
            ManagedDownloadCoverAssetStore.materialize(
                context = context,
                reference = coverReference,
                preferredFileName = null
            )
        }.getOrElse { error ->
            NPLogger.e(
                loggerTag,
                "整理封面回写引用失败: file=${audio.name}, error=${error.message}",
                error
            )
            return false
        }
        val effectiveReference = materialized?.reference ?: coverReference
        val patchedPayload = patchDownloadedMetadataCoverReference(
            rawMetadata = raw,
            coverReference = effectiveReference,
            coverAssetHash = materialized?.assetHash,
            coverAssetFileName = materialized?.fileName
        )
            ?: return false
        var lastError: Throwable? = null
        repeat(writeAttempts) { attempt ->
            val result = try {
                Result.success(ManagedDownloadStorage.saveMetadata(context, audio, patchedPayload))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (result.getOrDefault(false)) {
                NPLogger.d(loggerTag, "补写下载封面侧载引用: file=${audio.name}, coverPath=$effectiveReference")
                return true
            }
            lastError = result.exceptionOrNull()
            if (attempt < writeAttempts - 1) {
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
                romanizedLyricReference = sidecarReferences?.romanizedLyricReference,
                lyricContent = sidecarReferences?.lyricContent,
                translatedLyricContent = sidecarReferences?.translatedLyricContent,
                romanizedLyricContent = sidecarReferences?.romanizedLyricContent
            )
        }

        val candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension)
        val explicitCoverReference = sidecarReferences?.coverReference
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val existingCoverReference = existingMetadata?.coverPath
            ?.trim()
            ?.takeIf(String::isNotBlank)
        // 已有 metadata 是同一音频的权威索引。先复用它，只有没有引用时
        // 才触发 snapshot/SAF 查找，避免大目录下每首歌重复枚举。
        val discoveredCoverReference = explicitCoverReference
            ?: existingCoverReference
            ?: ManagedDownloadStorage.findCoverReference(context, audio)
        return DownloadedMetadataSidecarReferences(
            coverReference = explicitCoverReference
                ?: resolveDownloadedMetadataCoverReference(
                    existingCoverReference = discoveredCoverReference,
                    song = song,
                    previousCustomCoverReference = existingMetadata?.customCoverUrl
                ),
            lyricReference = sidecarReferences?.lyricReference
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: existingMetadata?.lyricPath
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: ManagedDownloadStorage.findLyricLocation(
                    context = context,
                    songId = song.id,
                    candidateBaseNames = candidateBaseNames,
                    translated = false
                )
                ?: existingMetadata?.lyricPath,
            translatedLyricReference = sidecarReferences?.translatedLyricReference
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: existingMetadata?.translatedLyricPath
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: ManagedDownloadStorage.findLyricLocation(
                    context = context,
                    songId = song.id,
                    candidateBaseNames = candidateBaseNames,
                    translated = true
                )
                ?: existingMetadata?.translatedLyricPath,
            romanizedLyricReference = sidecarReferences?.romanizedLyricReference
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: existingMetadata?.romanizedLyricPath
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: ManagedDownloadStorage.findRomanizedLyricLocation(
                    context = context,
                    songId = song.id,
                    candidateBaseNames = candidateBaseNames
                ),
            lyricContent = sidecarReferences?.lyricContent,
            translatedLyricContent = sidecarReferences?.translatedLyricContent,
            romanizedLyricContent = sidecarReferences?.romanizedLyricContent
        )
    }

    private suspend fun readRestorableSidecarLyrics(
        context: Context,
        sidecars: DownloadedMetadataSidecarReferences,
        readOriginal: Boolean,
        readTranslated: Boolean,
        readRomanized: Boolean
    ): RestorableSidecarLyrics {
        val values = readRestorableSidecarLyricsConcurrently(
            references = listOf(
                sidecars.lyricReference.takeIf {
                    readOriginal && sidecars.lyricContent.isNullOrBlank()
                },
                sidecars.translatedLyricReference.takeIf {
                    readTranslated && sidecars.translatedLyricContent.isNullOrBlank()
                },
                sidecars.romanizedLyricReference.takeIf {
                    readRomanized && sidecars.romanizedLyricContent.isNullOrBlank()
                }
            ),
            parallelism = SIDECAR_READ_PARALLELISM
        ) { normalized ->
            try {
                ManagedDownloadStorage.readText(context, normalized)
                    ?.takeIf(String::isNotBlank)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ManagedDownloadRootProviderException) {
                throw error
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(
                    loggerTag,
                    "读取下载歌词侧载失败: reference=$normalized, error=${error.message}"
                )
                null
            }
        }
        return RestorableSidecarLyrics(
            original = sidecars.lyricContent?.takeIf { readOriginal }
                ?: values.getOrNull(0),
            translated = sidecars.translatedLyricContent?.takeIf { readTranslated }
                ?: values.getOrNull(1),
            romanized = sidecars.romanizedLyricContent?.takeIf { readRomanized }
                ?: values.getOrNull(2)
        )
    }

    private fun elapsedMs(startedAtNs: Long): Long {
        return ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun buildMetadataPayload(
        song: SongItem,
        coverReference: String?,
        lyricReference: String?,
        translatedLyricReference: String?,
        romanizedLyricReference: String?,
        downloadTimeMs: Long?,
        downloadFinalized: Boolean,
        metadataEmbeddingState: DownloadedAudioEmbeddingState?,
        createdAtMs: Long,
        createdAtSource: String,
        createdAtConfidence: String,
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
            put("schemaVersion", 6)
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
            put("metadataEmbeddingState", metadataEmbeddingState?.name)
            put("createdAtMs", createdAtMs)
            put("createdAtSource", createdAtSource)
            put("createdAtConfidence", createdAtConfidence)
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
        coverAssetFileName: String?,
        sidecarOriginalLyric: String?,
        sidecarTranslatedLyric: String?,
        sidecarRomanizedLyric: String?,
        createdAtMs: Long,
        clearRestorableOverrides: RestorableMetadataClearPolicy
    ): ManagedDownloadRestorableMetadata {
        val identity = song.identity()
        val baseline = mergeRestorableBaseline(
            existing = existing?.baseline,
            song = song,
            coverReference = coverReference,
            sidecarOriginalLyric = sidecarOriginalLyric,
            sidecarTranslatedLyric = sidecarTranslatedLyric,
            sidecarRomanizedLyric = sidecarRomanizedLyric
        )
        val previous = existing ?: ManagedDownloadRestorableMetadata(
            sourceStableKey = identity.stableKey(),
            baseline = baseline,
            overrides = ManagedDownloadRestorableMetadata.Overrides(),
            createdAtMs = createdAtMs
        )
        val coverAssets = mergeRestorableCoverAssetRefs(
            existing = existing,
            hasCustomCover = !song.customCoverUrl.isNullOrBlank(),
            coverAssetHash = coverAssetHash,
            coverAssetFileName = coverAssetFileName,
            clearCoverOverride = clearRestorableOverrides.cover
        )
        return previous.copy(
            sourceStableKey = previous.sourceStableKey ?: identity.stableKey(),
            baseline = baseline,
            overrides = mergeRestorableOverrides(
                previous = previous.overrides,
                song = song,
                clearRestorableOverrides = clearRestorableOverrides
            ),
            baselineCoverAssetHash = coverAssets.baselineHash,
            currentCoverAssetHash = coverAssets.currentHash,
            baselineCoverAssetFileName = coverAssets.baselineFileName,
            currentCoverAssetFileName = coverAssets.currentFileName,
            createdAtMs = previous.createdAtMs ?: createdAtMs,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private data class DownloadedMetadataSidecarReferences(
        val coverReference: String?,
        val lyricReference: String?,
        val translatedLyricReference: String?,
        val romanizedLyricReference: String?,
        val lyricContent: String? = null,
        val translatedLyricContent: String? = null,
        val romanizedLyricContent: String? = null
    )

    private data class RestorableSidecarLyrics(
        val original: String? = null,
        val translated: String? = null,
        val romanized: String? = null
    )
}

internal fun mergeRestorableOverrides(
    previous: ManagedDownloadRestorableMetadata.Overrides,
    song: SongItem,
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
        // null 表示用户恢复了基线，不要把旧的覆盖值重新带回来
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

internal data class DownloadedMetadataCreatedAt(
    val timestampMs: Long,
    val source: String,
    val confidence: String
)

internal fun resolveDownloadedMetadataCreatedAt(
    existing: ManagedDownloadStorage.DownloadedAudioMetadata?,
    song: SongItem,
    audioLastModifiedMs: Long?,
    nowMs: Long = System.currentTimeMillis()
): DownloadedMetadataCreatedAt {
    val existingCreatedAt = existing?.createdAtMs?.takeIf { it > 0L }
    val existingDownloadTime = existing?.downloadTimeMs?.takeIf { it > 0L }
    val songCreatedAt = song.logicalCreatedAtMs?.takeIf { it > 0L }
    val songAddedAt = song.addedAt.takeIf { it > 0L }
    val audioModifiedAt = audioLastModifiedMs?.takeIf { it > 0L }
    val timestamp = existingCreatedAt
        ?: existingDownloadTime
        ?: songCreatedAt
        ?: songAddedAt
        ?: audioModifiedAt
        ?: nowMs.coerceAtLeast(1L)
    val source = when {
        existingCreatedAt != null -> existing.createdAtSource
        existingDownloadTime != null -> existing.createdAtSource ?: "DOWNLOAD_TIME"
        songCreatedAt != null -> song.createdAtSource ?: "UNKNOWN"
        songAddedAt != null -> song.createdAtSource ?: "UNKNOWN"
        audioModifiedAt != null -> "MTIME"
        else -> "MANAGED_COMMIT"
    }?.trim()?.takeIf(String::isNotBlank) ?: "MANAGED_COMMIT"
    val confidence = when {
        existingCreatedAt != null -> existing.createdAtConfidence
        existingDownloadTime != null -> null
        songCreatedAt != null || songAddedAt != null -> song.createdAtConfidence
        else -> null
    }?.trim()?.takeIf(String::isNotBlank)
        ?: resolveCreatedAtConfidence(source)
    return DownloadedMetadataCreatedAt(
        timestampMs = timestamp,
        source = source,
        confidence = confidence
    )
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
    coverAssetHash: String? = null,
    coverAssetFileName: String? = null
): String? {
    return runCatching {
        val root = JSONObject(rawMetadata).put("coverPath", coverReference)
        val restorable = root.optJSONObject("restorableMetadata")
            ?: return@runCatching null
        val overrides = restorable.optJSONObject("overrides") ?: JSONObject()
        overrides.put("coverReference", coverReference)
        restorable.put("overrides", overrides)
        coverAssetHash?.let { hash ->
            val assets = restorable.optJSONObject("assetRefs") ?: JSONObject()
            assets.put("currentCoverHash", hash)
            coverAssetFileName?.let { fileName ->
                assets.put("currentCoverFileName", fileName)
            }
            val hasCustomCover = root.has("customCoverUrl") &&
                !root.isNull("customCoverUrl") &&
                root.optString("customCoverUrl").trim().isNotBlank()
            if (!hasCustomCover) {
                val baselineHash = assets.optString("baselineCoverHash")
                if (baselineHash.isBlank()) {
                    assets.put("baselineCoverHash", hash)
                    coverAssetFileName?.let { fileName ->
                        assets.put("baselineCoverFileName", fileName)
                    }
                } else if (
                    baselineHash.equals(hash, ignoreCase = true) &&
                    assets.optString("baselineCoverFileName").isBlank()
                ) {
                    coverAssetFileName?.let { fileName ->
                        assets.put("baselineCoverFileName", fileName)
                    }
                }
            }
            restorable.put("assetRefs", assets)
        }
        root.put("restorableMetadata", restorable)
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

internal fun mergeRestorableBaseline(
    existing: ManagedDownloadRestorableMetadata.Baseline?,
    song: SongItem,
    coverReference: String?,
    sidecarOriginalLyric: String? = null,
    sidecarTranslatedLyric: String? = null,
    sidecarRomanizedLyric: String? = null
): ManagedDownloadRestorableMetadata.Baseline {
    val current = existing ?: ManagedDownloadRestorableMetadata.Baseline()
    fun resolveLyric(
        currentValue: String?,
        songValue: String?,
        sidecarValue: String?,
        matchedValue: String?
    ): String? {
        if (currentValue != null) return currentValue
        return songValue ?: sidecarValue ?: matchedValue.takeIf { existing == null }
    }
    return current.copy(
        title = current.title ?: song.originalName ?: song.name,
        artist = current.artist ?: song.originalArtist ?: song.artist,
        album = current.album ?: song.album,
        // 第一次写入要记录来源封面，不能误用当前的覆盖封面
        coverReference = current.coverReference
            ?: song.originalCoverUrl
            ?: song.coverUrl.takeUnless { cover ->
                cover.isNullOrBlank() || cover == song.customCoverUrl
            }
            ?: coverReference.takeIf { song.customCoverUrl.isNullOrBlank() },
        originalLyric = resolveLyric(
            currentValue = current.originalLyric,
            songValue = song.originalLyric,
            sidecarValue = sidecarOriginalLyric,
            matchedValue = song.matchedLyric
        ),
        translatedLyric = resolveLyric(
            currentValue = current.translatedLyric,
            songValue = song.originalTranslatedLyric,
            sidecarValue = sidecarTranslatedLyric,
            matchedValue = song.matchedTranslatedLyric
        ),
        romanizedLyric = resolveLyric(
            currentValue = current.romanizedLyric,
            songValue = song.originalRomanizedLyric,
            sidecarValue = sidecarRomanizedLyric,
            matchedValue = song.matchedRomanizedLyric
        )
    )
}

private fun String?.normalizedCoverReference(): String? {
    return this?.trim()?.takeIf(String::isNotBlank)
}

private fun String.isLocalCoverReference(): Boolean {
    return startsWith("/") ||
        startsWith("file://", ignoreCase = true) ||
        startsWith("content://", ignoreCase = true)
}
