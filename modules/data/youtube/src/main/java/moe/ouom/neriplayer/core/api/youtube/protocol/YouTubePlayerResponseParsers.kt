package moe.ouom.neriplayer.core.api.youtube.protocol

import moe.ouom.neriplayer.core.api.youtube.YouTubeAudioMetadata
import moe.ouom.neriplayer.core.api.youtube.challenge.YouTubeStreamingCipherResolver
import moe.ouom.neriplayer.core.api.youtube.challenge.extractStreamQueryParameter
import moe.ouom.neriplayer.core.api.youtube.playback.YouTubePlayableAudio
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeGoogleVideoHost
import org.json.JSONArray
import org.json.JSONObject

private data class YouTubePlayerAudioCandidate(
    val format: JSONObject,
    val mimeType: String?,
    val bitrate: Int,
    val audioSampleRate: Int,
    val contentLength: Long?,
    val durationMs: Long
)

private data class YouTubeStreamingFormatDescriptor(
    val url: String,
    val signatureParameter: String = "sig",
    val signature: String? = null,
    val encryptedSignature: String? = null
)

internal data class YouTubePlayerPlayabilityStatus(
    val status: String,
    val reason: String
)

internal enum class YouTubeMusicPlaybackQuality {
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH;

    companion object {
        fun fromSetting(settingKey: String?): YouTubeMusicPlaybackQuality {
            return when (settingKey?.lowercase(Locale.US)) {
                "low",
                "standard" -> LOW
                "medium" -> MEDIUM
                "high",
                "higher" -> HIGH
                "very_high",
                "very-high",
                "exhigh",
                "lossless",
                "hires",
                "jyeffect",
                "sky",
                "jymaster" -> VERY_HIGH
                else -> VERY_HIGH
            }
        }
    }
}

internal object YouTubeMusicPlaybackParser {
    fun parsePlayableAudio(
        root: JSONObject,
        preferredQualityKey: String? = null,
        preferM4a: Boolean = false,
        cipherResolver: YouTubeStreamingCipherResolver? = null,
        maxCandidateCount: Int = Int.MAX_VALUE
    ): YouTubePlayableAudio? {
        val candidates = selectedAudioCandidates(root, preferredQualityKey, preferM4a, maxCandidateCount)
        val durationFallbackMs = parseDurationMs(root)
        // 丢掉一个候选就白付一次完整求解, 单条求解日志被一次性守卫盖住了看不出轮数
        var discardedCandidates = 0
        for (candidate in candidates) {
            val playableUrl = resolveFormatUrl(candidate.format, cipherResolver)
            if (playableUrl.isBlank()) {
                discardedCandidates++
                continue
            }
            logAcceptedCandidateFallback(discardedCandidates, candidates.size, candidate)
            return candidate.toPlayableAudio(playableUrl, durationFallbackMs)
        }
        logRejectedCandidateFallback(root, discardedCandidates, candidates.size)
        return null
    }

    suspend fun parsePlayableAudioAsync(
        root: JSONObject,
        preferredQualityKey: String? = null,
        preferM4a: Boolean = false,
        cipherResolver: YouTubeStreamingCipherResolver? = null,
        maxCandidateCount: Int = Int.MAX_VALUE
    ): YouTubePlayableAudio? {
        val candidates = selectedAudioCandidates(root, preferredQualityKey, preferM4a, maxCandidateCount)
        val durationFallbackMs = parseDurationMs(root)
        var discardedCandidates = 0
        for (candidate in candidates) {
            val playableUrl = resolveFormatUrlAsync(candidate.format, cipherResolver)
            if (playableUrl.isBlank()) {
                discardedCandidates++
                continue
            }
            logAcceptedCandidateFallback(discardedCandidates, candidates.size, candidate)
            return candidate.toPlayableAudio(playableUrl, durationFallbackMs)
        }
        logRejectedCandidateFallback(root, discardedCandidates, candidates.size)
        return null
    }

