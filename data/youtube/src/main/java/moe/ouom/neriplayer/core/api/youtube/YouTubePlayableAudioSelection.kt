package moe.ouom.neriplayer.core.api.youtube

import androidx.media3.common.MimeTypes
import java.util.Locale

private val PLAYABLE_M4A_MIME_TYPES = setOf("audio/mp4", "audio/m4a", "audio/aac")
private val DIRECT_CLIENTS_WITHOUT_PO_TOKEN = setOf(
    YOUTUBE_PLAYER_VISIONOS_CLIENT_NAME,
    YOUTUBE_PLAYER_ANDROID_VR_CLIENT_NAME
)
private val DIRECT_CLIENTS_REQUIRING_PO_TOKEN = setOf(
    YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME,
    YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_NAME,
    YOUTUBE_PLAYER_TV_CLIENT_NAME
)
private val PLAYABLE_MIME_SCORES = mapOf(
    MimeTypes.APPLICATION_M3U8.lowercase(Locale.US) to 3,
    "audio/mp4" to 2,
    "audio/m4a" to 2,
    "audio/aac" to 2,
    "audio/webm" to 1
)
private val MINIMUM_BITRATE_KBPS = mapOf(
    YouTubeMusicPlaybackQuality.MEDIUM to 96,
    YouTubeMusicPlaybackQuality.HIGH to 128,
    YouTubeMusicPlaybackQuality.VERY_HIGH to 160
)

enum class YouTubePlayableStreamType {
    DIRECT,
    HLS
}

data class YouTubePlayableAudio(
    val url: String,
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val streamType: YouTubePlayableStreamType = YouTubePlayableStreamType.DIRECT,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null
)

internal fun isTrustedYouTubeDirectUrlForStrictRecovery(url: String): Boolean {
    if (!isYouTubeGoogleVideoStream(url)) return true
    val clientName = normalizedStreamClient(url)
    if (clientName in DIRECT_CLIENTS_WITHOUT_PO_TOKEN) return true
    return clientName in DIRECT_CLIENTS_REQUIRING_PO_TOKEN &&
        !extractStreamQueryParameter(url, "pot").isNullOrBlank()
}

private fun normalizedStreamClient(url: String): String =
    extractStreamQueryParameter(url, "c")?.trim()?.uppercase(Locale.US).orEmpty()

internal fun isPlayableM4aContainer(mimeType: String?): Boolean =
    mimeType?.lowercase(Locale.US) in PLAYABLE_M4A_MIME_TYPES

internal fun playableAudioMimePreferenceScore(mimeType: String?): Int =
    PLAYABLE_MIME_SCORES[mimeType?.lowercase(Locale.US)] ?: 0

internal fun satisfiesYouTubePlaybackQuality(
    playableAudio: YouTubePlayableAudio,
    preferredQualityKey: String?
): Boolean {
    val minimumBitrateKbps = MINIMUM_BITRATE_KBPS[
        YouTubeMusicPlaybackQuality.fromSetting(preferredQualityKey)
    ] ?: return true
    return (playableAudio.bitrateKbps ?: 0) >= minimumBitrateKbps
}

internal object YouTubePlayableAudioSelection {
    private data class ClientScores(val direct: Int, val hls: Int)

    private val clientScores = mapOf(
        YOUTUBE_PLAYER_VISIONOS_CLIENT_NAME to ClientScores(40, 25),
        YOUTUBE_PLAYER_ANDROID_VR_CLIENT_NAME to ClientScores(35, 22),
        YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME to ClientScores(30, 20),
        YOUTUBE_PLAYER_TV_CLIENT_NAME to ClientScores(20, 5),
        YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_NAME to ClientScores(15, 0),
        YOUTUBE_PLAYER_ANDROID_MUSIC_CLIENT_NAME to ClientScores(10, 0)
    )
    private val anonymousScores = ClientScores(0, 0)
    private val unknownScores = ClientScores(5, 0)
    private val immediateDirectClients = setOf(
        YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME,
        YOUTUBE_PLAYER_TV_CLIENT_NAME,
        YOUTUBE_PLAYER_VISIONOS_CLIENT_NAME,
        YOUTUBE_PLAYER_ANDROID_VR_CLIENT_NAME,
        YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_NAME,
        YOUTUBE_PLAYER_ANDROID_MUSIC_CLIENT_NAME
    )

