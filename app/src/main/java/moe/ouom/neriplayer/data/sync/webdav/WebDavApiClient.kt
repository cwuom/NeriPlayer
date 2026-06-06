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

class WebDavApiException(
    val statusCode: Int,
    message: String
) : IOException(message)

class WebDavApiClient(
    private val context: Context,
    username: String,
    password: String
) {
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
                            context.getString(R.string.webdav_auth_failed)
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

    fun getFileContentStrict(remoteUrl: String): Result<Pair<String, String>> {
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
                        body to calculateFingerprint(body)
                    }

                    response.code == 401 || response.code == 403 -> {
                        throw WebDavAuthException(
                            context.getString(R.string.webdav_auth_failed)
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
        content: String
    ): Result<String> {
        return runCatching {
            val request = Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader)
                .put(content.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> calculateFingerprint(content)
                    response.code == 401 || response.code == 403 -> {
                        throw WebDavAuthException(
                            context.getString(R.string.webdav_auth_failed)
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
}