    private fun selectedAudioCandidates(
        root: JSONObject,
        preferredQualityKey: String?,
        preferM4a: Boolean,
        maxCandidateCount: Int
    ): List<YouTubePlayerAudioCandidate> {
        return selectCandidate(collectAudioCandidates(root), preferredQualityKey, preferM4a)
            .take(maxCandidateCount.coerceAtLeast(0))
    }

    private fun YouTubePlayerAudioCandidate.toPlayableAudio(
        playableUrl: String,
        durationFallbackMs: Long
    ): YouTubePlayableAudio {
        return YouTubePlayableAudio(
            url = playableUrl,
            durationMs = positiveDurationOrFallback(durationMs, durationFallbackMs),
            mimeType = mimeType,
            contentLength = contentLength,
            bitrateKbps = positiveIntOrNull(bitrate)?.let(::roundedBitrateKbps),
            sampleRateHz = positiveIntOrNull(audioSampleRate)
        )
    }

    private fun positiveDurationOrFallback(durationMs: Long, fallbackMs: Long): Long =
        if (durationMs > 0L) durationMs else fallbackMs

    private fun positiveIntOrNull(value: Int): Int? = if (value > 0) value else null

    private fun roundedBitrateKbps(bitrate: Int): Int = (bitrate + 500) / 1000

    private fun logAcceptedCandidateFallback(
        discardedCandidates: Int,
        candidateCount: Int,
        candidate: YouTubePlayerAudioCandidate
    ) {
        if (discardedCandidates <= 0) return
        NPLogger.d(
            "YouTubeMusicPlayback",
            "playable audio candidate fallback: discarded=$discardedCandidates, total=$candidateCount, acceptedMime=${candidate.mimeType}, acceptedBitrate=${candidate.bitrate}"
        )
    }

    private fun logRejectedCandidateFallback(
        root: JSONObject,
        discardedCandidates: Int,
        candidateCount: Int
    ) {
        if (discardedCandidates <= 0) return
        NPLogger.w(
            "YouTubeMusicPlayback",
            "playable audio has no usable candidate: discarded=$discardedCandidates, " +
                "total=$candidateCount, diagnostics=" + describeAudioCandidates(root)
        )
    }

    /** 只输出候选结构, 便于定位 player.js/格式变化而不泄露直链或签名 */
    fun describeAudioCandidates(root: JSONObject): String {
        return collectAudioCandidates(root).joinToString(separator = ";", transform = ::describeCandidate)
    }

    private fun describeCandidate(candidate: YouTubePlayerAudioCandidate): String {
        return "mime=${candidate.mimeType ?: "?"},bitrate=${candidate.bitrate}," +
            describeFormatFeatures(candidate.format)
    }

    private fun describeFormatFeatures(format: JSONObject): String {
        val directUrl = format.optString("url").isNotBlank()
        val cipher = rawFormatCipher(format)
        val cipherParams = parseUrlEncodedQuery(cipher)
        val rawUrl = format.optString("url").ifBlank { cipherParams["url"].orEmpty() }
        val hasN = extractStreamQueryParameter(rawUrl, "n") != null
        val hasS = cipherParams["s"].orEmpty().isNotBlank()
        val hasSignature = hasFormatSignature(cipherParams)
        return "direct=$directUrl,cipher=${cipher.isNotBlank()},n=$hasN,s=$hasS,sig=$hasSignature"
    }

    private fun rawFormatCipher(format: JSONObject): String {
        return format.optString("signatureCipher")
            .ifBlank { format.optString("cipher") }
            .trim()
    }

    private fun hasFormatSignature(params: Map<String, String>): Boolean {
        return listOf("sig", "signature").any { key -> params[key]?.isNotBlank() == true }
    }

    // 判断原始响应是否本就包含 googlevideo direct 音频流
    // 用于识别"WEB_REMIX 返回了 direct 流, 但候选因 n 无法解出被 #Y4 丢弃"的场景:
    // 这类场景换 locale 不会改变 player.js/n 的可解性, 应直接切换下一 client 而非重试当前 client 其他 locale
    fun hasDirectGoogleVideoAudioStream(root: JSONObject): Boolean {
        return collectAudioCandidates(root).any(::isDirectGoogleVideoAudioCandidate)
    }

