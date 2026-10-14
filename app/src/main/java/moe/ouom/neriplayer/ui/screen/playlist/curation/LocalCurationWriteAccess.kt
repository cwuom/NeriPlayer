package moe.ouom.neriplayer.ui.screen.playlist.curation

import android.app.Activity
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
import moe.ouom.neriplayer.data.local.media.curation.LocalMediaWriteAccess
import moe.ouom.neriplayer.data.model.SongItem

/**
 * 写入前集中申请媒体文件修改权限：都已可写时直接执行，否则弹出一次系统授权，
 * 同意后执行 [onGranted]，拒绝时回调 [onDenied]，不会进入必然失败的写入
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) granted() else denied()
    }
    return remember(launcher) {
        { songs ->
            scope.launch {
                val pending = LocalMediaWriteAccess.pendingWriteUris(context, songs)
                val request = LocalMediaWriteAccess.createWriteRequest(context, pending)
                if (request == null) {
                    granted()
                } else {
                    launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            }
        }
    }
}
