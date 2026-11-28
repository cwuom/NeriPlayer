package moe.ouom.neriplayer.ui.dialog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.StoragePermissionFallback
import moe.ouom.neriplayer.data.model.playback.StoragePermissionScope
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.component.overlay.ScrollableDialogColumn
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.feedback.launchOrShowUnavailable
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DownloadDirectoryAvailability
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.probeConfiguredDownloadRoot
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.resolveDownloadDirectoryAvailability
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.resolveDownloadDirectoryPermissionLost

private const val STORAGE_PERMISSION_TAG = "NERI-StoragePermission"
private const val READ_WRITE_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

private class PersistableOpenDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(
            READ_WRITE_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
}

@Composable
internal fun StoragePermissionLostDialogHost(state: StoragePermissionPromptState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var verifying by remember { mutableStateOf(false) }
    // 选择器打开期间 Activity 或进程可能被重建，弹窗状态不会保留，授权目标要单独存进 saved state
    var pendingRequest by rememberSaveable(stateSaver = StoragePermissionPromptSaver) {
        mutableStateOf<StoragePermissionPrompt?>(null)
    }
    // 启动器需要在弹窗出现前注册，Activity 重建后才能收到选择结果
    val launcher = rememberLauncherForActivityResult(remember { PersistableOpenDocumentTree() }) { picked ->
        val prompt = pendingRequest ?: state.current
        pendingRequest = null
        if (picked == null || prompt == null) return@rememberLauncherForActivityResult
        verifying = true
        scope.launch {
            val outcome = reauthorizeStorageTree(context, prompt, picked)
            verifying = false
            // 授权常在播放页发起，播放页不挂主界面的 Snackbar，用 Toast 保证结果可见
            AppFeedback.showToast(context, resources.getString(outcome.messageRes))
            when {
                outcome == StorageReauthorizationOutcome.RESTORED -> state.resolve()
                // 重建后弹窗已丢失，未恢复时重新给出入口
                state.current == null -> state.offer(prompt)
            }
        }
    }
    val prompt = state.current ?: return
    val directoryLabel = storagePermissionDirectoryLabel(prompt.targetTreeUri)
    AlertDialog(
        onDismissRequest = { if (!verifying) state.dismiss() },
        title = { Text(stringResource(AppR.string.storage_permission_lost_title)) },
        text = {
            ScrollableDialogColumn {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(storagePermissionMessageRes(prompt.event)))
                    directoryLabel?.let {
                        Text(
                            text = stringResource(AppR.string.storage_permission_lost_directory, it),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (prompt.targetTreeUri == null) {
                        Text(stringResource(AppR.string.storage_permission_lost_reimport_hint))
                    }
                }
            }
        },
        confirmButton = {
            val target = prompt.targetTreeUri
            if (target != null) {
                HapticTextButton(
                    enabled = !verifying,
                    onClick = {
                        pendingRequest = prompt
                        if (!launcher.launchOrShowUnavailable(context, initialPickerUri(target))) {
                            pendingRequest = null
                        }
                    }
                ) {
                    Text(stringResource(AppR.string.storage_permission_reauthorize))
                }
            } else {
                HapticTextButton(onClick = state::dismiss) {
                    Text(stringResource(CoreCommonR.string.action_confirm))
                }
            }
        },
        dismissButton = if (prompt.targetTreeUri != null) {
            {
                HapticTextButton(enabled = !verifying, onClick = state::dismiss) {
                    Text(stringResource(AppR.string.storage_permission_later))
                }
            }
        } else {
            null
        }
    )
}

private fun initialPickerUri(treeUri: String): Uri? = runCatching {
    val tree = treeUri.toUri()
    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
}.getOrNull()

