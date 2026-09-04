package moe.ouom.neriplayer.core.download.storage.tree

import android.net.Uri
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_TEMPORARY_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import java.text.Normalizer
import java.util.Locale

internal object ManagedDownloadTreeNaming {
    fun canCreateWhenChildrenQueryIsIncomplete(parentUri: Uri): Boolean {
        return canCreateWhenChildrenQueryIsIncomplete(
            scheme = parentUri.scheme,
            authority = parentUri.authority
        )
    }

    internal fun canCreateWhenChildrenQueryIsIncomplete(
        scheme: String?,
        authority: String?
    ): Boolean {
        // 不完整枚举无法证明目标不存在, 继续创建会让 ExternalStorageProvider 产生副本
        return false
    }

    fun resolveTreeStoredName(actualName: String?, expectedName: String): String {
        return actualName?.takeIf(String::isNotBlank) ?: expectedName
    }

    fun isExactTreeStoredName(actualName: String?, expectedName: String): Boolean {
        return canonicalName(resolveTreeStoredName(actualName, expectedName)) == canonicalName(expectedName)
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
        return subdirectory.equals(COVER_SUBDIRECTORY, ignoreCase = true) ||
            subdirectory.equals(DOWNLOAD_TEMPORARY_DIR_NAME, ignoreCase = true)
    }

    fun matchesManagedSubdirectoryName(actualName: String, desiredName: String): Boolean {
        return canonicalName(actualName) == canonicalName(desiredName) ||
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
        pendingMetadataAudioName(actualName)?.let { return it }
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
        if (canonicalName(actualName) == canonicalName(expectedName)) return 0
        providerNumberedNameOrdinal(actualName, expectedName)?.let { return it }
        val pendingExpectedName = "$audioName$PENDING_METADATA_SUFFIX"
        if (canonicalName(actualName) == canonicalName(pendingExpectedName)) return 1
        return providerNumberedNameOrdinal(actualName, pendingExpectedName)?.let { it + 1 }
    }

    fun isPendingMetadataName(actualName: String, audioName: String): Boolean {
        val expectedName = "$audioName$PENDING_METADATA_SUFFIX"
        return canonicalName(actualName) == canonicalName(expectedName) ||
            providerNumberedNameOrdinal(actualName, expectedName) != null
    }

    private fun pendingMetadataAudioName(actualName: String): String? {
        val wholeNameMarkerIndex = actualName.lastIndexOf(" (")
        if (wholeNameMarkerIndex >= 0 && actualName.endsWith(")")) {
            val ordinal = actualName
                .substring(wholeNameMarkerIndex + 2, actualName.length - 1)
                .toIntOrNull()
            if (ordinal != null) {
                val unnumberedName = actualName.substring(0, wholeNameMarkerIndex)
                if (unnumberedName.endsWith(PENDING_METADATA_SUFFIX, ignoreCase = true)) {
                    return unnumberedName.substring(
                        0,
                        unnumberedName.length - PENDING_METADATA_SUFFIX.length
                    )
                }
            }
        }
        val pendingExtension = PENDING_METADATA_SUFFIX.substringAfterLast('.')
        val pendingExtensionSuffix = ".${pendingExtension}"
        if (actualName.endsWith(pendingExtensionSuffix, ignoreCase = true)) {
            val stem = actualName.substring(0, actualName.length - pendingExtensionSuffix.length)
            val markerIndex = stem.lastIndexOf(" (")
            if (markerIndex >= 0 && stem.endsWith(")")) {
                val ordinal = stem.substring(markerIndex + 2, stem.length - 1).toIntOrNull()
                val unnumberedStem = stem.substring(0, markerIndex)
                val pendingStemSuffix = PENDING_METADATA_SUFFIX.removeSuffix(pendingExtensionSuffix)
                if (ordinal != null && unnumberedStem.endsWith(pendingStemSuffix, ignoreCase = true)) {
                    return unnumberedStem.substring(
                        0,
                        unnumberedStem.length - pendingStemSuffix.length
                    )
                }
            }
        }
        if (actualName.endsWith(PENDING_METADATA_SUFFIX, ignoreCase = true)) {
            return actualName.substring(0, actualName.length - PENDING_METADATA_SUFFIX.length)
        }
        return null
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

    private fun canonicalName(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}
