package moe.ouom.neriplayer.core.download.storage.directory

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object ManagedDownloadDirectoryIdentity {
    fun normalizeDirectoryUri(uriString: String?): String? {
        return uriString?.trim()?.takeIf(String::isNotBlank)
    }

    fun normalizeConfiguredDirectoryUri(uriString: String?): String? {
        val normalized = normalizeDirectoryUri(uriString)
            ?.substringBefore('#')
            ?.substringBefore('?')
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val authority = extractDirectoryAuthority(normalized).takeIf(String::isNotBlank) ?: return normalized
        extractEncodedDirectoryDocumentId(normalized, "/tree/")
            ?.let { encodedDocumentId ->
                return "content://$authority/tree/$encodedDocumentId"
            }
        extractEncodedDirectoryDocumentId(normalized, "/document/")
            ?.let { encodedDocumentId ->
                return "content://$authority/tree/$encodedDocumentId"
            }
        return normalized
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

    fun isDocumentReferenceUnderManagedTree(reference: String, managedTreeUri: String): Boolean {
        val referenceAuthority = extractDirectoryAuthority(reference).takeIf(String::isNotBlank) ?: return false
        val rootAuthority = extractDirectoryAuthority(managedTreeUri).takeIf(String::isNotBlank) ?: return false
        if (referenceAuthority != rootAuthority) {
            return false
        }
        val rootDocumentId = extractDirectoryDocumentId(managedTreeUri, "/tree/")
            ?: extractDirectoryDocumentId(managedTreeUri, "/document/")
            ?: return false
        val referenceDocumentId = extractDirectoryDocumentId(reference, "/document/")
            ?: return false
        return isDocumentIdInsideManagedRoot(
            documentId = referenceDocumentId,
            rootDocumentId = rootDocumentId
        )
    }

    fun isDocumentIdInsideManagedRoot(documentId: String, rootDocumentId: String): Boolean {
        if (documentId == rootDocumentId) {
            return false
        }
        if (rootDocumentId.endsWith(":")) {
            return documentId.startsWith(rootDocumentId)
        }
        val prefix = if (rootDocumentId.endsWith("/")) rootDocumentId else "$rootDocumentId/"
        return documentId.startsWith(prefix)
    }
}
