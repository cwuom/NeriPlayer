package moe.ouom.neriplayer.data.model.sync.transport

data class WebDavConcurrencyToken(
    val etag: String? = null,
    val lastModified: String? = null
) {
    fun hasConditionToken(): Boolean {
        return !etag.isNullOrBlank() || !lastModified.isNullOrBlank()
    }
}

class WebDavRemoteFileSnapshot(
    val content: ByteArray,
    val fingerprint: String,
    val version: WebDavConcurrencyToken
)

data class WebDavWriteResult(
    val fingerprint: String,
    val version: WebDavConcurrencyToken
)

data class WebDavArchiveEntry(val path: String, val etag: String)

object WebDavArchiveObjectPolicy {
    private val ownedPath = Regex("neriplayer-sync-v[34]-[0-9a-f]{64}\\.zst")
    private val strongETag = Regex("\"[!#-~\\u0080-\\u00ff]*\"")
    fun isOwnedPath(path: String): Boolean = ownedPath.matches(path)
    fun isStrongETag(etag: String): Boolean = strongETag.matches(etag)
}
