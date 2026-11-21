package moe.ouom.neriplayer.core.player.dlna.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.dlna.protocol.DlnaProtocolInfo
import moe.ouom.neriplayer.core.player.dlna.protocol.FlacHeaderInspector
import moe.ouom.neriplayer.core.player.dlna.protocol.FlacVorbisCommentWriter
import moe.ouom.neriplayer.core.player.dlna.protocol.Mp3Id3TagWriter
import moe.ouom.neriplayer.core.player.dlna.protocol.Mp4MetadataWriter
import moe.ouom.neriplayer.core.player.dlna.protocol.WavId3TagBuilder
import moe.ouom.neriplayer.core.player.dlna.server.DlnaMediaServer
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaAudioQualityReport
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDeviceOptions
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaPlaybackSnapshot
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaStreamMode
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.platform.bilibili.api.stream.isBiliStreamHost
import moe.ouom.neriplayer.platform.bilibili.api.stream.isBiliStreamUrl
import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.platform.youtube.api.transport.youtubeMusicThumbnailUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Resolves, caches locally, verifies lossless audio integrity (hash + header),
 * optionally decodes to raw PCM + WAV/ID3v2.3, and produces an immutable
 * [PreparedCastMedia] bound to a single playback generation.
 */
class LosslessSourceResolver(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val biliCookieHeaderProvider: () -> String,
    private val getLocalizedString: (Int, Array<out Any>) -> String = { resId, args ->
        if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
    }
) {
    companion object {
        private const val STAGING_DIR_NAME = "dlna_staging"
        private const val MAX_STAGING_BYTES = 512L * 1024L * 1024L
        private const val MAX_COVER_DIMENSION = 1024
        private const val BILI_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        internal fun buildStableRemoteStagingKey(
            songKey: String,
            url: String,
            audioInfo: PlaybackAudioInfo?
        ): String {
            val extHint = fileExtensionForMime(
                audioInfo?.mimeType.orEmpty(),
                File(url.substringBefore('?'))
            )
            val raw = if (songKey.isNotBlank()) {
                "$songKey|${audioInfo?.qualityKey.orEmpty()}|${audioInfo?.mimeType.orEmpty()}|$extHint"
            } else {
                url.substringBefore('?')
            }
            return sha256Hex(raw.toByteArray()).take(24)
        }

        internal fun evictStagingDirectory(dir: File, maxBytes: Long = MAX_STAGING_BYTES) {
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            val targetBytes = (maxBytes * 3L) / 4L
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= targetBytes) break
                val len = file.length()
                if (file.delete()) {
                    total -= len
                }
            }
        }

        private fun sha256Hex(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }

        private fun fileExtensionForMime(mimeType: String, file: File): String {
            return when (mimeType.lowercase(Locale.US)) {
                "audio/x-flac", "audio/flac" -> "flac"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/alac", "audio/mp4", "audio/m4a" -> "m4a"
                "audio/aac" -> "aac"
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/ogg" -> "ogg"
                "audio/webm" -> "webm"
                else -> file.extension.ifBlank { "bin" }
            }
        }
    }

    sealed class ResolutionOutcome {
        data class Ready(
            val snapshot: DlnaPlaybackSnapshot,
            val streamContent: DlnaMediaServer.StreamContent,
            val streamExtension: String,
            val coverBytes: ByteArray?,
            val qualityReport: DlnaAudioQualityReport
        ) : ResolutionOutcome()

        data class Blocked(
            val reason: String,
            val qualityReport: DlnaAudioQualityReport
        ) : ResolutionOutcome()

        data class Failed(val reason: String) : ResolutionOutcome()
    }

    private val stagingDir: File
        get() = File(context.cacheDir, STAGING_DIR_NAME).apply { mkdirs() }

    suspend fun prepareForCast(
        song: SongItem,
        generationId: Long,
        urlResult: SongUrlResult.Success,
        requestedLosslessQuality: Boolean,
        device: DlnaDevice,
        options: DlnaDeviceOptions,
        isCancelled: () -> Boolean
    ): ResolutionOutcome = withContext(Dispatchers.IO) {
        if (isCancelled()) return@withContext ResolutionOutcome.Failed("Cancelled")

        val sourceFile = materializeSourceFile(song, urlResult, isCancelled)
            ?: return@withContext ResolutionOutcome.Failed(
                getLocalizedString(CoreCommonR.string.dlna_err_materialize_failed, emptyArray())
            )

        if (isCancelled()) return@withContext ResolutionOutcome.Failed("Cancelled")

        val inspected = FlacHeaderInspector.inspectFile(sourceFile)
        val sha256 = computeSha256(sourceFile)
        val decision = LosslessSourcePolicy.evaluate(
            inspected = inspected,
            audioInfo = urlResult.audioInfo,
            isPreviewClip = urlResult.isPreviewClip,
            requestedLosslessQuality = requestedLosslessQuality,
            device = device,
            options = options
        )

        val songKey = song.stableKey()
        val sourceType = urlResult.audioInfo?.source ?: PlaybackAudioSource.LOCAL

        when (decision) {
            is LosslessSourcePolicy.DeliveryDecision.Blocked -> {
                val localizedReason = when (decision.kind) {
                    LosslessSourcePolicy.BlockReasonKind.PREVIEW_CLIP ->
                        getLocalizedString(
                            CoreCommonR.string.dlna_strict_blocked_preview_detail,
                            arrayOf(decision.sourceSummary)
                        )
                    LosslessSourcePolicy.BlockReasonKind.LOSSY_SOURCE ->
                        getLocalizedString(
                            CoreCommonR.string.dlna_strict_blocked_lossy_detail,
                            arrayOf(decision.sourceSummary)
                        )
                    LosslessSourcePolicy.BlockReasonKind.FLAC_UNSUPPORTED_WAV_DISABLED ->
                        getLocalizedString(CoreCommonR.string.dlna_blocked_flac_unsupported, emptyArray())
                    LosslessSourcePolicy.BlockReasonKind.ALAC_REQUIRES_WAV_FALLBACK ->
                        getLocalizedString(CoreCommonR.string.dlna_blocked_alac_requires_wav, emptyArray())
                }
                val report = DlnaAudioQualityReport(
                    songKey = songKey,
                    source = sourceType,
                    resolvedFormat = decision.resolvedFormat,
                    mimeType = decision.mimeType,
                    sampleRateHz = decision.sampleRateHz,
                    bitDepth = decision.bitDepth,
                    channelCount = inspected.channelCount,
                    bitrateKbps = urlResult.audioInfo?.bitrateKbps,
                    deliveryMode = DlnaStreamMode.ORIGINAL_FLAC,
                    degraded = true,
                    degradationReason = localizedReason,
                    strictLosslessBlocked = true,
                    blockReason = localizedReason,
                    sourceSha256 = sha256,
                    totalBytes = sourceFile.length()
                )
                ResolutionOutcome.Blocked(localizedReason, report)
            }

            is LosslessSourcePolicy.DeliveryDecision.Deliver -> {
                val localizedDegradation = when (decision.degradationKind) {
                    LosslessSourcePolicy.DegradationKind.LOSSY_SOURCE ->
                        getLocalizedString(
                            CoreCommonR.string.dlna_degraded_lossy_detail,
                            arrayOf(decision.sourceSummary)
                        )
                    LosslessSourcePolicy.DegradationKind.DOWNGRADED_TIER ->
                        getLocalizedString(
                            CoreCommonR.string.dlna_degraded_downgraded_detail,
                            arrayOf(decision.sourceSummary)
                        )
                    null -> null
                }
                val coverBytes = loadNormalizedCoverBytes(song, sourceFile)
                val durationMs = song.durationMs.takeIf { it > 0L } ?: (urlResult.durationMs ?: 0L)
                val normalizedMeta = DlnaTrackMetadataNormalizer.normalize(
                    song = song,
                    source = sourceType,
                    biliFallbackAlbum = getLocalizedString(CoreCommonR.string.dlna_album_fallback_bilibili, emptyArray()),
                    neteaseFallbackAlbum = getLocalizedString(CoreCommonR.string.dlna_album_fallback_netease, emptyArray()),
                    youtubeFallbackAlbum = getLocalizedString(CoreCommonR.string.dlna_album_fallback_youtube, emptyArray()),
                    localFallbackAlbum = getLocalizedString(CoreCommonR.string.dlna_album_fallback_local, emptyArray())
                )
                val embedContainerTags = options.wavEmbedId3Enabled || device.isLikelyShanlingEm7

                when (decision.mode) {
                    DlnaStreamMode.ORIGINAL_FLAC,
                    DlnaStreamMode.ADAPTIVE_DIRECT -> {
                        val protocolInfo = DlnaProtocolInfo.protocolInfoFor(
                            mode = decision.mode,
                            sinkProtocolInfo = device.sinkProtocolInfo,
                            mimeOverride = decision.mimeType
                        )
                        val contentType = DlnaProtocolInfo.contentTypeFor(
                            mode = decision.mode,
                            mimeOverride = decision.mimeType
                        )
                        val contentFeatures = DlnaProtocolInfo.contentFeaturesFromProtocolInfo(protocolInfo)
                        val ext = fileExtensionForMime(contentType, sourceFile)
                        val servedFile = if (embedContainerTags) {
                            prepareTaggedStreamFile(
                                sourceFile = sourceFile,
                                inspected = inspected,
                                sha256 = sha256,
                                generationId = generationId,
                                metadata = normalizedMeta,
                                isCancelled = isCancelled
                            )
                        } else {
                            sourceFile
                        }
                        val streamContent = DlnaMediaServer.StreamContent.FileContent(
                            file = servedFile,
                            contentType = contentType,
                            contentFeatures = contentFeatures
                        )
                        val snapshot = DlnaPlaybackSnapshot(
                            generationId = generationId,
                            songKey = songKey,
                            title = normalizedMeta.title,
                            artist = normalizedMeta.artist,
                            album = normalizedMeta.album,
                            durationMs = durationMs,
                            streamPath = "", // filled when registered on server
                            coverPath = null,
                            contentType = contentType,
                            protocolInfo = protocolInfo,
                            totalBytes = servedFile.length(),
                            sampleRateHz = decision.sampleRateHz,
                            bitDepth = decision.bitDepth,
                            channelCount = decision.channelCount,
                            streamMode = decision.mode
                        )
                        val report = DlnaAudioQualityReport(
                            songKey = songKey,
                            source = sourceType,
                            resolvedFormat = decision.resolvedFormat,
                            mimeType = contentType,
                            sampleRateHz = decision.sampleRateHz.takeIf { it > 0 },
                            bitDepth = decision.bitDepth.takeIf { it > 0 },
                            channelCount = decision.channelCount.takeIf { it > 0 },
                            bitrateKbps = urlResult.audioInfo?.bitrateKbps,
                            deliveryMode = decision.mode,
                            degraded = decision.degraded,
                            degradationReason = localizedDegradation,
                            strictLosslessBlocked = false,
                            sourceSha256 = sha256,
                            totalBytes = servedFile.length(),
                            headerPrefixBytes = 0
                        )
                        ResolutionOutcome.Ready(snapshot, streamContent, ext, coverBytes, report)
                    }

                    DlnaStreamMode.LOSSLESS_WAV,
                    DlnaStreamMode.LOSSLESS_WAV_ID3 -> {
                        evictStagingIfNeeded()
                        val pcmCacheFile = File(stagingDir, "${sha256.take(24)}_${generationId}.pcm")
                        val decoded = LosslessWavDecoder.decodeToPcmFile(
                            sourceFile = sourceFile,
                            inspected = inspected,
                            pcmOutputFile = pcmCacheFile,
                            isCancelled = isCancelled
                        )
                        val id3Chunk = if (decision.mode == DlnaStreamMode.LOSSLESS_WAV_ID3) {
                            WavId3TagBuilder.buildId3Chunk(
                                title = normalizedMeta.title,
                                artist = normalizedMeta.artist,
                                album = normalizedMeta.album
                            )
                        } else {
                            ByteArray(0)
                        }
                        val layout = WavId3TagBuilder.buildWavLayout(
                            sampleRateHz = decoded.sampleRateHz,
                            bitsPerSample = decoded.bitsPerSample,
                            channels = decoded.channelCount,
                            pcmDataBytes = decoded.pcmDataBytes,
                            id3Chunk = id3Chunk
                        )
                        val protocolInfo = DlnaProtocolInfo.protocolInfoFor(
                            mode = decision.mode,
                            sinkProtocolInfo = device.sinkProtocolInfo
                        )
                        val contentFeatures = DlnaProtocolInfo.contentFeaturesFromProtocolInfo(protocolInfo)
                        val streamContent = DlnaMediaServer.StreamContent.HybridWavContent(
                            headerPrefix = layout.headerBytes,
                            pcmFile = decoded.pcmFile,
                            pcmDataOffsetInFile = decoded.pcmDataOffsetInFile,
                            pcmDataLength = decoded.pcmDataBytes,
                            contentType = "audio/wav",
                            contentFeatures = contentFeatures
                        )
                        val snapshot = DlnaPlaybackSnapshot(
                            generationId = generationId,
                            songKey = songKey,
                            title = normalizedMeta.title,
                            artist = normalizedMeta.artist,
                            album = normalizedMeta.album,
                            durationMs = durationMs,
                            streamPath = "",
                            coverPath = null,
                            contentType = "audio/wav",
                            protocolInfo = protocolInfo,
                            totalBytes = layout.totalBytes,
                            sampleRateHz = decoded.sampleRateHz,
                            bitDepth = decoded.bitsPerSample,
                            channelCount = decoded.channelCount,
                            streamMode = decision.mode
                        )
                        val report = DlnaAudioQualityReport(
                            songKey = songKey,
                            source = sourceType,
                            resolvedFormat = if (decision.mode == DlnaStreamMode.LOSSLESS_WAV_ID3) {
                                "${decision.resolvedFormat} -> WAV+ID3"
                            } else {
                                "${decision.resolvedFormat} -> WAV"
                            },
                            mimeType = "audio/wav",
                            sampleRateHz = decoded.sampleRateHz,
                            bitDepth = decoded.bitsPerSample,
                            channelCount = decoded.channelCount,
                            bitrateKbps = ((decoded.sampleRateHz.toLong() * decoded.bitsPerSample * decoded.channelCount) / 1000L).toInt(),
                            deliveryMode = decision.mode,
                            degraded = decision.degraded,
                            degradationReason = localizedDegradation,
                            strictLosslessBlocked = false,
                            sourceSha256 = sha256,
                            totalBytes = layout.totalBytes,
                            headerPrefixBytes = layout.headerBytes.size
                        )
                        ResolutionOutcome.Ready(snapshot, streamContent, "wav", coverBytes, report)
                    }
                }
            }
        }
    }

    private fun materializeSourceFile(
        song: SongItem,
        urlResult: SongUrlResult.Success,
        isCancelled: () -> Boolean
    ): File? {
        val url = urlResult.url.trim()
        if (url.isEmpty()) return null

        // Direct local file path or file:// URI
        if (url.startsWith("/") || (!url.contains("://") && File(url).isFile)) {
            return File(url).takeIf { it.isFile && it.length() > 0L }
        }
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.scheme.equals("file", ignoreCase = true)) {
            return uri?.path?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
        }
        if (uri?.scheme.equals("content", ignoreCase = true)) {
            return copyContentUriToStaging(uri!!, song.stableKey(), isCancelled)
        }
        if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) {
            if (url.startsWith("http://offline.cache/", ignoreCase = true)) {
                return null
            }
            val urls = urlResult.playbackUrls()
            for (candidateUrl in urls) {
                if (isCancelled()) return null
                val downloaded = downloadRemoteToStaging(
                    url = candidateUrl,
                    songKey = song.stableKey(),
                    isBilibiliSong = song.channelId.equals("bilibili", ignoreCase = true) ||
                        song.album.startsWith("Bilibili", ignoreCase = true),
                    audioInfo = urlResult.audioInfo,
                    isCancelled = isCancelled
                )
                if (downloaded != null) return downloaded
            }
        }
        // Fallback: try song.localMediaUri if present
        val localUri = song.localMediaUri()
        if (localUri != null) {
            if (localUri.scheme.equals("file", ignoreCase = true)) {
                localUri.path?.let(::File)?.takeIf { it.isFile && it.length() > 0L }?.let { return it }
            }
            if (localUri.scheme.equals("content", ignoreCase = true)) {
                return copyContentUriToStaging(localUri, song.stableKey(), isCancelled)
            }
        }
        return null
    }

    private fun copyContentUriToStaging(
        uri: Uri,
        songKey: String,
        isCancelled: () -> Boolean
    ): File? {
        evictStagingIfNeeded()
        val keyHash = sha256Hex(songKey.toByteArray()).take(24)
        val target = File(stagingDir, "local_$keyHash.audio")
        if (target.isFile && target.length() > 0L) return target
        val tmp = File(stagingDir, "local_$keyHash.tmp")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (!isCancelled()) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
            } ?: return null
            if (isCancelled() || tmp.length() <= 0L) {
                tmp.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return null
            target
        }.getOrNull()
    }

    private fun downloadRemoteToStaging(
        url: String,
        songKey: String,
        isBilibiliSong: Boolean,
        audioInfo: PlaybackAudioInfo?,
        isCancelled: () -> Boolean
    ): File? {
        evictStagingIfNeeded()
        val cacheId = buildStableRemoteStagingKey(songKey, url, audioInfo)
        val target = File(stagingDir, "remote_$cacheId.audio")
        if (target.isFile && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val tmp = File(stagingDir, "remote_$cacheId.tmp")
        return runCatching {
            val builder = Request.Builder().url(url).get()
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            val host = uri?.host?.lowercase(Locale.US).orEmpty()
            val isBiliUrl = isBiliStreamHost(host) || isBiliStreamUrl(url)
            if (isBiliUrl || isBilibiliSong) {
                builder.header("Referer", "https://www.bilibili.com")
                builder.header("User-Agent", BILI_USER_AGENT)
                if (isBiliUrl && url.startsWith("https://", ignoreCase = true)) {
                    val cookies = biliCookieHeaderProvider()
                    if (cookies.isNotBlank()) {
                        builder.header("Cookie", cookies)
                    }
                }
            }
            val call = okHttpClient.newCall(builder.build())
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    tmp.delete()
                    return null
                }
                val body = response.body
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (!isCancelled()) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                        }
                    }
                }
            }
            if (isCancelled() || tmp.length() <= 0L) {
                tmp.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return null
            target
        }.getOrNull()
    }

    private fun loadNormalizedCoverBytes(song: SongItem, sourceFile: File): ByteArray? {
        val candidates = buildList {
            song.customCoverUrl?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            song.coverUrl?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            song.originalCoverUrl?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            extractYouTubeMusicVideoId(song.mediaUri)?.takeIf { it.isNotBlank() }?.let { videoId ->
                add(youtubeMusicThumbnailUrl(videoId))
            }
            song.localMediaUri()?.let { localUri ->
                runCatching {
                    LocalMediaSupport.inspectQuick(context, localUri, includeAudioTrackInfo = false)
                }.getOrNull()?.coverUri?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()

        for (candidate in candidates) {
            val bytes = readCoverBytes(candidate) ?: continue
            val normalized = normalizeCoverToJpegOrPng(bytes)
            if (normalized != null) return normalized
        }

        // Embedded artwork fallback directly from the materialized audio file (local FLAC/MP3/M4A)
        val embedded = extractEmbeddedCoverFromFile(sourceFile) ?: return null
        return normalizeCoverToJpegOrPng(embedded)
    }

    private fun extractEmbeddedCoverFromFile(file: File): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readCoverBytes(rawReference: String): ByteArray? {
        val reference = if (rawReference.startsWith("//")) "https:$rawReference" else rawReference
        return runCatching {
            when {
                reference.startsWith("/") || (!reference.contains("://") && File(reference).isFile) ->
                    File(reference).takeIf { it.isFile }?.readBytes()
                reference.startsWith("file://", ignoreCase = true) ->
                    Uri.parse(reference).path?.let(::File)?.takeIf { it.isFile }?.readBytes()
                reference.startsWith("content://", ignoreCase = true) ->
                    context.contentResolver.openInputStream(Uri.parse(reference))?.use { it.readBytes() }
                reference.startsWith("http://", ignoreCase = true) ||
                    reference.startsWith("https://", ignoreCase = true) -> {
                    val reqBuilder = Request.Builder().url(reference).get()
                    val host = runCatching { Uri.parse(reference).host?.lowercase(Locale.US) }.getOrNull().orEmpty()
                    if (host.endsWith("hdslb.com") || host.endsWith("biliimg.com")) {
                        reqBuilder.header("Referer", "https://www.bilibili.com")
                        reqBuilder.header("User-Agent", BILI_USER_AGENT)
                    }
                    okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.isSuccessful) resp.body.bytes() else null
                    }
                }
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeCoverToJpegOrPng(raw: ByteArray): ByteArray? {
        // Already JPEG or PNG within reasonable size -> keep original bytes
        val isPng = raw.size >= 8 && raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte()
        val isJpeg = raw.size >= 3 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()
        if ((isPng || isJpeg) && raw.size <= 2 * 1024 * 1024) {
            return raw
        }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val scaled = if (bitmap.width > MAX_COVER_DIMENSION || bitmap.height > MAX_COVER_DIMENSION) {
            val scale = minOf(
                MAX_COVER_DIMENSION.toFloat() / bitmap.width,
                MAX_COVER_DIMENSION.toFloat() / bitmap.height
            )
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        scaled.recycle()
        return out.toByteArray()
    }

    private fun evictStagingIfNeeded() {
        evictStagingDirectory(stagingDir, MAX_STAGING_BYTES)
    }

    fun clearStaging() {
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    private fun prepareTaggedStreamFile(
        sourceFile: File,
        inspected: FlacHeaderInspector.InspectedAudio,
        sha256: String,
        generationId: Long,
        metadata: DlnaTrackMetadataNormalizer.NormalizedMetadata,
        isCancelled: () -> Boolean
    ): File {
        if (isCancelled()) return sourceFile
        evictStagingIfNeeded()
        val tagSig = sha256Hex("${metadata.title}|${metadata.artist}|${metadata.album}".toByteArray()).take(10)
        return when (inspected.format) {
            FlacHeaderInspector.ContainerFormat.FLAC -> {
                val tagged = File(stagingDir, "tagged_${sha256.take(16)}_${tagSig}_$generationId.flac")
                if (FlacVorbisCommentWriter.writeTags(sourceFile, tagged, metadata.title, metadata.artist, metadata.album)) {
                    tagged
                } else {
                    sourceFile
                }
            }
            FlacHeaderInspector.ContainerFormat.MP3 -> {
                val tagged = File(stagingDir, "tagged_${sha256.take(16)}_${tagSig}_$generationId.mp3")
                if (Mp3Id3TagWriter.writeTags(sourceFile, tagged, metadata.title, metadata.artist, metadata.album)) {
                    tagged
                } else {
                    sourceFile
                }
            }
            FlacHeaderInspector.ContainerFormat.AAC_OR_MP4,
            FlacHeaderInspector.ContainerFormat.ALAC_MP4 -> {
                val defragmented = if (Mp4MetadataWriter.hasMoofBox(sourceFile)) {
                    val m4a = File(stagingDir, "defrag_${sha256.take(16)}_$generationId.m4a")
                    Mp4Defragmenter.defragmentIfNeeded(sourceFile, m4a, isCancelled)
                } else {
                    sourceFile
                }
                val tagged = File(stagingDir, "tagged_${sha256.take(16)}_${tagSig}_$generationId.m4a")
                val wrote = Mp4MetadataWriter.writeTags(
                    sourceFile = defragmented,
                    targetFile = tagged,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album
                )
                if (defragmented != sourceFile && wrote) {
                    defragmented.delete()
                }
                if (wrote) tagged else defragmented
            }
            else -> sourceFile
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
