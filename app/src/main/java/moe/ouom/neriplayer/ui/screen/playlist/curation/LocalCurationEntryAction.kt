package moe.ouom.neriplayer.ui.screen.playlist.curation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.R as AppR

/** 顶栏入口；后台会话进行中时叠加进度环，提示用户可以回来查看 */
@Composable
internal fun LocalCurationEntryAction(
    onClick: () -> Unit,
    coordinator: LocalCurationCoordinator = LocalCurationRuntime.coordinator
) {
    val session by coordinator.session.collectAsStateWithLifecycle()
    val running = session?.stage == LocalCurationStage.ANALYZING ||
        session?.stage == LocalCurationStage.APPLYING
    HapticIconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = stringResource(AppR.string.local_curation_title)
            )
            AnimatedVisibility(
                visible = running,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        }
    }
}
