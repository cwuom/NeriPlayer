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