    private fun isDirectGoogleVideoAudioCandidate(candidate: YouTubePlayerAudioCandidate): Boolean {
        val rawUrl = resolveFormatUrl(candidate.format, cipherResolver = null)
        return rawUrl.isNotBlank() && isYouTubeGoogleVideoStream(rawUrl)
    }

    fun parsePreferredAudioMetadata(
        root: JSONObject,
        preferredQualityKey: String? = null,
        preferM4a: Boolean = false
    ): YouTubeAudioMetadata? {
        val candidates = collectAudioCandidates(root)
        val selected = selectCandidate(candidates, preferredQualityKey, preferM4a).firstOrNull()

        val durationMs = selected?.durationMs?.takeIf { it > 0L } ?: parseDurationMs(root)
        val mimeType = selected?.mimeType
        val contentLength = selected?.contentLength
        return audioMetadataOrNull(
            durationMs = durationMs,
            mimeType = mimeType,
            contentLength = contentLength
        )
    }

    private fun audioMetadataOrNull(
        durationMs: Long,
        mimeType: String?,
        contentLength: Long?
    ): YouTubeAudioMetadata? {
        if (durationMs <= 0L && mimeType.isNullOrBlank() && contentLength == null) return null
        return YouTubeAudioMetadata(durationMs, mimeType, contentLength)
    }

    fun parsePlayabilityStatus(root: JSONObject): YouTubePlayerPlayabilityStatus {
        val playabilityStatus = root.optJSONObject("playabilityStatus")
        return YouTubePlayerPlayabilityStatus(
            status = optStringOrEmpty(playabilityStatus, "status"),
            reason = optStringOrEmpty(playabilityStatus, "reason")
        )
    }

    private fun optStringOrEmpty(source: JSONObject?, key: String): String =
        source?.optString(key).orEmpty()

    private fun collectAudioCandidates(
        root: JSONObject
    ): List<YouTubePlayerAudioCandidate> {
        val streamingData = root.optJSONObject("streamingData") ?: return emptyList()
        val formatArrays = listOfNotNull(
            streamingData.optJSONArray("adaptiveFormats"),
            streamingData.optJSONArray("formats")
        )
        return formatArrays.flatMap(::audioCandidatesIn)
    }

    private fun audioCandidatesIn(formats: JSONArray): List<YouTubePlayerAudioCandidate> =
        (0 until formats.length()).mapNotNull { index ->
            formats.optJSONObject(index)?.let(::audioCandidate)
        }

    private fun audioCandidate(format: JSONObject): YouTubePlayerAudioCandidate? {
        val mimeType = normalizeMimeType(format.optString("mimeType"))
        if (mimeType?.startsWith("audio/") != true) return null
        return YouTubePlayerAudioCandidate(
            format = format,
            mimeType = mimeType,
            bitrate = parseIntLike(format.opt("bitrate"), format.opt("averageBitrate")),
            audioSampleRate = parseIntLike(format.opt("audioSampleRate")),
            contentLength = parseLongLike(format.opt("contentLength")).takeIf { it > 0L },
            durationMs = parseLongLike(format.opt("approxDurationMs"))
        )
    }

