package moe.ouom.neriplayer.core.download.storage.tree

import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX

internal object ManagedDownloadTreeNaming {
    fun resolveTreeStoredName(actualName: String?, expectedName: String): String {
        return actualName?.takeIf(String::isNotBlank) ?: expectedName
    }

    fun documentCreateMimeType(desiredName: String, mimeType: String): String {
        val normalizedMimeType = mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val extension = desiredName.substringAfterLast('.', "").lowercase()
        if (normalizedMimeType.equals("text/plain", ignoreCase = true) && extension.isNotBlank() && extension != "txt") {
            return "application/octet-stream"
        }
        if (
            extension.isNotBlank() &&
            (
                normalizedMimeType.startsWith("audio/", ignoreCase = true) ||
                    normalizedMimeType.startsWith("image/", ignoreCase = true)
                )
        ) {
            // 有些 SAF 提供方会按 MIME 再补一次后缀，二进制兜底能保住原文件名
            return "application/octet-stream"
        }
        if (
            normalizedMimeType.equals("application/json", ignoreCase = true) &&
            desiredName.endsWith(METADATA_SUFFIX, ignoreCase = true)
        ) {
            return "application/octet-stream"
        }
        return normalizedMimeType
    }

    fun shouldCreateNoMediaMarker(subdirectory: String): Boolean {
        return subdirectory == COVER_SUBDIRECTORY
    }

    fun matchesManagedSubdirectoryName(actualName: String, desiredName: String): Boolean {
        if (actualName == desiredName) {
            return true
        }
        if (!actualName.startsWith("$desiredName (") || !actualName.endsWith(")")) {
            return false
        }
        val suffix = actualName.removePrefix("$desiredName (").removeSuffix(")")
        return suffix.isNotBlank() && suffix.all(Char::isDigit)
    }

    fun managedSubdirectoryOrdinal(actualName: String, desiredName: String): Int {
        if (actualName == desiredName) {
            return 0
        }
        return actualName.removePrefix("$desiredName (")
            .removeSuffix(")")
            .toIntOrNull()
            ?: Int.MAX_VALUE
    }
}