    fun selectPreferred(
        current: YouTubePlayableAudio?,
        incoming: YouTubePlayableAudio?,
        currentClientName: String? = null,
        incomingClientName: String? = null,
        preferM4a: Boolean = false,
        preferredQualityKey: String? = null
    ): YouTubePlayableAudio? {
        if (incoming == null) return current
        if (current == null) return incoming
        if (preferM4a && isPlayableM4aContainer(incoming.mimeType) != isPlayableM4aContainer(current.mimeType)) {
            return if (isPlayableM4aContainer(incoming.mimeType)) incoming else current
        }
        val incomingMeetsQuality = satisfiesYouTubePlaybackQuality(incoming, preferredQualityKey)
        val currentMeetsQuality = satisfiesYouTubePlaybackQuality(current, preferredQualityKey)
        if (incomingMeetsQuality != currentMeetsQuality) {
            return if (incomingMeetsQuality) incoming else current
        }
        return chooseEquivalentQuality(current, incoming, currentClientName, incomingClientName)
    }

    private fun chooseEquivalentQuality(
        current: YouTubePlayableAudio,
        incoming: YouTubePlayableAudio,
        currentClientName: String?,
        incomingClientName: String?
    ): YouTubePlayableAudio {
        if (incoming.streamType != current.streamType) {
            return if (incoming.streamType == YouTubePlayableStreamType.DIRECT) incoming else current
        }
        val qualityComparison = compareQuality(incoming, current)
        if (qualityComparison != 0) return if (qualityComparison > 0) incoming else current
        if (currentClientName == incomingClientName) return current
        val incomingScore = clientPreferenceScore(incomingClientName, incoming.streamType)
        val currentScore = clientPreferenceScore(currentClientName, current.streamType)
        return if (incomingScore > currentScore) incoming else current
    }

    private fun clientPreferenceScore(clientName: String?, streamType: YouTubePlayableStreamType): Int {
        val scores = clientScores[normalizedClientName(clientName)]
            ?: if (clientName.isNullOrBlank()) anonymousScores else unknownScores
        return if (streamType == YouTubePlayableStreamType.DIRECT) scores.direct else scores.hls
    }

    private fun normalizedClientName(clientName: String?): String? =
        if (clientName?.startsWith(YOUTUBE_PLAYER_TV_CLIENT_NAME, ignoreCase = true) == true) {
            YOUTUBE_PLAYER_TV_CLIENT_NAME
        } else {
            clientName
        }

    fun shouldReturnImmediately(
        profile: YouTubePlayerClientProfile,
        playableAudio: YouTubePlayableAudio,
        acceptedFromCurrentProfile: Boolean,
        preferredQualityKey: String,
        preferM4a: Boolean
    ): Boolean {
        if (!acceptedFromCurrentProfile || playableAudio.streamType != YouTubePlayableStreamType.DIRECT) return false
        if (!satisfiesYouTubePlaybackQuality(playableAudio, preferredQualityKey) &&
            !(preferM4a && isPlayableM4aContainer(playableAudio.mimeType))) return false
        return profile.clientName in immediateDirectClients
    }

    private fun compareQuality(incoming: YouTubePlayableAudio, current: YouTubePlayableAudio): Int {
        val comparisons = listOf(
            compareNullableInt(incoming.bitrateKbps, current.bitrateKbps),
            compareNullableInt(incoming.sampleRateHz, current.sampleRateHz),
            playableAudioMimePreferenceScore(incoming.mimeType)
                .compareTo(playableAudioMimePreferenceScore(current.mimeType)),
            compareNullableLong(incoming.contentLength, current.contentLength),
            incoming.durationMs.compareTo(current.durationMs)
        )
        return comparisons.firstOrNull { it != 0 } ?: 0
    }

    private fun compareNullableInt(incoming: Int?, current: Int?): Int =
        incoming.orZero().compareTo(current.orZero())

    private fun compareNullableLong(incoming: Long?, current: Long?): Int =
        incoming.orZero().compareTo(current.orZero())

    private fun Int?.orZero(): Int = this ?: 0

    private fun Long?.orZero(): Long = this ?: 0L
}