    private fun resolveFormatUrl(
        format: JSONObject,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String {
        val descriptor = parseStreamingFormat(format) ?: return ""
        val signedUrl = resolveSignedFormatUrl(descriptor, cipherResolver) ?: return ""
        return resolveStreamingUrl(signedUrl, cipherResolver)
    }

    private fun parseStreamingFormat(format: JSONObject): YouTubeStreamingFormatDescriptor? {
        val directUrl = format.optString("url").trim()
        if (directUrl.isNotBlank()) return YouTubeStreamingFormatDescriptor(directUrl)
        val cipher = rawFormatCipher(format)
        if (cipher.isBlank()) return null
        return parseCipherFormat(cipher)
    }

    private fun parseCipherFormat(cipher: String): YouTubeStreamingFormatDescriptor? {
        val params = parseUrlEncodedQuery(cipher)
        val url = cipherUrl(params) ?: return null
        val signature = cipherSignature(params)
        return YouTubeStreamingFormatDescriptor(
            url = url,
            signatureParameter = params["sp"].orEmpty().ifBlank { "sig" },
            signature = signature,
            encryptedSignature = if (signature == null) params["s"] else null
        )
    }

    private fun cipherUrl(params: Map<String, String>): String? =
        params["url"]?.decodeUrlComponent()?.takeIf(String::isNotBlank)

    private fun cipherSignature(params: Map<String, String>): String? =
        params["sig"].orEmpty()
            .ifBlank { params["signature"].orEmpty() }
            .takeIf(String::isNotBlank)

    private fun resolveSignedFormatUrl(
        descriptor: YouTubeStreamingFormatDescriptor,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String? {
        plainSignedUrl(descriptor)?.let { return it }
        val encrypted = descriptor.encryptedSignature ?: return descriptor.url
        cipherResolver?.prewarmChallenges(
            encryptedSignature = encrypted,
            obfuscatedThrottlingParameter = extractStreamQueryParameter(descriptor.url, "n")
        )
        return resolveEncryptedSignatureUrl(descriptor, encrypted, cipherResolver)
    }

    private fun plainSignedUrl(descriptor: YouTubeStreamingFormatDescriptor): String? =
        descriptor.signature?.let {
            appendQueryParameter(descriptor.url, descriptor.signatureParameter, it)
        }

    private fun resolveEncryptedSignatureUrl(
        descriptor: YouTubeStreamingFormatDescriptor,
        encrypted: String,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String? {
        if (encrypted.isBlank()) return null
        return appendResolvedSignature(descriptor, cipherResolver?.resolveSignature(encrypted))
    }

    private fun appendResolvedSignature(
        descriptor: YouTubeStreamingFormatDescriptor,
        signature: String?
    ): String? {
        if (signature.isNullOrBlank()) return null
        return appendQueryParameter(descriptor.url, descriptor.signatureParameter, signature)
    }

    private fun resolveStreamingUrl(
        url: String,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String {
        if (url.isBlank()) {
            return ""
        }
        // 无 resolver 时保持原样返回, 不破坏正常路径
        val resolver = cipherResolver ?: return url
        // resolver 对"带 n 参数但解不出"的候选返回空串表示该候选不可用
        // 必须原样透传空串让 parsePlayableAudio 跳过该候选
        // 而非回退到带混淆 n 的原 URL (会被 googlevideo 限速, #Y4/#257)
        return resolver.resolveStreamingUrl(url)
    }

    private suspend fun resolveFormatUrlAsync(
        format: JSONObject,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String {
        val descriptor = parseStreamingFormat(format) ?: return ""
        val signedUrl = resolveSignedFormatUrlAsync(descriptor, cipherResolver) ?: return ""
        return resolveStreamingUrlAsync(signedUrl, cipherResolver)
    }

    private suspend fun resolveSignedFormatUrlAsync(
        descriptor: YouTubeStreamingFormatDescriptor,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String? {
        plainSignedUrl(descriptor)?.let { return it }
        val encrypted = descriptor.encryptedSignature ?: return descriptor.url
        cipherResolver?.prewarmChallengesAsync(
            encryptedSignature = encrypted,
            obfuscatedThrottlingParameter = extractStreamQueryParameter(descriptor.url, "n")
        )
        return resolveEncryptedSignatureUrlAsync(descriptor, encrypted, cipherResolver)
    }

    private suspend fun resolveEncryptedSignatureUrlAsync(
        descriptor: YouTubeStreamingFormatDescriptor,
        encrypted: String,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String? {
        if (encrypted.isBlank()) return null
        return appendResolvedSignature(descriptor, cipherResolver?.resolveSignatureAsync(encrypted))
    }

    private suspend fun resolveStreamingUrlAsync(
        url: String,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String {
        if (url.isBlank()) {
            return ""
        }
        val resolver = cipherResolver ?: return url
        return resolver.resolveStreamingUrlAsync(url)
    }

    private fun parseDurationMs(root: JSONObject): Long {
        val videoDuration = positiveDurationMs(root.optJSONObject("videoDetails")?.opt("lengthSeconds"))
        if (videoDuration > 0L) return videoDuration
        val microformatDuration = root.optJSONObject("microformat")
            ?.optJSONObject("playerMicroformatRenderer")
            ?.opt("lengthSeconds")
        return positiveDurationMs(microformatDuration)
    }

    private fun positiveDurationMs(rawSeconds: Any?): Long {
        val seconds = parseLongLike(rawSeconds)
        return if (seconds > 0L) seconds * 1000L else 0L
    }

    private fun normalizeMimeType(rawMimeType: String): String? {
        return rawMimeType
            .substringBefore(';')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun isM4aAudioContainer(mimeType: String?): Boolean {
        return mimeType?.lowercase(Locale.US) in M4A_AUDIO_MIME_TYPES
    }

    private val M4A_AUDIO_MIME_TYPES = setOf("audio/mp4", "audio/m4a", "audio/aac")

    private fun mimePreferenceScore(mimeType: String?): Int =
        MIME_PREFERENCE_SCORES[mimeType?.lowercase(Locale.US)] ?: 0

    private val MIME_PREFERENCE_SCORES = mapOf(
        "audio/mp4" to 2,
        "audio/m4a" to 2,
        "audio/aac" to 2,
        "audio/webm" to 1
    )

    private fun selectCandidate(
        candidates: List<YouTubePlayerAudioCandidate>,
        preferredQualityKey: String?,
        preferM4a: Boolean = false
    ): List<YouTubePlayerAudioCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }
        if (!preferM4a) {
            return orderCandidatesByQualityTier(
                candidates = candidates,
                preferredQualityKey = preferredQualityKey,
                comparator = audioCandidateComparator()
            )
        }
        // preferM4a (尤其下载/requireDirect) 把容器为 m4a/mp4/aac 作为硬性首要键:
        // 先在可打标的 m4a 候选内部按画质挡位排序, 只有完全没有 m4a 时才回退 webm/opus
        // 避免下载落到无法内嵌标签的 webm (#Y3/#223)
        val comparator = compareByDescending<YouTubePlayerAudioCandidate> { mimePreferenceScore(it.mimeType) }
            .thenByDescending { it.bitrate }
            .thenByDescending { it.audioSampleRate }
            .thenByDescending { it.contentLength ?: 0L }
        val m4aCandidates = candidates.filter { isM4aAudioContainer(it.mimeType) }
        val fallbackCandidates = candidates.filterNot { isM4aAudioContainer(it.mimeType) }
        return orderCandidatesByQualityTier(m4aCandidates, preferredQualityKey, comparator) +
            orderCandidatesByQualityTier(fallbackCandidates, preferredQualityKey, comparator)
    }

    private fun orderCandidatesByQualityTier(
        candidates: List<YouTubePlayerAudioCandidate>,
        preferredQualityKey: String?,
        comparator: Comparator<YouTubePlayerAudioCandidate>
    ): List<YouTubePlayerAudioCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }
        val sortedDescending = candidates.sortedWith(comparator)
        val sortedAscending = sortedDescending.asReversed()
        return when (YouTubeMusicPlaybackQuality.fromSetting(preferredQualityKey)) {
            YouTubeMusicPlaybackQuality.LOW -> sortedAscending
            YouTubeMusicPlaybackQuality.MEDIUM -> prioritizeThresholdCandidate(
                sortedAscending = sortedAscending,
                thresholdBitrate = 96_000
            )
            YouTubeMusicPlaybackQuality.HIGH -> prioritizeThresholdCandidate(
                sortedAscending = sortedAscending,
                thresholdBitrate = 128_000
            )
            YouTubeMusicPlaybackQuality.VERY_HIGH -> sortedDescending
        }
    }

    private fun prioritizeThresholdCandidate(
        sortedAscending: List<YouTubePlayerAudioCandidate>,
        thresholdBitrate: Int
    ): List<YouTubePlayerAudioCandidate> {
        val preferredIndex = sortedAscending.indexOfFirst { it.bitrate >= thresholdBitrate }
        if (preferredIndex < 0) {
            return sortedAscending.asReversed()
        }
        return buildList(sortedAscending.size) {
            addAll(sortedAscending.subList(preferredIndex, sortedAscending.size))
            addAll(sortedAscending.subList(0, preferredIndex).asReversed())
        }
    }

    private fun audioCandidateComparator(): Comparator<YouTubePlayerAudioCandidate> {
        return compareByDescending<YouTubePlayerAudioCandidate> { it.bitrate }
            .thenByDescending { it.audioSampleRate }
            .thenByDescending { mimePreferenceScore(it.mimeType) }
            .thenByDescending { it.contentLength ?: 0L }
    }

    private fun parseUrlEncodedQuery(rawQuery: String): Map<String, String> {
        return rawQuery.split('&')
            .mapNotNull { segment ->
                val key = segment.substringBefore('=').decodeUrlComponent()
                if (key.isBlank()) {
                    null
                } else {
                    key to segment.substringAfter('=', "").decodeUrlComponent()
                }
            }
            .toMap()
    }

    private fun appendQueryParameter(url: String, key: String, value: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return if (Regex("(^|[?&])${Regex.escape(key)}=").containsMatchIn(url)) {
            url
        } else {
            buildString(url.length + key.length + value.length + 2) {
                append(url)
                append(separator)
                append(key)
                append('=')
                append(value.encodeUrlComponent())
            }
        }
    }

    private fun parseLongLike(vararg values: Any?): Long {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun parseIntLike(vararg values: Any?): Int {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    private fun String.decodeUrlComponent(): String {
        return URLDecoder.decode(this, Charsets.UTF_8.name())
    }

    private fun String.encodeUrlComponent(): String {
        return URLEncoder.encode(this, Charsets.UTF_8.name())
    }
}

internal data class YouTubeHlsAudioPlaylist(
    val uri: String,
    val contentLength: Long? = null,
    val estimatedBitrate: Int = 0,
    val audioItag: Int? = null
)

internal object YouTubeMusicHlsManifestParser {
    fun selectAudioPlaylist(
        masterManifest: String,
        masterManifestUrl: String? = null,
        preferredQualityKey: String? = null,
        durationMs: Long = 0L
    ): YouTubeHlsAudioPlaylist? {
        val candidates = collectAudioPlaylists(
            masterManifest = masterManifest,
            masterManifestUrl = masterManifestUrl,
            durationMs = durationMs
        )
        if (candidates.isEmpty()) {
            return null
        }
        val sortedDescending = candidates.sortedWith(
            compareByDescending<YouTubeHlsAudioPlaylist> { it.estimatedBitrate }
                .thenByDescending { it.contentLength ?: 0L }
                .thenByDescending { it.audioItag ?: 0 }
        )
        val sortedAscending = sortedDescending.asReversed()
        return when (YouTubeMusicPlaybackQuality.fromSetting(preferredQualityKey)) {
            YouTubeMusicPlaybackQuality.LOW -> sortedAscending.first()
            YouTubeMusicPlaybackQuality.MEDIUM -> {
                sortedAscending.firstOrNull { it.estimatedBitrate >= 96_000 }
                    ?: sortedDescending.first()
            }
            YouTubeMusicPlaybackQuality.HIGH -> {
                sortedAscending.firstOrNull { it.estimatedBitrate >= 128_000 }
                    ?: sortedDescending.first()
            }
            YouTubeMusicPlaybackQuality.VERY_HIGH -> sortedDescending.first()
        }
    }

    fun collectAudioPlaylists(
        masterManifest: String,
        masterManifestUrl: String? = null,
        durationMs: Long = 0L
    ): List<YouTubeHlsAudioPlaylist> {
        return masterManifest
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .mapNotNull { line -> parseAudioPlaylistLine(line, masterManifestUrl, durationMs) }
            .distinctBy(YouTubeHlsAudioPlaylist::uri)
            .toList()
    }

    private fun parseAudioPlaylistLine(
        line: String,
        masterManifestUrl: String?,
        durationMs: Long
    ): YouTubeHlsAudioPlaylist? {
        val attributes = parseAttributes(line.removePrefix("#EXT-X-MEDIA:"))
        val rawUri = audioPlaylistUri(attributes) ?: return null
        val audioItag = parseAudioItag(rawUri)
        val contentLength = parseContentLength(rawUri)
        return YouTubeHlsAudioPlaylist(
            uri = resolveRelativeUri(masterManifestUrl, rawUri),
            contentLength = contentLength,
            estimatedBitrate = estimateBitrate(audioItag, contentLength, durationMs),
            audioItag = audioItag
        )
    }

    private fun audioPlaylistUri(attributes: Map<String, String>): String? {
        if (!attributes["TYPE"].equals("AUDIO", ignoreCase = true)) return null
        return attributes["URI"]?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseAttributes(rawAttributes: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val pattern = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")
        pattern.findAll(rawAttributes).forEach { match ->
            val key = match.groupValues[1]
            val rawValue = match.groupValues[2]
            result[key] = rawValue.trim().removeSurrounding("\"")
        }
        return result
    }

    private fun resolveRelativeUri(baseUri: String?, candidate: String): String {
        if (isAbsoluteHttpUri(candidate)) return candidate
        return resolveRelativeUriAgainstBase(baseUri, candidate)
    }

    private fun resolveRelativeUriAgainstBase(baseUri: String?, candidate: String): String {
        val uri = baseUri ?: return candidate
        if (uri.isEmpty()) return candidate
        return resolveUriOrOriginal(uri, candidate)
    }

    private fun isAbsoluteHttpUri(uri: String): Boolean =
        uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)

    private fun resolveUriOrOriginal(baseUri: String, candidate: String): String =
        runCatching { URI(baseUri).resolve(candidate).toString() }.getOrElse { candidate }

    private fun parseAudioItag(url: String): Int? {
        val decoded = decodeUrlOrOriginal(url)
        val queryItag = lastDelimitedNumber(decoded, "itag")?.toIntOrNull()
        return queryItag ?: pathItag(decoded)
    }

    private fun pathItag(decoded: String): Int? =
        Regex("""/itag/(\d+)""").find(decoded)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun parseContentLength(url: String): Long? =
        lastDelimitedNumber(decodeUrlOrOriginal(url), "clen")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    private fun decodeUrlOrOriginal(url: String): String =
        runCatching { URLDecoder.decode(url, Charsets.UTF_8.name()) }.getOrElse { url }

    private fun lastDelimitedNumber(uri: String, key: String): String? =
        Regex("(?:^|[;/?&])${Regex.escape(key)}=(\\d+)")
            .findAll(uri)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)

    private fun estimateBitrate(audioItag: Int?, contentLength: Long?, durationMs: Long): Int {
        audioItagToBitrate(audioItag)?.let { return it }
        return bitrateFromContentLength(contentLength, durationMs)
    }

    private fun bitrateFromContentLength(contentLength: Long?, durationMs: Long): Int =
        if (contentLength != null && durationMs > 0L) ((contentLength * 8_000L) / durationMs).toInt() else 0

    private fun audioItagToBitrate(itag: Int?): Int? {
        return AUDIO_ITAG_BITRATES[itag]
    }

    private val AUDIO_ITAG_BITRATES = mapOf(
        139 to 48_000, 233 to 48_000, 249 to 48_000,
        140 to 128_000, 234 to 128_000, 250 to 128_000,
        141 to 256_000, 251 to 256_000
    )
}

internal fun isYouTubeGoogleVideoStream(url: String): Boolean {
    val host = googleVideoHost(url)
    if (!isYouTubeGoogleVideoHost(host)) return false
    return hasYoutubeSource(url)
}

private fun googleVideoHost(url: String): String =
    runCatching { URI(url).host }
        .getOrNull()
        ?.lowercase(Locale.US)
        .orEmpty()

private fun hasYoutubeSource(url: String): Boolean =
    extractStreamQueryParameter(url, "source")
        ?.equals("youtube", ignoreCase = true) == true
