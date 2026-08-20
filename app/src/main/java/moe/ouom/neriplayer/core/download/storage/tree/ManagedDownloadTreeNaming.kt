package moe.ouom.neriplayer.core.download.storage.tree

import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX

internal object ManagedDownloadTreeNaming {
    fun resolveTreeStoredName(actualName: String?, expectedName: String): String {
        return actualName?.takeIf(String::isNotBlank) ?: expectedName
    }

    fun isExactTreeStoredName(actualName: String?, expectedName: String): Boolean {
        return resolveTreeStoredName(actualName, expectedName)
            .equals(expectedName, ignoreCase = true)
    }

    fun documentCreateMimeType(desiredName: String, mimeType: String): String {
        val normalizedMimeType = mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val extension = desiredName.substringAfterLast('.', "").lowercase()
        val nameBeforePendingSuffix = desiredName.substringBefore(
            delimiter = ".npdl_pending.",
            missingDelimiterValue = desiredName
        )
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
            isMetadataName(nameBeforePendingSuffix)
        ) {
            return "application/octet-stream"
        }
        return normalizedMimeType
    }

    fun shouldCreateNoMediaMarker(subdirectory: String): Boolean {
        return subdirectory.equals(COVER_SUBDIRECTORY, ignoreCase = true)
    }

    fun matchesManagedSubdirectoryName(actualName: String, desiredName: String): Boolean {
        return actualName.equals(desiredName, ignoreCase = true) ||
            providerNumberedNameOrdinal(actualName, desiredName) != null
    }

    fun managedSubdirectoryOrdinal(actualName: String, desiredName: String): Int {
        if (actualName.equals(desiredName, ignoreCase = true)) {
            return 0
        }
        return providerNumberedNameOrdinal(actualName, desiredName) ?: Int.MAX_VALUE
    }

    fun matchesProviderNumberedName(actualName: String, expectedName: String): Boolean {
        return providerNumberedNameOrdinal(actualName, expectedName) != null
    }

    fun metadataAudioName(actualName: String): String? {
        val wholeNameMarkerIndex = actualName.lastIndexOf(" (")
        if (wholeNameMarkerIndex >= 0 && actualName.endsWith(")")) {
            val ordinal = actualName
                .substring(wholeNameMarkerIndex + 2, actualName.length - 1)
                .toIntOrNull()
            if (ordinal != null) {
                val unnumberedName = actualName.substring(0, wholeNameMarkerIndex)
                if (unnumberedName.endsWith(METADATA_SUFFIX, ignoreCase = true)) {
                    return unnumberedName.substring(0, unnumberedName.length - METADATA_SUFFIX.length)
                }
            }
        }
        if (actualName.endsWith(METADATA_SUFFIX, ignoreCase = true)) {
            val stem = actualName.substring(0, actualName.length - METADATA_SUFFIX.length)
            val markerIndex = stem.lastIndexOf(" (")
            if (markerIndex >= 0 && stem.endsWith(")")) {
                val ordinal = stem.substring(markerIndex + 2, stem.length - 1).toIntOrNull()
                if (ordinal != null) {
                    val numberedStem = stem.substring(0, markerIndex)
                    if (numberedStem.endsWith(".npmeta", ignoreCase = true)) {
                        return numberedStem.substring(0, numberedStem.length - ".npmeta".length)
                    }
                }
            }
            return stem
        }

        val extension = METADATA_SUFFIX.substringAfterLast('.')
        val extensionSuffix = ".${extension}"
        if (!actualName.endsWith(extensionSuffix, ignoreCase = true)) return null
        val stem = actualName.substring(0, actualName.length - extensionSuffix.length)
        val markerIndex = stem.lastIndexOf(" (")
        if (markerIndex < 0 || !stem.endsWith(")")) return null
        if (stem.substring(markerIndex + 2, stem.length - 1).toIntOrNull() == null) return null
        val numberedStem = stem.substring(0, markerIndex)
        if (!numberedStem.endsWith(".npmeta", ignoreCase = true)) return null
        return numberedStem.substring(0, numberedStem.length - ".npmeta".length)
    }

    fun isMetadataName(actualName: String): Boolean {
        return metadataAudioName(actualName) != null
    }

    fun metadataNameOrdinal(actualName: String, audioName: String): Int? {
        val expectedName = "$audioName$METADATA_SUFFIX"
        if (actualName.equals(expectedName, ignoreCase = true)) return 0
        return providerNumberedNameOrdinal(actualName, expectedName)
    }

    fun providerNumberedNameOrdinal(actualName: String, expectedName: String): Int? {
        numberedNameOrdinal(
            actualName = actualName,
            prefix = "$expectedName (",
            suffix = ""
        )?.let { return it }

        val extensionIndex = expectedName.lastIndexOf('.')
        if (extensionIndex <= 0 || extensionIndex == expectedName.lastIndex) {
            return null
        }
        return numberedNameOrdinal(
            actualName = actualName,
            prefix = expectedName.substring(0, extensionIndex) + " (",
            suffix = expectedName.substring(extensionIndex)
        )
    }

    private fun numberedNameOrdinal(
        actualName: String,
        prefix: String,
        suffix: String
    ): Int? {
        if (
            !actualName.startsWith(prefix, ignoreCase = true) ||
                !actualName.endsWith(suffix, ignoreCase = true)
        ) {
            return null
        }
        val numberEnd = actualName.length - suffix.length
        if (numberEnd <= prefix.length || actualName[numberEnd - 1] != ')') {
            return null
        }
        return actualName.substring(prefix.length, numberEnd - 1).toIntOrNull()
    }
}
