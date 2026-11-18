package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliBvidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliCidOrNull
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

internal fun SongItem.biliPartsPickerBvidOrNull(): String? =
    if (biliCidOrNull() == null && !isLocalSong()) biliBvidOrNull() else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalPlaylistBiliPartsPicker(
    bvid: String,
    title: String,
    loadVideo: suspend (String) -> VideoBasicInfo,
    onDismiss: () -> Unit,
    onPlayOriginal: () -> Unit,
    onPlayPart: (VideoBasicInfo, Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var video by remember(bvid) { mutableStateOf<VideoBasicInfo?>(null) }
    var failed by remember(bvid) { mutableStateOf(false) }
    var attempt by remember(bvid) { mutableIntStateOf(0) }
    val dismiss by rememberUpdatedState(onDismiss)
    val playOriginal by rememberUpdatedState(onPlayOriginal)

    LaunchedEffect(bvid, attempt) {
        val info = try {
            loadVideo(bvid)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w("LocalPlaylistBiliParts", "get video parts failed: bvid=$bvid", error)
            null
        }
        currentCoroutineContext().ensureActive()
        when {
            // 接口业务失败时可能返回一个没有 aid 的空对象
            info == null || info.aid <= 0L -> failed = true
            info.pages.size <= 1 -> {
                dismiss()
                playOriginal()
            }
            else -> video = info
        }
    }

    DensityScaledModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false
    ) {
        Column(Modifier.bottomSheetScrollGuard().padding(bottom = 12.dp)) {
            val info = video
            BiliVideoPartsTitle(info?.title?.ifBlank { title } ?: title)
            HorizontalDivider()
            if (info != null) {
                BiliVideoPartsList(
                    pages = info.pages,
                    onPlayPart = { index ->
                        onDismiss()
                        onPlayPart(info, index)
                    }
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (failed) {
                        Text(stringResource(AppR.string.bili_get_parts_failed))
                        HapticTextButton(onClick = {
                            failed = false
                            attempt++
                        }) {
                            Text(stringResource(CoreCommonR.string.action_retry))
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
internal fun BiliVideoPartsTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun BiliVideoPartsList(
    pages: List<VideoPage>,
    onPlayPart: (Int) -> Unit,
    selectedParts: Set<Int>? = null,
    onTogglePart: (Int) -> Unit = {},
    onLongPressPart: ((Int) -> Unit)? = null
) {
    LazyColumn {
        itemsIndexed(pages, key = { _, page -> page.page }) { index, page ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            if (selectedParts == null) onPlayPart(index) else onTogglePart(page.page)
                        },
                        onLongClick = onLongPressPart?.let { onLongPress -> { onLongPress(page.page) } }
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedParts != null) {
                    Checkbox(
                        checked = page.page in selectedParts,
                        onCheckedChange = { onTogglePart(page.page) }
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Text(
                    text = "P${page.page}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(48.dp)
                )
                Text(
                    text = page.part,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