private suspend fun reauthorizeStorageTree(
    context: Context,
    prompt: StoragePermissionPrompt,
    picked: Uri
): StorageReauthorizationOutcome {
    val resolver = context.contentResolver
    val grant = withContext(Dispatchers.IO) {
        val alreadyPersisted = resolver.persistedUriPermissions.any { it.uri == picked }
        val flags = takePersistableGrant(context, prompt.event.scope, picked)
        alreadyPersisted to flags
    }
    val (alreadyPersisted, takenFlags) = grant
    val pickedMatches = pickedTreeRestoresAccess(prompt, picked.toString())
    val accessVerified = takenFlags != null && pickedMatches && verifyRestoredAccess(context, prompt)
    val outcome = storageReauthorizationOutcome(takenFlags != null, pickedMatches, accessVerified)
    withContext(Dispatchers.IO) {
        if (outcome != StorageReauthorizationOutcome.RESTORED && takenFlags != null && !alreadyPersisted) {
            // 用户选错目录时不留下额外授权，避免持续占用系统的持久授权配额
            runCatching { resolver.releasePersistableUriPermission(picked, takenFlags) }
        }
        if (outcome == StorageReauthorizationOutcome.RESTORED &&
            prompt.event.scope == StoragePermissionScope.DOWNLOAD_DIRECTORY
        ) {
            GlobalDownloadManager.scanLocalFiles(context, forceRefresh = true)
        }
    }
    NPLogger.i(
        STORAGE_PERMISSION_TAG,
        "reauthorize: scope=${prompt.event.scope} fallback=${prompt.event.fallback} outcome=$outcome"
    )
    return outcome
}

private fun takePersistableGrant(context: Context, scope: StoragePermissionScope, picked: Uri): Int? {
    val resolver = context.contentResolver
    if (runCatching { resolver.takePersistableUriPermission(picked, READ_WRITE_FLAGS) }.isSuccess) {
        return READ_WRITE_FLAGS
    }
    // 本地导入只需要读取；下载目录必须可写，不能降级
    if (scope == StoragePermissionScope.LOCAL_MEDIA &&
        runCatching { resolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
    ) {
        return Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    return null
}

private suspend fun verifyRestoredAccess(context: Context, prompt: StoragePermissionPrompt): Boolean {
    // 本地导入可能选的是父目录，播放侧会按目录授权恢复引用，这里只校验覆盖关系
    if (prompt.event.scope != StoragePermissionScope.DOWNLOAD_DIRECTORY) return true
    return resolveDownloadDirectoryAvailability(
        directoryUri = prompt.targetTreeUri,
        isRootResolvable = { probeConfiguredDownloadRoot(context) }
    ) == DownloadDirectoryAvailability.Available
}

internal suspend fun StoragePermissionPromptState.offerFromEvent(
    context: Context,
    event: PlayerEvent.StoragePermissionLost
) {
    val downloadDirectoryUri = AppContainer.settingsRepo.downloadDirectoryUriFlow.first()
    if (offer(storagePermissionPrompt(event, downloadDirectoryUri)) == StoragePermissionPromptAction.NOTICE) {
        AppFeedback.showToast(context, context.getString(AppR.string.storage_permission_lost_skipped_notice))
    }
}

/** 授权可能在应用不在前台时被系统回收，启动时主动检查一次下载目录，避免等到播放失败才发现 */
@Composable
internal fun StoragePermissionStartupAudit(state: StoragePermissionPromptState) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (startupDownloadDirectoryAudited) return@LaunchedEffect
        startupDownloadDirectoryAudited = true
        val directoryUri = AppContainer.settingsRepo.downloadDirectoryUriFlow.first() ?: return@LaunchedEffect
        val permissionLost = resolveDownloadDirectoryPermissionLost(
            directoryUri = directoryUri,
            isRootResolvable = { probeConfiguredDownloadRoot(context.applicationContext) }
        )
        if (!permissionLost) return@LaunchedEffect
        NPLogger.w(STORAGE_PERMISSION_TAG, "startup audit: configured download directory is not accessible")
        state.offer(
            storagePermissionPrompt(
                PlayerEvent.StoragePermissionLost(
                    scope = StoragePermissionScope.DOWNLOAD_DIRECTORY,
                    fallback = StoragePermissionFallback.NONE
                ),
                directoryUri
            )
        )
    }
}

@Volatile
private var startupDownloadDirectoryAudited = false
