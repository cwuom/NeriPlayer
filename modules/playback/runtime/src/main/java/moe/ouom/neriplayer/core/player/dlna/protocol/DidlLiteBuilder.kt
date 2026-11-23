package moe.ouom.neriplayer.core.player.dlna.protocol

import moe.ouom.neriplayer.data.model.playback.dlna.DlnaPlaybackSnapshot
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaStreamMode
import java.util.Locale

/**
 * Generates UPnP DIDL-Lite metadata XML and evaluates ConnectionManager protocolInfo.
 */
object DidlLiteBuilder {

    private const val DLNA_FLAGS_STREAMING_FILE = "01700000000000000000000000000000"

    fun escapeXml(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    sb.append(ch)
                    sb.append(text[i + 1])
                    i += 2
                } else {
                    i += 1
                }
                continue
            }
            if (Character.isLowSurrogate(ch) || ch == '\uFFFE' || ch == '\uFFFF') {
                i += 1
                continue
            }
            val code = ch.code
            if (code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') {
                i += 1
                continue
            }
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
            i += 1
        }
        return sb.toString()
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return ""
        val totalSeconds = durationMs / 1000L
        val millis = durationMs % 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds / 60L) % 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    fun buildDidlLite(
        snapshot: DlnaPlaybackSnapshot,
        streamUrl: String,
        coverUrl: String?
    ): String {
        val sb = StringBuilder(640)
        sb.append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
        sb.append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
        sb.append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
        sb.append("""<item id="${escapeXml("${snapshot.songKey}#${snapshot.generationId}")}" """)
        sb.append("""parentID="0" restricted="1">""")
        sb.append("<dc:title>${escapeXml(snapshot.title.ifBlank { "Unknown Title" })}</dc:title>")
        sb.append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        if (snapshot.artist.isNotBlank()) {
            val escapedArtist = escapeXml(snapshot.artist)
            sb.append("<dc:creator>$escapedArtist</dc:creator>")
            sb.append("""<upnp:artist role="Performer">$escapedArtist</upnp:artist>""")
        }
        if (snapshot.album.isNotBlank()) {
            sb.append("<upnp:album>${escapeXml(snapshot.album)}</upnp:album>")
        }
        if (snapshot.trackNumber > 0) {
            sb.append("<upnp:originalTrackNumber>${snapshot.trackNumber}</upnp:originalTrackNumber>")
        }
        if (!coverUrl.isNullOrBlank()) {
            sb.append("<upnp:albumArtURI>${escapeXml(coverUrl)}</upnp:albumArtURI>")
        }
        sb.append("""<res protocolInfo="${escapeXml(snapshot.protocolInfo)}"""")
        val durationText = formatDuration(snapshot.durationMs)
        if (durationText.isNotEmpty()) {
            sb.append(""" duration="${escapeXml(durationText)}"""")
        }
        if (snapshot.totalBytes > 0L) {
            sb.append(""" size="${snapshot.totalBytes}"""")
        }
        if (snapshot.sampleRateHz > 0) {
            sb.append(""" sampleFrequency="${snapshot.sampleRateHz}"""")
        }
        if (snapshot.bitDepth > 0) {
            sb.append(""" bitsPerSample="${snapshot.bitDepth}"""")
        }
        if (snapshot.channelCount > 0) {
            sb.append(""" nrAudioChannels="${snapshot.channelCount}"""")
        }
        sb.append(">")
        sb.append(escapeXml(streamUrl))
        sb.append("</res></item></DIDL-Lite>")
        return sb.toString()
    }
}

object DlnaProtocolInfo {

    private const val FLAGS = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    private val WAV_MIME_ALIASES = listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
    private val FLAC_MIME_ALIASES = listOf("audio/x-flac", "audio/flac")
    private val MP3_MIME_ALIASES = listOf("audio/mpeg", "audio/mp3")
    private val MP4_MIME_ALIASES = listOf("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/mp4a-latm")
    private val DEFAULT_FALLBACK_DLNA_MIMES = (
        WAV_MIME_ALIASES + FLAC_MIME_ALIASES + MP3_MIME_ALIASES + MP4_MIME_ALIASES + listOf("audio/aac")
        ).toSet()

    fun preferredWavMime(sinkProtocolInfo: String = ""): String {
        return selectPreferredMime(
            sinkProtocolInfo = sinkProtocolInfo,
            preferred = "audio/wav",
            alternatives = WAV_MIME_ALIASES
        )
    }

    fun contentTypeFor(
        mode: DlnaStreamMode,
        mimeOverride: String? = null,
        sinkProtocolInfo: String = ""
    ): String {
        return when (mode) {
            DlnaStreamMode.LOSSLESS_WAV,
            DlnaStreamMode.LOSSLESS_WAV_ID3 -> preferredWavMime(sinkProtocolInfo)
            DlnaStreamMode.ORIGINAL_FLAC,
            DlnaStreamMode.ADAPTIVE_DIRECT -> {
                val clean = mimeOverride?.substringBefore(';')?.trim()?.lowercase(Locale.US)
                when (clean) {
                    in WAV_MIME_ALIASES -> preferredWavMime(sinkProtocolInfo)
                    "audio/flac", "audio/x-flac" -> selectPreferredMime(
                        sinkProtocolInfo = sinkProtocolInfo,
                        preferred = "audio/x-flac",
                        alternatives = FLAC_MIME_ALIASES
                    )
                    "audio/mp3", "audio/mpeg" -> selectPreferredMime(
                        sinkProtocolInfo = sinkProtocolInfo,
                        preferred = "audio/mpeg",
                        alternatives = MP3_MIME_ALIASES
                    )
                    "audio/m4a", "audio/mp4", "audio/x-m4a", "audio/mp4a-latm" -> selectPreferredMime(
                        sinkProtocolInfo = sinkProtocolInfo,
                        preferred = "audio/mp4",
                        alternatives = MP4_MIME_ALIASES
                    )
                    "audio/aac" -> "audio/aac"
                    "audio/ogg" -> "audio/ogg"
                    "audio/webm" -> "audio/webm"
                    null, "" -> if (mode == DlnaStreamMode.ORIGINAL_FLAC) {
                        selectPreferredMime(sinkProtocolInfo, "audio/x-flac", FLAC_MIME_ALIASES)
                    } else {
                        "application/octet-stream"
                    }
                    else -> clean
                }
            }
        }
    }

    fun protocolInfoFor(
        mode: DlnaStreamMode,
        sinkProtocolInfo: String = "",
        mimeOverride: String? = null
    ): String {
        val contentType = contentTypeFor(
            mode = mode,
            mimeOverride = mimeOverride,
            sinkProtocolInfo = sinkProtocolInfo
        )
        return "http-get:*:$contentType:$FLAGS"
    }

    fun contentFeaturesFromProtocolInfo(protocolInfo: String): String {
        val fourthField = protocolInfo.split(':').getOrNull(3)?.trim().orEmpty()
        if (fourthField.isEmpty() || fourthField == "*") return ""
        return fourthField.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("DLNA.ORG_PN=", ignoreCase = true) }
            .joinToString(";")
    }

    fun supportsFlac(sinkProtocolInfo: String, sampleRateHz: Int? = null): Boolean {
        if (sinkProtocolInfo.isBlank()) return true
        val mimeEntries = parseProtocolEntries(sinkProtocolInfo)
        if (mimeEntries.isEmpty()) return true
        var sawExplicitFlac = false
        for ((mime, params) in mimeEntries) {
            if (mime in FLAC_MIME_ALIASES) {
                sawExplicitFlac = true
                val declaredRate = extractRateParam(params)
                if (declaredRate != null && sampleRateHz != null && sampleRateHz > 0 && declaredRate != sampleRateHz) {
                    continue
                }
                return true
            }
        }
        if (sawExplicitFlac) return false
        return mimeEntries.any { (mime, _) -> isWildcardAudioMime(mime) }
    }

    fun supportsWav(sinkProtocolInfo: String): Boolean {
        if (sinkProtocolInfo.isBlank()) return true
        val mimeEntries = parseProtocolEntries(sinkProtocolInfo)
        if (mimeEntries.isEmpty()) return true
        return mimeEntries.any { (mime, _) ->
            mime in WAV_MIME_ALIASES || isWildcardAudioMime(mime)
        }
    }

    fun supportsDeliveryMime(sinkProtocolInfo: String, mimeType: String?): Boolean {
        val clean = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
        if (clean.isEmpty()) return false
        if (sinkProtocolInfo.isBlank()) {
            return clean in DEFAULT_FALLBACK_DLNA_MIMES
        }
        val mimeEntries = parseProtocolEntries(sinkProtocolInfo)
        if (mimeEntries.isEmpty()) {
            return clean in DEFAULT_FALLBACK_DLNA_MIMES
        }
        if (mimeEntries.any { (mime, _) -> isWildcardAudioMime(mime) }) {
            return true
        }
        val candidates = when (clean) {
            in WAV_MIME_ALIASES -> WAV_MIME_ALIASES
            in FLAC_MIME_ALIASES -> FLAC_MIME_ALIASES
            in MP3_MIME_ALIASES -> MP3_MIME_ALIASES
            in MP4_MIME_ALIASES -> MP4_MIME_ALIASES
            "audio/ogg", "application/ogg" -> listOf("audio/ogg", "application/ogg")
            else -> listOf(clean)
        }.toSet()
        return mimeEntries.any { (mime, _) -> mime in candidates }
    }

    private fun isWildcardAudioMime(mime: String): Boolean {
        return mime == "*" || mime == "*/*" || mime == "audio/*"
    }

    private fun selectPreferredMime(
        sinkProtocolInfo: String,
        preferred: String,
        alternatives: List<String>
    ): String {
        if (sinkProtocolInfo.isBlank()) return preferred
        val entries = parseProtocolEntries(sinkProtocolInfo).map { it.first }.toSet()
        if (preferred in entries) return preferred
        return alternatives.firstOrNull { it in entries } ?: preferred
    }

    private fun parseProtocolEntries(sinkProtocolInfo: String): List<Pair<String, String>> {
        return sinkProtocolInfo.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val parts = entry.split(':')
                if (parts.size < 3) return@mapNotNull null
                val transport = parts[0].trim().lowercase(Locale.US)
                if (transport != "*" && transport != "http-get") return@mapNotNull null
                val third = parts[2].trim()
                val semi = third.indexOf(';')
                val mime = (if (semi >= 0) third.substring(0, semi) else third).trim().lowercase(Locale.US)
                val params = if (semi >= 0) third.substring(semi + 1).trim().lowercase(Locale.US) else ""
                if (mime.isEmpty()) null else mime to params
            }
    }

    private fun extractRateParam(params: String): Int? {
        val idx = params.indexOf("rate=")
        if (idx < 0) return null
        val rest = params.substring(idx + 5)
        val digits = rest.takeWhile { it.isDigit() }
        return digits.toIntOrNull()?.takeIf { it > 0 }
    }
}
