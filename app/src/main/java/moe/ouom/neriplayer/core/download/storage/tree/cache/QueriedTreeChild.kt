package moe.ouom.neriplayer.core.download.storage.tree.cache

import android.net.Uri

internal data class QueriedTreeChild(
    val name: String,
    val documentUri: Uri,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val isDirectory: Boolean
)
