package moe.ouom.neriplayer.api.sync.webdav

import moe.ouom.neriplayer.api.sync.http.SyncResponseBodyReader
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class WebDavDirectoryProbe(
    private val client: OkHttpClient,
    private val authorizationHeader: String,
    private val authFailureMessage: String,
    private val directoryMissingMessage: String,
    private val accessDeniedMessage: String
) {
    fun requireExists(remoteUrl: String, lease: WebDavArchiveLease? = null) {
        val request = directoryRequest(remoteUrl)
        val parse: (Response, () -> Unit) -> Unit = { response, _ -> parseDirectoryResponse(response) }
        if (lease != null) lease.execute(request, parse)
        else client.newCall(request.build()).execute().use { parse(it) {} }
    }

    suspend fun requireExistsCancellable(remoteUrl: String, lease: WebDavArchiveLease? = null) {
        val request = directoryRequest(remoteUrl)
        val parse: (Response, () -> Unit) -> Unit = { response, _ -> parseDirectoryResponse(response) }
        if (lease != null) lease.executeCancellable(request, parse)
        else executeWebDavArchiveRead(client.newCall(request.build()), parse)
    }

    private fun directoryRequest(remoteUrl: String): Request.Builder {
        val fileUrl = remoteUrl.toHttpUrl()
        val directoryUrl = fileUrl.newBuilder()
            .removePathSegment(fileUrl.pathSegments.lastIndex)
            .addPathSegment("")
            .build()
        // 文件 404 也可能来自父目录缺失，确认目录后才能进入首次上传
        return Request.Builder()
            .url(directoryUrl)
            .header("Authorization", authorizationHeader)
            .header("Depth", "0")
            .method("PROPFIND", "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
                .toRequestBody("application/xml; charset=utf-8".toMediaType()))
    }

    private fun parseDirectoryResponse(response: Response) {
        val statusCode = WebDavDirectoryResponse.statusCode(response)
        when {
            statusCode in 200..299 -> Unit
            statusCode == 401 -> throw WebDavAuthException(authFailureMessage)
            statusCode == 403 -> throw WebDavAccessDeniedException(accessDeniedMessage)
            statusCode == 404 -> throw WebDavDirectoryNotFoundException(directoryMissingMessage)
            else -> {
                val body = if (response.code == 207) "" else SyncResponseBodyReader.readText(response.body)
                throw WebDavApiException(statusCode, "Failed to check WebDAV directory: $statusCode - $body")
            }
        }
    }
}
