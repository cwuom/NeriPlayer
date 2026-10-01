package moe.ouom.neriplayer.api.sync.webdav

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.api.sync.http.SyncResponseBodyReader
import moe.ouom.neriplayer.api.sync.http.syncTransportResult
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.transport.WebDavConcurrencyToken
import moe.ouom.neriplayer.data.model.sync.transport.WebDavRemoteFileSnapshot
import moe.ouom.neriplayer.data.model.sync.transport.WebDavWriteResult
import java.security.MessageDigest

class WebDavAuthException(message: String) : IOException(message)

class WebDavFileNotFoundException(message: String) : IOException(message)

class WebDavDirectoryNotFoundException(message: String) : IOException(message)

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
                    response.code == 404 -> directoryProbe.requireExists(remoteUrl)
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

    fun getFileContentStrict(remoteUrl: String): Result<WebDavRemoteFileSnapshot> {
        return syncTransportResult {
            val request = Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = SyncResponseBodyReader.read(response.body)
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
                        directoryProbe.requireExists(remoteUrl)
                        throw WebDavFileNotFoundException("Remote backup file not found: $remoteUrl")
                    }

                    else -> {
                        val errorBody = SyncResponseBodyReader.readText(response.body)
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
    ): Result<WebDavWriteResult> {
        return syncTransportResult {
            WebDavConditionalHeaders.validate(expectedVersion, createOnly, allowUnconditionalWrite)

            val requestBuilder = Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader)

            WebDavConditionalHeaders.apply(requestBuilder, expectedVersion, createOnly)

            val request = Request.Builder()
                .url(remoteUrl)
                .headers(requestBuilder.build().headers)
                .put(content.toRequestBody(mediaType.toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 404 || response.code == 409) {
                    directoryProbe.requireExists(remoteUrl)
                }
                WebDavWriteResponse.parse(response, content, authFailureMessage, accessDeniedMessage)
            }
        }.onFailure {
            NPLogger.e(TAG, "Update WebDAV file content failed", it)
        }
    }

}
