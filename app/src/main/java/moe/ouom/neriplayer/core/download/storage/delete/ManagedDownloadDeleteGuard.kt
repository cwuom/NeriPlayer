package moe.ouom.neriplayer.core.download.storage.delete

import java.io.File
internal data class ManagedDownloadDeletePolicy(
    val managedFileRoots: List<String>,
    val managedTreeRoots: List<String>,
    val trustedReferences: Set<moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef>
)

internal object ManagedDownloadDeleteGuard {
    fun isReferenceAllowedForManagedDelete(
        reference: String,
        trustedReferences: Set<String>,
        managedFileRoots: Collection<String>,
        managedTreeRoots: Collection<String>,
        onTrustedReferenceOutsideManagedRoot: ((String) -> Unit)? = null
    ): Boolean {
        val normalizedReference = reference.takeIf(String::isNotBlank) ?: return false
        val isTrusted = normalizedReference in trustedReferences
        if (normalizedReference.startsWith("/")) {
            val underRoot = managedFileRoots.any { rootPath ->
                isFileReferenceUnderManagedRoot(normalizedReference, rootPath)
            }
            if (!underRoot && isTrusted) {
                onTrustedReferenceOutsideManagedRoot?.invoke(normalizedReference)
            }
            return underRoot
        }
        if (isTrusted) {
            return true
        }
        // SAF document ids are opaque. A tree/document URI that merely looks nested
        // under the configured root is not deletion evidence without enumeration.
        return false
    }

    fun isFileReferenceUnderManagedRoot(reference: String, managedRootPath: String): Boolean {
        val root = runCatching { File(managedRootPath).canonicalFile }.getOrNull() ?: return false
        val target = runCatching { File(reference).canonicalFile }.getOrNull() ?: return false
        if (target == root) {
            return false
        }
        return generateSequence(target.parentFile) { file -> file.parentFile }
            .any { parent -> parent == root }
    }
}
