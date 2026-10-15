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
 * 写入前集中申请媒体文件修改权限：都已可写时直接执行，否则依次完成系统授权
 * （Android 10 为授予歌曲所在文件夹），全部同意后执行 [onGranted]；
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
    val pendingConsents = remember { ArrayDeque<PendingIntent>() }
    var launchNextConsent: () -> Unit = {}
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            pendingConsents.clear()
            denied()
        } else {
            pendingConsents.removeFirstOrNull()
            launchNextConsent()
        }
    }
    launchNextConsent = {
        pendingConsents.firstOrNull()
            ?.let { next -> consentLauncher.launch(IntentSenderRequest.Builder(next.intentSender).build()) }
            ?: granted()
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && takeLocalCurationFolderGrant(context, uri, requireWrite = true)) granted() else denied()
    }
    return remember(consentLauncher, folderLauncher) {
        { songs ->
            scope.launch {
                when (val plan = LocalMediaWriteAccess.plan(context, songs)) {
                    LocalMediaWriteAccessPlan.Ready -> granted()
                    is LocalMediaWriteAccessPlan.SystemConsent -> {
                        pendingConsents.clear()
                        pendingConsents.addAll(plan.requests)
                        launchNextConsent()
                    }
                    is LocalMediaWriteAccessPlan.FolderGrant ->
                        folderLauncher.launch(plan.suggestedFolder?.let(FolderArtworkLocator::documentUriFor))
                    is LocalMediaWriteAccessPlan.Unavailable -> denied()
                }
            }
        }
    }
}
