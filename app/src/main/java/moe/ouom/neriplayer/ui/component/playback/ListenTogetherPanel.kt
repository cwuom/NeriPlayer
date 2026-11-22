package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.server.isServerSong
import moe.ouom.neriplayer.ui.component.common.CleanSegmentedTabs
import moe.ouom.neriplayer.ui.component.playback.lan.LanRoomPanel
import moe.ouom.neriplayer.ui.screen.debug.ListenTogetherRoomPanel
import moe.ouom.neriplayer.ui.theme.NeriMotion

internal enum class ListenTogetherTab { Cloud, Lan }

@Composable
internal fun ListenTogetherPageTransition(
    showDetails: Boolean,
    pageLayout: @Composable (@Composable () -> Unit) -> Unit = { it() },
    content: @Composable (Boolean) -> Unit
) {
    AnimatedContent(
        targetState = showDetails,
        modifier = Modifier.fillMaxWidth(),
        transitionSpec = {
            (EnterTransition.None togetherWith ExitTransition.None).using(
                SizeTransform { _, _ -> tween(320, easing = NeriMotion.SmoothInOut) }
            )
        },
        label = "listen_together_page"
    ) { details ->
        val semantics = if (details == showDetails) Modifier else Modifier.clearAndSetSemantics {}
        Box(semantics) {
            pageLayout {
                Box(Modifier.animateEnterExit(enter = fadeIn(tween(240)), exit = fadeOut(tween(160)))) {
                    content(details)
                }
            }
        }
    }
}

@Composable
internal fun ListenTogetherPanel(
    modifier: Modifier = Modifier,
    initialTab: ListenTogetherTab = ListenTogetherTab.Cloud,
    onDismiss: (() -> Unit)? = null
) {
    val lanManager = remember { AppContainer.lanListenTogether }
    val cloudManager = remember { AppContainer.listenTogetherSessionManager }
    val lanState by lanManager.state.collectAsStateWithLifecycle()
    val cloudState by cloudManager.sessionState.collectAsStateWithLifecycle()
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    ListenTogetherTabs(
        lanActive = lanState.active,
        cloudRoomId = cloudState.roomId,
        initialTab = initialTab,
        modifier = modifier,
        cloudContent = {
            if (currentSong?.isServerSong() == true && cloudState.roomId.isNullOrBlank()) {
                Text(
                    text = stringResource(CoreR.string.server_listen_together_unavailable),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ListenTogetherRoomPanel(Modifier.fillMaxWidth())
            }
        },
        lanContent = { LanRoomPanel(onDismiss = onDismiss) }
    )
}

@Composable
internal fun ListenTogetherTabs(
    lanActive: Boolean,
    cloudRoomId: String?,
    modifier: Modifier = Modifier,
    initialTab: ListenTogetherTab = ListenTogetherTab.Cloud,
    requestedTab: ListenTogetherTab? = null,
    requestId: Int = 0,
    cloudContent: @Composable () -> Unit,
    lanContent: @Composable () -> Unit
) {
    var selectedTab by rememberSaveable {
        mutableStateOf(
            when {
                lanActive -> ListenTogetherTab.Lan
                !cloudRoomId.isNullOrBlank() -> ListenTogetherTab.Cloud
                else -> initialTab
            }
        )
    }
    val contentState = rememberSaveableStateHolder()
    LaunchedEffect(lanActive, cloudRoomId) {
        when {
            lanActive -> selectedTab = ListenTogetherTab.Lan
            !cloudRoomId.isNullOrBlank() -> selectedTab = ListenTogetherTab.Cloud
        }
    }
    LaunchedEffect(requestedTab, requestId) {
        requestedTab?.let { selectedTab = it }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CleanSegmentedTabs(
            labels = listOf(
                stringResource(CoreR.string.listen_together_tab_cloud),
                stringResource(CoreR.string.listen_together_tab_lan)
            ),
            selectedIndex = selectedTab.ordinal,
            onSelected = { selectedTab = ListenTogetherTab.entries[it] },
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        contentState.SaveableStateProvider(selectedTab) {
            when (selectedTab) {
                ListenTogetherTab.Cloud -> cloudContent()
                ListenTogetherTab.Lan -> lanContent()
            }
        }
    }
}
