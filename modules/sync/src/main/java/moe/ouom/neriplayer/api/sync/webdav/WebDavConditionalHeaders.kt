package moe.ouom.neriplayer.api.sync.webdav

import moe.ouom.neriplayer.data.model.sync.transport.WebDavConcurrencyToken
import okhttp3.Request
import okhttp3.Response

internal object WebDavConditionalHeaders {
    fun validate(version: WebDavConcurrencyToken?, createOnly: Boolean, unconditional: Boolean) {
        if (createOnly || unconditional) return
        if (version != null && !version.hasConditionToken()) {
            throw WebDavMissingConcurrencyTokenException("WebDAV server does not expose ETag or Last-Modified for conditional sync")
        }
    }

    fun apply(builder: Request.Builder, version: WebDavConcurrencyToken?, createOnly: Boolean) {
        if (createOnly) {
            builder.header("If-None-Match", "*")
            return
        }
        if (version == null) return
        val etag = version.etag
        if (!etag.isNullOrBlank()) {
            builder.header("If-Match", etag)
            return
        }
        val lastModified = version.lastModified
        if (lastModified != null) builder.header("If-Unmodified-Since", lastModified)
    }

    fun extract(response: Response): WebDavConcurrencyToken = WebDavConcurrencyToken(
        etag = strongETag(response.header("ETag")),
        lastModified = normalizedHeader(response.header("Last-Modified"))
    )

    private fun strongETag(value: String?): String? = normalizedHeader(value)?.takeUnless { it.startsWith("W/") }
    private fun normalizedHeader(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)
}
