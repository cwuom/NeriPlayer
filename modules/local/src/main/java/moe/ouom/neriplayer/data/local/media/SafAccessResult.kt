package moe.ouom.neriplayer.data.local.media

import android.net.Uri

/**
 * 统一描述 SAF 只读访问失败，避免业务层把权限错误误判成空目录
 */
internal sealed interface SafAccessResult<out T> {
    data class Ok<T>(val value: T) : SafAccessResult<T>

    data class OutOfScope(
        val treeUri: Uri,
        val attemptedDocumentId: String?,
        val cause: SecurityException? = null
    ) : SafAccessResult<Nothing>

    data class PermissionLost(
        val treeUri: Uri,
        val cause: SecurityException? = null
    ) : SafAccessResult<Nothing>

    data class ProviderFailure(
        val treeUri: Uri,
        val cause: Throwable
    ) : SafAccessResult<Nothing>
}
