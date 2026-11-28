package moe.ouom.neriplayer.ui.dialog

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.storage.directory.ManagedDownloadDirectoryIdentity
import moe.ouom.neriplayer.data.local.media.curation.StorageDocumentPath
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.StoragePermissionFallback
import moe.ouom.neriplayer.data.model.playback.StoragePermissionScope

private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"

internal data class StoragePermissionPrompt(
    val event: PlayerEvent.StoragePermissionLost,
    /** 需要重新授权的 SAF 树；引用不是 SAF 树文档时为空，只能提示无法自助恢复 */
    val targetTreeUri: String?
) {
    val key: String = "${event.scope}:${targetTreeUri ?: event.reference.orEmpty()}"
}

internal fun StoragePermissionPrompt.toSavedState(): ArrayList<String> = arrayListOf(
    event.scope.name,
    event.fallback.name,
    event.reference.orEmpty(),
    targetTreeUri.orEmpty()
)

internal fun restoreStoragePermissionPrompt(saved: List<String>): StoragePermissionPrompt? {
    if (saved.size != 4) return null
    val scope = StoragePermissionScope.entries.firstOrNull { it.name == saved[0] } ?: return null
    val fallback = StoragePermissionFallback.entries.firstOrNull { it.name == saved[1] } ?: return null
    return StoragePermissionPrompt(
        event = PlayerEvent.StoragePermissionLost(scope, fallback, saved[2].ifBlank { null }),
        targetTreeUri = saved[3].ifBlank { null }
    )
}

internal val StoragePermissionPromptSaver = Saver<StoragePermissionPrompt?, ArrayList<String>>(
    save = { it?.toSavedState() },
    restore = ::restoreStoragePermissionPrompt
)

internal fun storagePermissionPrompt(
    event: PlayerEvent.StoragePermissionLost,
    downloadDirectoryUri: String?
): StoragePermissionPrompt {
    val source = when (event.scope) {
        StoragePermissionScope.DOWNLOAD_DIRECTORY -> downloadDirectoryUri
        StoragePermissionScope.LOCAL_MEDIA -> event.reference
    }
    return StoragePermissionPrompt(event, safTreeUriOf(source))
}

internal fun safTreeUriOf(reference: String?): String? {
    val value = reference?.trim()?.takeIf { it.startsWith("content://") && "/tree/" in it } ?: return null
    return ManagedDownloadDirectoryIdentity.normalizeConfiguredDirectoryUri(value)
}

/** 用户在选择器里选中的目录能否恢复失效的访问 */
internal fun pickedTreeRestoresAccess(prompt: StoragePermissionPrompt, pickedTreeUri: String): Boolean {
    val target = prompt.targetTreeUri ?: return false
    if (ManagedDownloadDirectoryIdentity.areEquivalentDirectoryUris(target, pickedTreeUri)) return true
    // 下载根目录按目录身份校验写权限，选父目录也无法让存储层重新识别
    if (prompt.event.scope == StoragePermissionScope.DOWNLOAD_DIRECTORY) return false
    val reference = prompt.event.reference ?: return false
    return treeCoversDocument(pickedTreeUri, reference)
}

internal fun treeCoversDocument(treeUri: String, documentUri: String): Boolean {
    val authority = ManagedDownloadDirectoryIdentity.extractDirectoryAuthority(treeUri)
    if (authority.isBlank() || authority != ManagedDownloadDirectoryIdentity.extractDirectoryAuthority(documentUri)) {
        return false
    }
    val treeId = ManagedDownloadDirectoryIdentity.extractDirectoryDocumentId(treeUri, "/tree/") ?: return false
    val documentId = ManagedDownloadDirectoryIdentity.extractDirectoryDocumentId(documentUri, "/document/")
        ?: ManagedDownloadDirectoryIdentity.extractDirectoryDocumentId(documentUri, "/tree/")
        ?: return false
    if (documentId == treeId) return true
    // 只有 externalstorage 的文档 ID 是 volume:path 结构，其他 provider 的 ID 不能按路径推断层级
    if (authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return false
    val volume = documentId.substringBefore(':', "").takeIf(String::isNotBlank) ?: return false
    return StorageDocumentPath(volume, documentId.substringAfter(':').trim('/')).isInside(treeId)
}

internal fun storagePermissionDirectoryLabel(treeUri: String?): String? {
    val documentId = treeUri?.let {
        ManagedDownloadDirectoryIdentity.extractDirectoryDocumentId(it, "/tree/")
    } ?: return null
    return documentId.substringAfter(':', "").trim('/').ifBlank { documentId }
}

@StringRes
internal fun storagePermissionMessageRes(event: PlayerEvent.StoragePermissionLost): Int = when (event.scope) {
    StoragePermissionScope.DOWNLOAD_DIRECTORY -> when (event.fallback) {
        StoragePermissionFallback.STREAMING -> AppR.string.storage_permission_lost_download_streaming
        StoragePermissionFallback.SKIPPED,
        StoragePermissionFallback.NONE -> AppR.string.storage_permission_lost_download_check
    }
    StoragePermissionScope.LOCAL_MEDIA -> when (event.fallback) {
        StoragePermissionFallback.STREAMING -> AppR.string.storage_permission_lost_local_streaming
        StoragePermissionFallback.SKIPPED,
        StoragePermissionFallback.NONE -> AppR.string.storage_permission_lost_local_skipped
    }
}

internal enum class StoragePermissionPromptAction { SHOW, NOTICE, IGNORE }

internal fun storagePermissionPromptAction(
    prompt: StoragePermissionPrompt,
    showingKey: String?,
    dismissedKeys: Set<String>
): StoragePermissionPromptAction = when {
    prompt.key == showingKey -> StoragePermissionPromptAction.IGNORE
    // 用户选了稍后处理时不再反复弹窗，但跳歌仍需告诉用户原因
    prompt.key in dismissedKeys || showingKey != null ->
        if (prompt.event.fallback == StoragePermissionFallback.SKIPPED) {
            StoragePermissionPromptAction.NOTICE
        } else {
            StoragePermissionPromptAction.IGNORE
        }
    else -> StoragePermissionPromptAction.SHOW
}

/** 授权失效弹窗的进程内状态，同一目录在本次运行中只主动弹一次 */
@Stable
internal class StoragePermissionPromptState {
    var current by mutableStateOf<StoragePermissionPrompt?>(null)
        private set
    private val dismissedKeys = mutableSetOf<String>()

    fun offer(prompt: StoragePermissionPrompt): StoragePermissionPromptAction {
        val action = storagePermissionPromptAction(prompt, current?.key, dismissedKeys)
        if (action == StoragePermissionPromptAction.SHOW) current = prompt
        return action
    }

    fun dismiss() {
        current?.let { dismissedKeys += it.key }
        current = null
    }

    fun resolve() {
        current?.let { dismissedKeys -= it.key }
        current = null
    }
}

internal enum class StorageReauthorizationOutcome(@StringRes val messageRes: Int) {
    RESTORED(AppR.string.storage_permission_restored),
    MISMATCH(AppR.string.storage_permission_mismatch),
    FAILED(AppR.string.storage_permission_restore_failed)
}

internal fun storageReauthorizationOutcome(
    granted: Boolean,
    pickedMatches: Boolean,
    accessVerified: Boolean
): StorageReauthorizationOutcome = when {
    !granted -> StorageReauthorizationOutcome.FAILED
    !pickedMatches -> StorageReauthorizationOutcome.MISMATCH
    !accessVerified -> StorageReauthorizationOutcome.FAILED
    else -> StorageReauthorizationOutcome.RESTORED
}
