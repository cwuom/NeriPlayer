package moe.ouom.neriplayer.core.download.storage.root

import androidx.documentfile.provider.DocumentFile
import java.io.File

internal sealed interface ManagedDownloadRootHandle {
    data class FileRoot(val dir: File) : ManagedDownloadRootHandle
    data class TreeRoot(val tree: DocumentFile) : ManagedDownloadRootHandle
}
