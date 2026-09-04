package moe.ouom.neriplayer.data.settings

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal const val DEFAULT_DOWNLOAD_NETEASE_AUDIO_QUALITY = "exhigh"
internal const val DEFAULT_DOWNLOAD_YOUTUBE_AUDIO_QUALITY = "high"
internal const val DEFAULT_DOWNLOAD_BILI_AUDIO_QUALITY = "high"

private val DOWNLOAD_NETEASE_AUDIO_QUALITIES = setOf(
    "standard",
    "higher",
    "exhigh",
    "lossless",
    "hires",
    "jyeffect",
    "sky",
    "jymaster"
)

private val DOWNLOAD_YOUTUBE_AUDIO_QUALITIES = setOf(
    "low",
    "medium",
    "high",
    "very_high"
)

private val DOWNLOAD_BILI_AUDIO_QUALITIES = setOf(
    "low",
    "medium",
    "high",
    "lossless",
    "hires",
    "dolby"
)

data class DownloadAudioQualitySelection(
    val neteaseQuality: String,
    val youtubeQuality: String,
    val biliQuality: String
) {
    companion object {
        fun normalized(
            neteaseQuality: String?,
            youtubeQuality: String?,
            biliQuality: String?
        ): DownloadAudioQualitySelection {
            return DownloadAudioQualitySelection(
                neteaseQuality = normalizeDownloadNeteaseAudioQuality(neteaseQuality),
                youtubeQuality = normalizeDownloadYouTubeAudioQuality(youtubeQuality),
                biliQuality = normalizeDownloadBiliAudioQuality(biliQuality)
            )
        }
    }
}

internal fun normalizeDownloadNeteaseAudioQuality(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it in DOWNLOAD_NETEASE_AUDIO_QUALITIES }
        ?: DEFAULT_DOWNLOAD_NETEASE_AUDIO_QUALITY
}

internal fun normalizeDownloadYouTubeAudioQuality(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it in DOWNLOAD_YOUTUBE_AUDIO_QUALITIES }
        ?: DEFAULT_DOWNLOAD_YOUTUBE_AUDIO_QUALITY
}

internal fun normalizeDownloadBiliAudioQuality(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it in DOWNLOAD_BILI_AUDIO_QUALITIES }
        ?: DEFAULT_DOWNLOAD_BILI_AUDIO_QUALITY
}

internal fun resolveDownloadAudioQualitySelection(
    followsPlaybackQuality: Boolean,
    playbackNeteaseQuality: String?,
    playbackYouTubeQuality: String?,
    playbackBiliQuality: String?,
    downloadNeteaseQuality: String?,
    downloadYouTubeQuality: String?,
    downloadBiliQuality: String?
): DownloadAudioQualitySelection {
    return if (followsPlaybackQuality) {
        DownloadAudioQualitySelection.normalized(
            neteaseQuality = playbackNeteaseQuality,
            youtubeQuality = playbackYouTubeQuality,
            biliQuality = playbackBiliQuality
        )
    } else {
        DownloadAudioQualitySelection.normalized(
            neteaseQuality = downloadNeteaseQuality,
            youtubeQuality = downloadYouTubeQuality,
            biliQuality = downloadBiliQuality
        )
    }
}

internal suspend fun resolveDownloadAudioQualitySelection(
    context: Context
): DownloadAudioQualitySelection {
    return try {
        val preferences = context.applicationContext.dataStore.data.first()
        val followsPlaybackQuality = preferences.valueOf(
            AutoSettingsSchema.download.downloadFollowPlaybackAudioQuality
        )
        resolveDownloadAudioQualitySelection(
            followsPlaybackQuality = followsPlaybackQuality,
            playbackNeteaseQuality = preferences[SettingsKeys.AUDIO_QUALITY],
            playbackYouTubeQuality = preferences[SettingsKeys.YOUTUBE_AUDIO_QUALITY],
            playbackBiliQuality = preferences[SettingsKeys.BILI_AUDIO_QUALITY],
            downloadNeteaseQuality = preferences.valueOf(
                AutoSettingsSchema.download.downloadNeteaseAudioQuality
            ),
            downloadYouTubeQuality = preferences.valueOf(
                AutoSettingsSchema.download.downloadYouTubeAudioQuality
            ),
            downloadBiliQuality = preferences.valueOf(
                AutoSettingsSchema.download.downloadBiliAudioQuality
            )
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        DownloadAudioQualitySelection.normalized(
            neteaseQuality = DEFAULT_DOWNLOAD_NETEASE_AUDIO_QUALITY,
            youtubeQuality = DEFAULT_DOWNLOAD_YOUTUBE_AUDIO_QUALITY,
            biliQuality = DEFAULT_DOWNLOAD_BILI_AUDIO_QUALITY
        )
    }
}

internal fun readDownloadFollowPlaybackAudioQualityStartupValue(context: Context): Boolean {
    return readBootstrapSettingsSnapshotSync(context)
        .downloadFollowPlaybackAudioQuality
}

internal suspend fun setDownloadFollowPlaybackAudioQuality(
    context: Context,
    followsPlaybackQuality: Boolean
) {
    val appContext = context.applicationContext
    appContext.setAutoSetting(
        AutoSettingsSchema.download.downloadFollowPlaybackAudioQuality,
        followsPlaybackQuality
    )
    updateDownloadFollowPlaybackAudioQualityStartupValue(appContext, followsPlaybackQuality)
}

internal fun updateDownloadFollowPlaybackAudioQualityStartupValue(
    context: Context,
    followsPlaybackQuality: Boolean
) {
    updateBootstrapDownloadFollowPlaybackAudioQuality(
        context = context,
        followsPlaybackQuality = followsPlaybackQuality
    )
}
