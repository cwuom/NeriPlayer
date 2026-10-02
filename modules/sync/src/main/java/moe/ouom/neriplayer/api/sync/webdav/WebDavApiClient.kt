package moe.ouom.neriplayer.api.sync.webdav

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.api.sync.http.SyncResponseBodyReader
import moe.ouom.neriplayer.api.sync.http.SyncFileTransferLimits
import moe.ouom.neriplayer.api.sync.http.syncTransportResult
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.transport.WebDavConcurrencyToken
import moe.ouom.neriplayer.data.model.sync.transport.WebDavRemoteFileSnapshot
import moe.ouom.neriplayer.data.model.sync.transport.WebDavWriteResult
import moe.ouom.neriplayer.data.model.sync.transport.WebDavArchiveEntry
import java.security.MessageDigest

class WebDavAuthException(message: String) : IOException(message)

class WebDavFileNotFoundException(message: String) : IOException(message)

class WebDavDirectoryNotFoundException(message: String) : IOException(message)

class WebDavNotDirectoryException(message: String) : IOException(message)

open class WebDavApiException(
    val statusCode: Int,
    message: String
) : IOException(message)

class WebDavAccessDeniedException(message: String) : WebDavApiException(403, message)

class WebDavContentConflictException(
    statusCode: Int,
    message: String
) : WebDavApiException(statusCode, message)

class WebDavMissingConcurrencyTokenException(message: String) : IOException(message)

