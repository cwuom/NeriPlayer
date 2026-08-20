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
        return subdirectory.equals(COVER_SUBDIRECTORY, ignoreCase = true)
    }

    fun matchesManagedSubdirectoryName(actualName: String, desiredName: String): Boolean {
        if (actualName.equals(desiredName, ignoreCase = true)) {
            return true
        }
        val prefix = "$desiredName ("
        if (!actualName.startsWith(prefix, ignoreCase = true) || !actualName.endsWith(")")) {
            return false
        }
        val suffix = actualName.substring(prefix.length, actualName.length - 1)
        return suffix.isNotBlank() && suffix.all(Char::isDigit)
    }

    fun managedSubdirectoryOrdinal(actualName: String, desiredName: String): Int {
        if (actualName.equals(desiredName, ignoreCase = true)) {
            return 0
        }
        val prefix = "$desiredName ("
        if (!actualName.startsWith(prefix, ignoreCase = true) || !actualName.endsWith(")")) {
            return Int.MAX_VALUE
        }
        return actualName.substring(prefix.length, actualName.length - 1)
            .toIntOrNull()
            ?: Int.MAX_VALUE
    }
}
