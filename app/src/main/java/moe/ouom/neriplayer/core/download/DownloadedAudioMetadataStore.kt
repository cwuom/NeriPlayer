package moe.ouom.neriplayer.core.download

import android.content.Context
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.json.JSONObject

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
        resolveExistingSidecars: Boolean = true
    ): Boolean {
        val identity = song.identity()
        val sidecars = resolveSidecarReferences(
            context = context,
            audio = audio,
            song = song,
            sidecarReferences = sidecarReferences,
            resolveExistingSidecars = resolveExistingSidecars
        )
        val payload = buildMetadataPayload(
            song = song,
            coverReference = sidecars.coverReference,
            lyricReference = sidecars.lyricReference,
            translatedLyricReference = sidecars.translatedLyricReference,
            downloadFinalized = downloadFinalized
        )

        var lastError: Throwable? = null
        repeat(maxWriteAttempts) { attempt ->
            val result = runCatching {
                ManagedDownloadStorage.saveMetadata(context, audio, payload.toString())
            }
            if (result.getOrDefault(false)) {
                NPLogger.d(
                    loggerTag,
                    "保存下载 metadata: file=${audio.name}, stableKey=${identity.stableKey()}, finalized=$downloadFinalized, lyricPath=${sidecars.lyricReference}, translatedLyricPath=${sidecars.translatedLyricReference}, coverPath=${sidecars.coverReference}"
                )
                return true
            }
            lastError = result.exceptionOrNull()
                ?: IllegalStateException("下载元数据写入读回校验失败")
            if (attempt < maxWriteAttempts - 1) {
                NPLogger.w(
                    loggerTag,
                    "写入下载元数据失败(第${attempt + 1}次): ${audio.name} - ${lastError?.message}"
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

    private suspend fun resolveSidecarReferences(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        resolveExistingSidecars: Boolean
    ): DownloadedMetadataSidecarReferences {
        if (!resolveExistingSidecars) {
            return DownloadedMetadataSidecarReferences(
                coverReference = sidecarReferences?.coverReference,
                lyricReference = sidecarReferences?.lyricReference,
                translatedLyricReference = sidecarReferences?.translatedLyricReference
            )
        }

        val candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension)
        return DownloadedMetadataSidecarReferences(
            coverReference = sidecarReferences?.coverReference
                ?: ManagedDownloadStorage.findCoverReference(context, audio)
                ?: ManagedDownloadStorage.findReusableCoverReference(
                    context = context,
                    song = song,
                    excludedAudioName = audio.name
                ),
            lyricReference = sidecarReferences?.lyricReference
                ?: ManagedDownloadStorage.findLyricLocation(
                    context = context,
                    songId = song.id,
                    candidateBaseNames = candidateBaseNames,
                    translated = false
                ),
            translatedLyricReference = sidecarReferences?.translatedLyricReference
                ?: ManagedDownloadStorage.findLyricLocation(
                    context = context,
                    songId = song.id,
                    candidateBaseNames = candidateBaseNames,
                    translated = true
                )
        )
    }

    private fun buildMetadataPayload(
        song: SongItem,
        coverReference: String?,
        lyricReference: String?,
        translatedLyricReference: String?,
        downloadFinalized: Boolean
    ): JSONObject {
        val identity = song.identity()
        return JSONObject().apply {
            put("stableKey", identity.stableKey())
            put("songId", song.id)
            put("identityAlbum", identity.album)
            put("name", song.name)
            put("artist", song.artist)
            put("coverUrl", song.coverUrl)
            put("matchedLyric", song.matchedLyric)
            put("matchedTranslatedLyric", song.matchedTranslatedLyric)
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
            put("mediaUri", identity.mediaUri ?: song.mediaUri)
            put("channelId", song.channelId)
            put("audioId", song.audioId)
            put("subAudioId", song.subAudioId)
            put("playlistContextId", song.playlistContextId)
            put("coverPath", coverReference)
            put("lyricPath", lyricReference)
            put("translatedLyricPath", translatedLyricReference)
            put("durationMs", song.durationMs)
            put("downloadFinalized", downloadFinalized)
        }
    }

    private data class DownloadedMetadataSidecarReferences(
        val coverReference: String?,
        val lyricReference: String?,
        val translatedLyricReference: String?
    )
}
