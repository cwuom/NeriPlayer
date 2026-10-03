package moe.ouom.neriplayer.api.sync.github

import moe.ouom.neriplayer.api.sync.http.SyncResponseBodyReader

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.sync.github/GitHubApiClient
 * Created: 2025/1/7
 */

import com.google.gson.Gson
import moe.ouom.neriplayer.data.model.sync.transport.GitHubRepositoryInfo
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.File

/**
 * Token过期异常
 */
class TokenExpiredException(message: String) : IOException(message)

class GitHubFileNotFoundException(message: String) : IOException(message)

open class GitHubApiException(
    val statusCode: Int,
    message: String
) : IOException(message)

class GitHubContentConflictException(
    statusCode: Int,
    message: String
) : GitHubApiException(statusCode, message)

data class GitHubSyncHead(val branch: String, val sha: String)

/**
 * GitHub API客户端
 * 使用 GitHub API 管理仓库与二进制同步载体
 */
class GitHubApiClient(
    private val token: String,
    private val client: OkHttpClient,
    private val tokenExpiredMessage: String,
    private val apiBase: String = "https://api.github.com",
    private val checkpointDirectory: File? = null
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "GitHubApiClient"
    }

    /** GitHub API请求 - 创建仓库 */
    private data class GitHubCreateRepoRequest(
        val name: String,
        val description: String = "NeriPlayer backup data",
        val private: Boolean = true,
        @SerializedName("auto_init") val autoInit: Boolean = true
    )

    /**
     * 验证Token是否有效
     */
    suspend fun validateToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$apiBase/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = SyncResponseBodyReader.readText(response.body)
                    val user = gson.fromJson(body, Map::class.java)
                    val username = user["login"] as? String ?: "Unknown"
                    return@withContext Result.success(username)
                }
                if (response.code == 401) {
                    return@withContext Result.failure(
                        TokenExpiredException(tokenExpiredMessage)
                    )
                }
                Result.failure(IOException("Token validation failed: ${response.code}"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            NPLogger.e(TAG, "Token validation error", e)
            Result.failure(e)
        }
    }

    /**
     * 创建私有仓库
     */
    suspend fun createRepository(repoName: String): Result<GitHubRepositoryInfo> = withContext(Dispatchers.IO) {
        try {
            val requestBody = GitHubCreateRepoRequest(name = repoName)
            val json = gson.toJson(requestBody)

            val request = Request.Builder()
                .url("$apiBase/user/repos")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = SyncResponseBodyReader.readText(response.body)
                    val repo = gson.fromJson(body, GitHubRepositoryInfo::class.java)
                    return@withContext Result.success(repo)
                }
                val errorBody = SyncResponseBodyReader.readText(response.body)
                Result.failure(IOException("Failed to create repository: ${response.code} - $errorBody"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            NPLogger.e(TAG, "Create repository error", e)
            Result.failure(e)
        }
    }

    /**
     * 检查仓库是否存在
     */
    suspend fun checkRepository(owner: String, repo: String): Result<GitHubRepositoryInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$apiBase/repos/$owner/$repo")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = SyncResponseBodyReader.readText(response.body)
                    val repoInfo = gson.fromJson(body, GitHubRepositoryInfo::class.java)
                    return@withContext Result.success(repoInfo)
                }

                val errorBody = SyncResponseBodyReader.readText(response.body).takeIf { it.isNotBlank() }
                val error = when (response.code) {
                    401 -> TokenExpiredException(tokenExpiredMessage)
                    else -> GitHubApiException(
                        statusCode = response.code,
                        message = "Failed to check repository: ${response.code}${errorBody?.let { " - $it" } ?: ""}"
                    )
                }
                Result.failure(error)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            NPLogger.e(TAG, "Check repository error", e)
            Result.failure(e)
        }
    }

    /** 读取仓库中的同步文件 */
    suspend fun getFileContent(owner: String, repo: String, path: String): Result<Pair<ByteArray, String>> {
        return syncTransport().getFileContent(owner, repo, path, strict = false)
    }

    suspend fun getFileContentStrict(owner: String, repo: String, path: String): Result<Pair<ByteArray, String>> {
        return syncTransport().getFileContent(owner, repo, path, strict = true)
    }

    suspend fun getRepositoryHead(owner: String, repo: String): Result<GitHubSyncHead> =
        syncTransport().getRepositoryHead(owner, repo)

    suspend fun getFileContentAtRef(owner: String, repo: String, path: String, ref: String): Result<ByteArray> =
        syncTransport().getFileContentAtRef(owner, repo, path, ref)

    suspend fun updateFilesContent(
        owner: String,
        repo: String,
        files: Sequence<Pair<String, ByteArray>>,
        expectedHead: GitHubSyncHead,
        message: String = "Update sync archive",
        retainedArchivePaths: Set<String>? = null
    ): Result<String> = syncTransport().updateFilesContent(owner, repo, files, expectedHead, message, retainedArchivePaths)

    /** 上传同步正文为仓库中的实际二进制或 JSON 文件 */
    suspend fun updateFileContent(
        owner: String,
        repo: String,
        content: ByteArray,
        sha: String? = null,
        path: String,
        message: String = "Update backup data",
        branch: String? = null
    ): Result<String> {
        return syncTransport().updateFileContent(owner, repo, content, sha, path, message, branch)
    }

    private fun syncTransport(): GitHubRepositorySyncTransport {
        return GitHubRepositorySyncTransport(client, token, apiBase, tokenExpiredMessage, checkpointDirectory)
    }
}
