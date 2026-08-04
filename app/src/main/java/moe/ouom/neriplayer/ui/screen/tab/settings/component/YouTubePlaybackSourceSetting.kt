package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.YouTubePlaybackSourcePreference
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun YouTubePlaybackSourceSetting(
    repository: SettingsRepository,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val source by repository.youtubePlaybackSourceFlow.collectAsState(
        initial = YouTubePlaybackSourcePreference.Automatic
    )
    var showDialog by remember { mutableStateOf(false) }

    AutoSettingSpecListItem(
        setting = AutoSettingsSchema.playback.youtubePlaybackSource,
        supportingContent = { Text(youTubePlaybackSourceLabel(source)) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = { showDialog = true }
    )

    if (!showDialog) return

    MiuixSettingsDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(stringResource(R.string.settings_youtube_playback_source)) },
        text = {
            androidx.compose.foundation.layout.Column {
                YouTubePlaybackSourcePreference.entries.forEach { option ->
                    MiuixSettingsChoiceRow(
                        title = youTubePlaybackSourceLabel(option),
                        subtitle = youTubePlaybackSourceDescription(option),
                        selected = option == source,
                        onClick = {
                            scope.launch { repository.setYouTubePlaybackSource(option) }
                            showDialog = false
                        }
                    )
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = { showDialog = false },
                text = { Text(stringResource(R.string.action_close)) }
            )
        }
    )
}

@Composable
private fun youTubePlaybackSourceLabel(
    source: YouTubePlaybackSourcePreference
): String = stringResource(
    when (source) {
        YouTubePlaybackSourcePreference.Automatic ->
            R.string.settings_youtube_playback_source_automatic
        YouTubePlaybackSourcePreference.VisionOs ->
            R.string.settings_youtube_playback_source_visionos
        YouTubePlaybackSourcePreference.AndroidVr ->
            R.string.settings_youtube_playback_source_android_vr
        YouTubePlaybackSourcePreference.WebRemix ->
            R.string.settings_youtube_playback_source_web_remix
        YouTubePlaybackSourcePreference.TvHtml5 ->
            R.string.settings_youtube_playback_source_tv_html5
        YouTubePlaybackSourcePreference.WebCreator ->
            R.string.settings_youtube_playback_source_web_creator
    }
)

@Composable
private fun youTubePlaybackSourceDescription(
    source: YouTubePlaybackSourcePreference
): String = stringResource(
    when (source) {
        YouTubePlaybackSourcePreference.Automatic ->
            R.string.settings_youtube_playback_source_automatic_desc
        YouTubePlaybackSourcePreference.VisionOs ->
            R.string.settings_youtube_playback_source_visionos_desc
        YouTubePlaybackSourcePreference.AndroidVr ->
            R.string.settings_youtube_playback_source_android_vr_desc
        YouTubePlaybackSourcePreference.WebRemix ->
            R.string.settings_youtube_playback_source_web_remix_desc
        YouTubePlaybackSourcePreference.TvHtml5 ->
            R.string.settings_youtube_playback_source_tv_html5_desc
        YouTubePlaybackSourcePreference.WebCreator ->
            R.string.settings_youtube_playback_source_web_creator_desc
    }
)
