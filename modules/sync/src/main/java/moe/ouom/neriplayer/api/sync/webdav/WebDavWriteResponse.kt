package moe.ouom.neriplayer.api.sync.webdav

import moe.ouom.neriplayer.api.sync.http.SyncResponseBodyReader
import moe.ouom.neriplayer.data.model.sync.transport.WebDavWriteResult
import okhttp3.Response

internal object WebDavWriteResponse {
    private val conflictStatuses = setOf(409, 412, 423)

    fun parse(response: Response, content: ByteArray, authFailureMessage: String): WebDavWriteResult {
        if (response.isSuccessful) return WebDavWriteResult(
            WebDavApiClient.calculateFingerprint(content), WebDavConditionalHeaders.extract(response)
        )
        if (response.code == 401 || response.code == 403) throw WebDavAuthException(authFailureMessage)
        val body = SyncResponseBodyReader.readText(response.body)
        val message = "Failed to update file: ${response.code}${errorSuffix(body)}"
        if (response.code in conflictStatuses) throw WebDavContentConflictException(response.code, message)
        throw WebDavApiException(response.code, message)
    }

    private fun errorSuffix(body: String): String = if (body.isBlank()) "" else " - $body"
}
