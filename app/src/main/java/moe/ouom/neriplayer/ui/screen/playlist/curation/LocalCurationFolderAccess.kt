package moe.ouom.neriplayer.ui.screen.playlist.curation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation
import moe.ouom.neriplayer.data.local.media.curation.FolderArtworkLocator
import moe.ouom.neriplayer.data.local.media.curation.StorageDocumentPath
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

internal data class LocalCurationFolderAccess(
    val pendingSongs: Int = 0,
    val suggestedFolder: StorageDocumentPath? = null
)

internal fun resolveLocalCurationFolderAccess(pending: List<StorageDocumentPath?>): LocalCurationFolderAccess {
    val directories = pending.filterNotNull()
    return LocalCurationFolderAccess(
        pendingSongs = directories.size,
        suggestedFolder = FolderArtworkLocator.commonAncestor(directories.distinct())
    )
}

/**
 * 分区存储下读取同目录 cover.jpg 需要用户授权一次文件夹；授权完成后立即重新统计，
 * 选择器默认定位到这批歌曲的公共上级目录
 */
@Composable
internal fun LocalCurationFolderAccessCard(songs: List<SongItem>) {
    val context = LocalContext.current.applicationContext
    var refreshKey by remember { mutableIntStateOf(0) }
    val access by produceState(LocalCurationFolderAccess(), songs, refreshKey) {
        value = runCatchingNonCancellation {
            resolveLocalCurationFolderAccess(FolderArtworkLocator.directoriesNeedingAccess(context, songs))
        }.getOrDefault(LocalCurationFolderAccess())
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // 同时保留写权限，之后嵌入词图可以直接走文件夹授权，不必每批再向系统申请修改媒体文件
            val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, readWrite) }
                .recoverCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            FolderArtworkLocator.clearCache()
            refreshKey++
        }
    }
    if (access.pendingSongs == 0) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pluralStringResource(
                        CoreCommonR.plurals.local_curation_folder_access_needed,
                        access.pendingSongs,
                        access.pendingSongs
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            MiuixSettingsTextButton(
                onClick = { launcher.launch(access.suggestedFolder?.let(FolderArtworkLocator::documentUriFor)) }
            ) {
                Text(stringResource(CoreCommonR.string.local_curation_folder_access_grant))
            }
        }
    }
}
