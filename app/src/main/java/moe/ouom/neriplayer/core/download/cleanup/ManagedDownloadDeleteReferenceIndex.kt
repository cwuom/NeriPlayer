package moe.ouom.neriplayer.core.download.cleanup

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** 引用别名只能映射到已枚举文档，同名文件和 URI 路径前缀不提供归属证据 */
internal class ManagedDownloadDeleteReferenceIndex(
    references: Collection<String>,
    owners: Map<String, Set<String>> = emptyMap()
) {
    private val byIdentity = references.associateBy(::managedDeleteReferenceIdentity)
    val ownersByReference: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        owners.forEach { (reference, audioNames) ->
            resolve(reference)?.let { getOrPut(it, ::linkedSetOf).addAll(audioNames) }
        }
    }

    fun resolve(reference: String?): String? = reference?.let { byIdentity[managedDeleteReferenceIdentity(it)] }
}

private data class ManagedReferenceIdentity(val value: String, val authority: String? = null)

private fun managedDeleteReferenceIdentity(reference: String): ManagedReferenceIdentity {
    val rawIdentity = ManagedReferenceIdentity(reference)
    return runCatching {
        val uri = URI(reference)
        if (uri.scheme.equals("file", ignoreCase = true)) return@runCatching ManagedReferenceIdentity(File(uri).absolutePath)
        if (!uri.scheme.equals("content", ignoreCase = true)) return@runCatching rawIdentity
        val authority = uri.rawAuthority?.lowercase(Locale.ROOT) ?: return@runCatching rawIdentity
        val segments = uri.rawPath.orEmpty().split('/')
        val encodedId = when {
            segments.size == 3 && segments[1] == "document" -> segments[2]
            segments.size == 5 && segments[1] == "tree" && segments[3] == "document" -> segments[4]
            else -> return@runCatching rawIdentity
        }
        if (encodedId.isEmpty()) return@runCatching rawIdentity
        val documentId = URLDecoder.decode(encodedId.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        ManagedReferenceIdentity(documentId, authority)
    }.getOrDefault(rawIdentity)
}
