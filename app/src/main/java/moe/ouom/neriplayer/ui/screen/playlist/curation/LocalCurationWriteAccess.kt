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

/** 一次写入授权流程的进度：依次弹出的系统授权，以及文件夹授权后仍未覆盖的歌曲数 */
private class WriteAccessRequest {
    var songs: List<SongItem> = emptyList()
    val pendingConsents = ArrayDeque<PendingIntent>()
    var folderPendingSongs = Int.MAX_VALUE

    fun restart(songs: List<SongItem>) {
        this.songs = songs
        pendingConsents.clear()
        folderPendingSongs = Int.MAX_VALUE
    }
}

/**
 * 文件夹授权要逐步覆盖全部歌曲；某次授权没有让待授权歌曲减少（选错目录或已无法覆盖），
 * 继续弹选择器只会让用户陷入循环，按拒绝处理
 */
internal fun folderGrantMadeProgress(previousPendingSongs: Int, pendingSongs: Int): Boolean =
    pendingSongs < previousPendingSongs

/**
 * 写入前集中申请媒体文件修改权限：都已可写时直接执行，否则依次完成系统授权
 * （Android 10 为授予歌曲所在文件夹，覆盖不全时继续申请），全部同意后执行 [onGranted]；
 * 拒绝或系统无法创建授权请求时回调 [onDenied]，不会进入必然失败的写入
 */
@Composable
internal fun rememberLocalCurationWriteAccessRequester(
    onGranted: () -> Unit,
    onDenied: () -> Unit
): (List<SongItem>) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val granted by rememberUpdatedState(onGranted)
    val denied by rememberUpdatedState(onDenied)
    val request = remember { WriteAccessRequest() }
    var launchNextConsent: () -> Unit = {}
    var resolve: () -> Unit = {}
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            request.pendingConsents.clear()
            denied()
        } else {
            request.pendingConsents.removeFirstOrNull()
            launchNextConsent()
        }
    }
    launchNextConsent = {
        request.pendingConsents.firstOrNull()
            ?.let { next -> consentLauncher.launch(IntentSenderRequest.Builder(next.intentSender).build()) }
            ?: granted()
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && takeLocalCurationFolderGrant(context, uri, requireWrite = true)) resolve() else denied()
    }
    resolve = {
        scope.launch {
            when (val plan = LocalMediaWriteAccess.plan(context, request.songs)) {
                LocalMediaWriteAccessPlan.Ready -> granted()
                is LocalMediaWriteAccessPlan.SystemConsent -> {
                    request.pendingConsents.clear()
                    request.pendingConsents.addAll(plan.requests)
                    launchNextConsent()
                }
                is LocalMediaWriteAccessPlan.FolderGrant -> {
                    if (folderGrantMadeProgress(request.folderPendingSongs, plan.pendingSongs)) {
                        request.folderPendingSongs = plan.pendingSongs
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
        { songs ->
            request.restart(songs)
            resolve()
        }
    }
}
