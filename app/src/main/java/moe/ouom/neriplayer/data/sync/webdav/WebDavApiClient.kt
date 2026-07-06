package moe.ouom.neriplayer.data.sync.webdav

import android.content.Context
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

class WebDavAuthException(message: String) : IOException(message)

class WebDavFileNotFoundException(message: String) : IOException(message)

class WebDavSyncInProgressException(message: String) : IOException(message)

open class WebDavApiException(
    val statusCode: Int,
    message: String
) : IOException(message)

class WebDavContentConflictException(
    statusCode: Int,
    message: String
) : WebDavApiException(statusCode, message)

class WebDavMissingConcurrencyTokenException(message: String) : IOException(message)

class WebDavApiClient(
    context: Context,
    username: String,
    password: String
) {
    private val appContext = context.applicationContext
    private val client: OkHttpClient = AppContainer.sharedOkHttpClient
    private val authorizationHeader = Credentials.basic(username, password)

    companion object {
        private const val TAG = "WebDavApiClient"
        private const val DEFAULT_REMOTE_FILE_NAME = "neriplayer-sync.json"

        fun calculateFingerprint(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
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

    data class ConcurrencyToken(
        val etag: String? = null,
        val lastModified: String? = null
    ) {
        fun hasConditionToken(): Boolean {
            return !etag.isNullOrBlank() || !lastModified.isNullOrBlank()
        }
    }

    data class RemoteFileSnapshot(
        val content: String,
        val fingerprint: String,
        val version: ConcurrencyToken
    )

    data class WriteResult(
        val fingerprint: String,
        val version: ConcurrencyToken
    )

    fun validateConnection(serverUrl: String, basePath: String): Result<Unit> {
        return runCatching {
            val remoteUrl = buildRemoteFileUrl(serverUrl, basePath)
            val request = Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful || response.code == 404 -> Unit
                    response.code == 401 || response.code == 403 -> {
                        throw WebDavAuthException(
                            appContext.getString(R.string.webdav_auth_failed)
                        )
                    }

                    else -> {
                        val errorBody = response.body?.string().orEmpty()
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

    fun getFileContentStrict(remoteUrl: String): Result<RemoteFileSnapshot> {
        return runCatching {
            val request = Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string()
                            ?: throw IOException("Empty response")
                        RemoteFileSnapshot(
                            content = body,
                            fingerprint = calculateFingerprint(body),
                            version = extractConcurrencyToken(response)
                        )
                    }

                    response.code == 401 || response.code == 403 -> {
                        throw WebDavAuthException(
                            appContext.getString(R.string.webdav_auth_failed)
                        )
                    }

                    response.code == 404 -> {
                        throw WebDavFileNotFoundException("Remote backup file not found: $remoteUrl")
                    }

                    else -> {
                        val errorBody = response.body?.string().orEmpty()
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
        content: String,
        expectedVersion: ConcurrencyToken? = null,
        createOnly: Boolean = false
    ): Result<WriteResult> {
        return runCatching {
            if (!createOnly && expectedVersion != null && !expectedVersion.hasConditionToken()) {
                throw WebDavMissingConcurrencyTokenException(
                    "WebDAV server does not expose ETag or Last-Modified for conditional sync"
                )
            }

            val requestBuilder = Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader)

            if (createOnly) {
                requestBuilder.header("If-None-Match", "*")
            } else {
                expectedVersion?.etag?.let { requestBuilder.header("If-Match", it) }
                if (expectedVersion?.etag.isNullOrBlank()) {
                    expectedVersion?.lastModified?.let {
                        requestBuilder.header("If-Unmodified-Since", it)
                    }
                }
            }

            val request = Request.Builder()
                .url(remoteUrl)
                .headers(requestBuilder.build().headers)
                .put(content.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> WriteResult(
                        fingerprint = calculateFingerprint(content),
                        version = extractConcurrencyToken(response)
                    )
                    response.code == 401 || response.code == 403 -> {
                        throw WebDavAuthException(
                            appContext.getString(R.string.webdav_auth_failed)
                        )
                    }

                    response.code == 409 ||
                        response.code == 412 ||
                        response.code == 423 -> {
                        val errorBody = response.body?.string().orEmpty()
                        throw WebDavContentConflictException(
                            statusCode = response.code,
                            message = "Failed to update file: ${response.code}${errorBody.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}"
                        )
                    }

                    else -> {
                        val errorBody = response.body?.string().orEmpty()
                        throw WebDavApiException(
                            response.code,
                            "Failed to update file: ${response.code}${errorBody.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}"
                        )
                    }
                }
            }
        }.onFailure {
            NPLogger.e(TAG, "Update WebDAV file content failed", it)
        }
    }

    private fun extractConcurrencyToken(response: okhttp3.Response): ConcurrencyToken {
        val etag = response.header("ETag")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
        val lastModified = response.header("Last-Modified")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return ConcurrencyToken(
            etag = etag,
            lastModified = lastModified
        )
    }
}
