package moe.ouom.neriplayer.ui.screen.playlist.curation

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.local.media.curation.FolderArtworkLocator
import moe.ouom.neriplayer.data.local.media.curation.LocalMediaWriteAccess
import moe.ouom.neriplayer.data.local.media.curation.LocalMediaWriteAccessPlan
import moe.ouom.neriplayer.data.model.SongItem

/**
 * 一次授权流程中已经弹出的请求；只用于判断进度，Activity 重建后丢失也无妨，
 * 每次系统界面返回都会按当前选中的歌曲重新规划
 */
private class WriteAccessProgress {
    val pendingConsents = ArrayDeque<PendingIntent>()
    var consentPendingItems = Int.MAX_VALUE
    var folderPendingSongs = Int.MAX_VALUE

    fun restart() {
        pendingConsents.clear()
        consentPendingItems = Int.MAX_VALUE
        folderPendingSongs = Int.MAX_VALUE
    }
}

/**
 * 每轮授权后待授权数量必须减少才继续弹出系统界面，否则（选错目录、系统没有记下授权）
 * 再弹只会让用户陷入循环
 */
internal fun writeAccessMadeProgress(previousPending: Int, pending: Int): Boolean = pending < previousPending

/**
 * 写入前集中申请媒体文件修改权限：都已可写时直接执行，否则依次完成系统授权
 * （Android 10 为授予歌曲所在文件夹）。每次系统界面返回都按 [songs] 重新规划，
 * 确认全部可写才执行 [onGranted]；拒绝或无法创建授权请求时回调 [onDenied]
 */
@Composable
internal fun rememberLocalCurationWriteAccessRequester(
    songs: () -> List<SongItem>,
    onGranted: () -> Unit,
    onDenied: () -> Unit
): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val currentSongs by rememberUpdatedState(songs)
    val granted by rememberUpdatedState(onGranted)
    val denied by rememberUpdatedState(onDenied)
    val progress = remember { WriteAccessProgress() }
    var resolve: () -> Unit = {}
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            progress.pendingConsents.clear()
            denied()
        } else {
            progress.pendingConsents.removeFirstOrNull()
            resolve()
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && takeLocalCurationFolderGrant(context, uri, requireWrite = true)) resolve() else denied()
    }
    resolve = resolve@{
        progress.pendingConsents.firstOrNull()?.let { next ->
            consentLauncher.launch(IntentSenderRequest.Builder(next.intentSender).build())
            return@resolve
        }
        scope.launch {
            when (val plan = LocalMediaWriteAccess.plan(context, currentSongs())) {
                LocalMediaWriteAccessPlan.Ready -> granted()
                is LocalMediaWriteAccessPlan.SystemConsent -> {
                    // 用户同意后系统仍报告未授权时不再重复弹窗，交给写入结果逐首反馈
                    if (!writeAccessMadeProgress(progress.consentPendingItems, plan.pendingItems)) {
                        granted()
                        return@launch
                    }
                    progress.consentPendingItems = plan.pendingItems
                    progress.pendingConsents.addAll(plan.requests)
                    resolve()
                }
                is LocalMediaWriteAccessPlan.FolderGrant -> {
                    if (writeAccessMadeProgress(progress.folderPendingSongs, plan.pendingSongs)) {
                        progress.folderPendingSongs = plan.pendingSongs
                        folderLauncher.launch(plan.suggestedFolder?.let(FolderArtworkLocator::documentUriFor))
                    } else {
                        denied()
                    }
                }
                is LocalMediaWriteAccessPlan.Unavailable -> denied()
            }
        }
    }
    return remember(consentLauncher, folderLauncher) {
        {
            progress.restart()
            resolve()
        }
    }
}