class WebDavApiClient(
    username: String,
    password: String,
    private val client: OkHttpClient,
    private val authFailureMessage: String,
    private val directoryMissingMessage: String = "WebDAV sync directory does not exist; create it or check the sync path",
    private val accessDeniedMessage: String = "Cannot access the WebDAV sync directory; check that it exists and that you have access permission"
) {
    private val authorizationHeader = Credentials.basic(username, password)
    private val archiveAccount = username
    private val directoryProbe = WebDavDirectoryProbe(
        client, authorizationHeader, authFailureMessage, directoryMissingMessage, accessDeniedMessage
    )

    companion object {
        private const val TAG = "WebDavApiClient"
        private const val DEFAULT_REMOTE_FILE_NAME = "neriplayer-sync.json"

        fun calculateFingerprint(content: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(content)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun buildRemoteFileUrl(serverUrl: String, basePath: String): String {
            val normalizedServerUrl = serverUrl.trim().trimEnd('/')
            val normalizedBasePath = basePath.trim().trim('/')
            val urlBuilder = normalizedServerUrl.toHttpUrl().newBuilder()
            if (normalizedBasePath.isNotBlank()) {
                normalizedBasePath
                    .split('/')
                    .filter(String::isNotBlank)
                    .forEach(urlBuilder::addPathSegment)
            }
            urlBuilder.addPathSegment(DEFAULT_REMOTE_FILE_NAME)
            return urlBuilder.build().toString()
        }

        fun buildSiblingFileUrl(remoteUrl: String, fileName: String): String {
            require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains("..")) {
                "Invalid sync archive file name"
            }
            val url = remoteUrl.toHttpUrl()
            return url.newBuilder().setPathSegment(url.pathSize - 1, fileName).build().toString()
        }
    }

    fun validateConnection(serverUrl: String, basePath: String): Result<Unit> {
        return syncTransportResult {
            val remoteUrl = buildRemoteFileUrl(serverUrl, basePath)
            val request = Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Unit
                    response.code == 404 -> {
                        response.close()
                        directoryProbe.requireExists(remoteUrl)
                    }
                    response.code == 401 -> {
                        throw WebDavAuthException(
                            authFailureMessage
                        )
                    }
                    response.code == 403 -> throw WebDavAccessDeniedException(accessDeniedMessage)

                    else -> {
                        val errorBody = SyncResponseBodyReader.readText(response.body)
                        throw WebDavApiException(
                            response.code,
                            "WebDAV validate failed: ${response.code}${errorBody.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}"
                        )
                    }
                }
            }
        }.onFailure {
            NPLogger.e(TAG, "Validate WebDAV connection failed", it)
        }
    }

    fun acquireArchiveLease(remoteUrl: String, knownSupported: Boolean, checkActive: () -> Unit): Result<WebDavArchiveLease?> =
        syncTransportResult { WebDavArchiveLease.acquire(remoteUrl, client, authorizationHeader, authFailureMessage, knownSupported, checkActive) }

    fun archiveMaintenanceScope(manifestUrl: String): String {
        val resource = manifestUrl.toHttpUrl().newBuilder().fragment(null).build()
        return calculateFingerprint((resource.toString() + "\n" + archiveAccount).toByteArray(Charsets.UTF_8))
    }

    fun getFileContentStrict(remoteUrl: String): Result<WebDavRemoteFileSnapshot> = getFileContent(remoteUrl, null)

    fun getFileContentLocked(remoteUrl: String, lease: WebDavArchiveLease): Result<WebDavRemoteFileSnapshot> =
        getFileContent(remoteUrl, lease)

    private fun getFileContent(remoteUrl: String, lease: WebDavArchiveLease?): Result<WebDavRemoteFileSnapshot> {
        return syncTransportResult {
            val request = Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader)
                .get()

            execute(request, lease) { response, cancel ->
                when {
                    response.isSuccessful -> {
                        val body = try {
                            SyncResponseBodyReader.read(response.body, SyncFileTransferLimits.responseBudget(request.build().url.encodedPath), cancel)
                        } catch (error: IOException) {
                            // 先切断连接，避免关闭响应时为复用连接继续读取超限正文
                            cancel()
                            throw error
                        }
                        WebDavRemoteFileSnapshot(
                            content = body,
                            fingerprint = calculateFingerprint(body),
                            version = WebDavConditionalHeaders.extract(response)
                        )
                    }

                    response.code == 401 -> {
                        throw WebDavAuthException(
                            authFailureMessage
                        )
                    }
                    response.code == 403 -> throw WebDavAccessDeniedException(accessDeniedMessage)

                    response.code == 404 -> {
                        response.close()
                        directoryProbe.requireExists(remoteUrl, lease)
                        throw WebDavFileNotFoundException("Remote backup file not found: $remoteUrl")
                    }

                    else -> {
                        val errorBody = SyncResponseBodyReader.read(response.body,
                            SyncFileTransferLimits.responseBudget(request.build().url.encodedPath), cancel).toString(Charsets.UTF_8)
                        throw WebDavApiException(
                            response.code,
                            "Failed to get file: ${response.code}${errorBody.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}"
                        )
                    }
                }
            }
        }.onFailure {
            NPLogger.e(TAG, "Get WebDAV file content failed", it)
        }
    }

    fun updateFileContent(
        remoteUrl: String,
        content: ByteArray,
        mediaType: String = "application/octet-stream",
        expectedVersion: WebDavConcurrencyToken? = null,
        createOnly: Boolean = false,
        allowUnconditionalWrite: Boolean = false
    ): Result<WebDavWriteResult> = updateFileContentInternal(remoteUrl, content, mediaType, expectedVersion, createOnly, allowUnconditionalWrite, null)

    fun updateFileContentLocked(remoteUrl: String, content: ByteArray, lease: WebDavArchiveLease,
        expectedVersion: WebDavConcurrencyToken? = null, createOnly: Boolean = false): Result<WebDavWriteResult> =
        updateFileContentInternal(remoteUrl, content, "application/octet-stream", expectedVersion, createOnly, false, lease)

    private fun updateFileContentInternal(remoteUrl: String, content: ByteArray, mediaType: String,
        expectedVersion: WebDavConcurrencyToken?, createOnly: Boolean, allowUnconditionalWrite: Boolean,
        lease: WebDavArchiveLease?): Result<WebDavWriteResult> {
        return syncTransportResult {
            WebDavConditionalHeaders.validate(expectedVersion, createOnly, allowUnconditionalWrite)

            val requestBuilder = Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader)

            WebDavConditionalHeaders.apply(requestBuilder, expectedVersion, createOnly)

            val request = requestBuilder.put(content.toRequestBody(mediaType.toMediaType()))

            val (statusCode, writeResult) = execute(request, lease) { response, _ ->
                response.code to syncTransportResult {
                    WebDavWriteResponse.parse(response, content, authFailureMessage, accessDeniedMessage)
                }
            }
            if (statusCode == 404 || statusCode == 409) directoryProbe.requireExists(remoteUrl, lease)
            writeResult.getOrThrow()
        }.onFailure {
            NPLogger.e(TAG, "Update WebDAV file content failed", it)
        }
    }

    fun listArchiveFiles(lease: WebDavArchiveLease): Result<List<WebDavArchiveEntry>> = syncTransportResult {
        val request = Request.Builder().url(lease.root).header("Authorization", authorizationHeader).header("Depth", "1")
            .method("PROPFIND", "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getetag/></d:prop></d:propfind>"
                .toRequestBody("application/xml; charset=utf-8".toMediaType()))
        execute(request, lease) { response, _ -> WebDavArchiveListing.read(response, lease.root) }
    }

    fun deleteArchiveObject(entry: WebDavArchiveEntry, lease: WebDavArchiveLease): Result<Unit> = syncTransportResult {
        require(WebDavArchiveListing.isOwnedPath(entry.path) && WebDavArchiveListing.isStrongETag(entry.etag)) { "Invalid WebDAV archive deletion" }
        val url = lease.root.newBuilder().addPathSegment(entry.path).build()
        val request = Request.Builder().url(url).header("Authorization", authorizationHeader).header("If-Match", entry.etag).delete()
        execute(request, lease) { response, _ ->
            when (response.code) {
                200, 204, 404 -> Unit
                401 -> throw WebDavAuthException(authFailureMessage)
                403 -> throw WebDavAccessDeniedException(accessDeniedMessage)
                else -> throw WebDavApiException(response.code, "Failed to delete unreferenced WebDAV archive object")
            }
        }
    }

    private fun <T> execute(request: Request.Builder, lease: WebDavArchiveLease?, parse: (Response, () -> Unit) -> T): T {
        if (lease != null) return lease.execute(request, parse)
        val call = client.newCall(request.build())
        return call.execute().use { parse(it, call::cancel) }
    }

}
