package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BILI_SUBTITLE_LANGUAGE_AUTO
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleTrack
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.lyrics.BiliSubtitleLyricsLoader

/** CC 字幕选择面板的加载结果 */
internal sealed interface BiliCcSubtitleState {
    data object Loading : BiliCcSubtitleState

    data class Ready(
        val tracks: List<BiliSubtitleTrack>,
        val loginRequired: Boolean
    ) : BiliCcSubtitleState

    data object Failed : BiliCcSubtitleState
}

/**
 * 播放页的 CC 字幕控制面板。
 *
 * 关闭开关、轨道选择与 AI 后备写入同一组全局偏好，设置页与播放页共用同一份状态。
 * 轨道列表随视频变化，所以每次打开都重新读取。
 */
@Composable
internal fun BiliCcSubtitleContent(
    song: SongItem,
    biliClient: BiliClient,
    onDismiss: () -> Unit
) {
    val settingsRepo = AppContainer.settingsRepo
    val scope = rememberCoroutineScope()
    val selectedLanguage by settingsRepo.biliCcSubtitleLanguageFlow
        .collectAsStateWithLifecycle(initialValue = BILI_SUBTITLE_LANGUAGE_AUTO)
    val lyricsEnabled by settingsRepo.biliCcSubtitleLyricsEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val aiOnlyEnabled by settingsRepo.biliCcSubtitleAiOnlyEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val songKey = song.stableKey()
    var state by remember(songKey) { mutableStateOf<BiliCcSubtitleState>(BiliCcSubtitleState.Loading) }
    var retryToken by remember(songKey) { mutableIntStateOf(0) }

    LaunchedEffect(songKey, retryToken) {
        state = BiliCcSubtitleState.Loading
        state = try {
            val loaded = BiliSubtitleLyricsLoader.loadTracks(song, biliClient)
            BiliCcSubtitleState.Ready(
                tracks = loaded.tracks,
                loginRequired = loaded.loginRequired
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BiliCcSubtitleState.Failed
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(CoreCommonR.string.action_back)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(AppR.string.bili_cc_subtitle_manage),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
        }

        when (val current = state) {
            BiliCcSubtitleState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            BiliCcSubtitleState.Failed -> ListItem(
                headlineContent = { Text(stringResource(AppR.string.bili_cc_subtitle_load_failed)) },
                trailingContent = {
                    IconButton(onClick = { retryToken++ }) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(CoreCommonR.string.action_retry)
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            is BiliCcSubtitleState.Ready -> BiliCcSubtitleReadyContent(
                state = current,
                lyricsEnabled = lyricsEnabled,
                selectedLanguage = selectedLanguage,
                aiOnlyEnabled = aiOnlyEnabled,
                onDisable = {
                    scope.launch { settingsRepo.setBiliCcSubtitleLyricsEnabled(false) }
                },
                onSelectAuto = {
                    scope.launch {
                        settingsRepo.setBiliCcSubtitleLyricsEnabled(true)
                        settingsRepo.setBiliCcSubtitleLanguage(BILI_SUBTITLE_LANGUAGE_AUTO)
                    }
                },
                onSelectTrack = { track ->
                    scope.launch {
                        settingsRepo.setBiliCcSubtitleLyricsEnabled(true)
                        settingsRepo.setBiliCcSubtitleLanguage(track.lan)
                    }
                },
                onToggleAiOnly = {
                    scope.launch {
                        settingsRepo.setBiliCcSubtitleAiOnlyEnabled(!aiOnlyEnabled)
                    }
                }
            )
        }
    }
}

@Composable
private fun BiliCcSubtitleReadyContent(
    state: BiliCcSubtitleState.Ready,
    lyricsEnabled: Boolean,
    selectedLanguage: String,
    aiOnlyEnabled: Boolean,
    onDisable: () -> Unit,
    onSelectAuto: () -> Unit,
    onSelectTrack: (BiliSubtitleTrack) -> Unit,
    onToggleAiOnly: () -> Unit
) {
    ListItem(
        headlineContent = { Text(stringResource(AppR.string.bili_cc_subtitle_off)) },
        supportingContent = { Text(stringResource(AppR.string.bili_cc_subtitle_off_desc)) },
        trailingContent = {
            RadioButton(selected = !lyricsEnabled, onClick = onDisable)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )

    if (state.loginRequired) {
        ListItem(
            headlineContent = {
                Text(stringResource(AppR.string.bili_cc_subtitle_login_required))
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        return
    }

    ListItem(
        headlineContent = { Text(stringResource(AppR.string.bili_cc_subtitle_auto)) },
        supportingContent = { Text(stringResource(AppR.string.bili_cc_subtitle_auto_desc)) },
        trailingContent = {
            RadioButton(
                selected = lyricsEnabled && selectedLanguage == BILI_SUBTITLE_LANGUAGE_AUTO,
                onClick = onSelectAuto
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )

    if (state.tracks.isNotEmpty()) {
        state.tracks.forEach { track ->
            ListItem(
                headlineContent = { Text(track.lanDoc.ifBlank { track.lan }) },
                supportingContent = {
                    Text(
                        if (track.isAiGenerated) {
                            stringResource(AppR.string.bili_cc_subtitle_ai_badge)
                        } else {
                            track.lan
                        }
                    )
                },
                trailingContent = {
                    RadioButton(
                        selected = lyricsEnabled &&
                            selectedLanguage.equals(track.lan, ignoreCase = true),
                        onClick = { onSelectTrack(track) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    } else {
        ListItem(
            headlineContent = { Text(stringResource(AppR.string.bili_cc_subtitle_empty)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

    ListItem(
        headlineContent = { Text(stringResource(AppR.string.bili_cc_subtitle_ai_fallback)) },
        supportingContent = {
            Text(stringResource(CoreCommonR.string.settings_bili_cc_subtitle_ai_only_desc))
        },
        trailingContent = {
            Switch(
                checked = aiOnlyEnabled,
                enabled = lyricsEnabled,
                onCheckedChange = { onToggleAiOnly() }
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
