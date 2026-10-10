package moe.ouom.neriplayer.common.storage.directory

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ManagedDownloadDirectoryIdentity {
    fun normalizeDirectoryUri(uriString: String?): String? {
        return uriString?.trim()?.takeIf(String::isNotBlank)
    }

    fun normalizeConfiguredDirectoryUri(uriString: String?): String? {
        val normalized = withoutQueryOrFragment(uriString) ?: return null
        val authority = extractDirectoryAuthority(normalized).takeIf(String::isNotBlank) ?: return normalized
        val encodedDocumentId = extractEncodedDirectoryDocumentId(normalized, "/tree/")
            ?: extractEncodedDirectoryDocumentId(normalized, "/document/")
            ?: return normalized
        return "content://$authority/tree/$encodedDocumentId"
    }

    private fun withoutQueryOrFragment(uriString: String?): String? {
        val trimmed = normalizeDirectoryUri(uriString) ?: return null
        return trimmed.substringBefore('#').substringBefore('?').trimEnd('/').takeIf { it.isNotBlank() }
    }

    fun directoryIdentity(uriString: String?): String? {
        val normalized = normalizeConfiguredDirectoryUri(uriString) ?: return null
        extractDirectoryDocumentId(normalized, "/tree/")
            ?.let { documentId ->
                return "tree:${extractDirectoryAuthority(normalized)}:$documentId"
            }
        extractDirectoryDocumentId(normalized, "/document/")
            ?.let { documentId ->
                return "document:${extractDirectoryAuthority(normalized)}:$documentId"
            }
        return normalized
    }

    fun areEquivalentDirectoryUris(first: String?, second: String?): Boolean {
        val firstIdentity = directoryIdentity(first)
        val secondIdentity = directoryIdentity(second)
        return when {
            firstIdentity == null && secondIdentity == null -> true
            else -> firstIdentity != null && firstIdentity == secondIdentity
        }
    }

    fun extractDirectoryDocumentId(uriString: String, marker: String): String? {
        val encodedId = extractEncodedDirectoryDocumentId(uriString, marker) ?: return null
        return runCatching {
            URLDecoder.decode(encodedId, StandardCharsets.UTF_8.name())
        }.getOrDefault(encodedId)
    }

    fun extractEncodedDirectoryDocumentId(uriString: String, marker: String): String? {
        val markerIndex = uriString.indexOf(marker)
        if (markerIndex < 0) return null
        val startIndex = markerIndex + marker.length
        val endIndex = uriString.indexOfAny(charArrayOf('/', '?', '#'), startIndex)
            .takeIf { it >= 0 }
            ?: uriString.length
        return uriString.substring(startIndex, endIndex).takeIf { it.isNotBlank() }
    }

    fun extractDirectoryAuthority(uriString: String): String {
        val schemeSeparatorIndex = uriString.indexOf("://")
        if (schemeSeparatorIndex < 0) return ""
        val authorityStartIndex = schemeSeparatorIndex + 3
        val authorityEndIndex = uriString.indexOfAny(charArrayOf('/', '?', '#'), authorityStartIndex)
            .takeIf { it >= 0 }
            ?: uriString.length
        return uriString.substring(authorityStartIndex, authorityEndIndex)
    }

}
