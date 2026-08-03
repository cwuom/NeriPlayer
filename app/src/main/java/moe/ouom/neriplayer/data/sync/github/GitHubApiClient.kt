package moe.ouom.neriplayer.data.sync.github

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

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

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

class GitHubSyncInProgressException(message: String) : IOException(message)

/**
 * GitHub API客户端
 * 使用 GitHub API 管理仓库与二进制同步载体
 */
@Suppress("unused")
class GitHubApiClient(
    context: Context,
    private val token: String
) {
    private val appContext = context.applicationContext

    private val client: OkHttpClient = AppContainer.sharedOkHttpClient
    private val gson = Gson()

    companion object {
        private const val TAG = "GitHubApiClient"
        private const val GITHUB_API_BASE = "https://api.github.com"
    }

    /** GitHub API响应 - 仓库信息 */
    data class GitHubRepoResponse(
        val id: Long,
        val name: String,
        @SerializedName("full_name") val fullName: String,
        val private: Boolean,
        @SerializedName("default_branch") val defaultBranch: String
    )

    /** GitHub API请求 - 创建仓库 */
    data class GitHubCreateRepoRequest(
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
                .url("$GITHUB_API_BASE/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val user = gson.fromJson(body, Map::class.java)
                    val username = user["login"] as? String ?: "Unknown"
                    return@withContext Result.success(username)
                }
                if (response.code == 401) {
                    return@withContext Result.failure(
                        TokenExpiredException(appContext.getString(R.string.github_token_expired_message))
                    )
                }
                Result.failure(IOException("Token validation failed: ${response.code}"))
            }
        } catch (e: Exception) {
            NPLogger.e(TAG, "Token validation error", e)
            Result.failure(e)
        }
    }

    /**
     * 创建私有仓库
     */
    suspend fun createRepository(repoName: String): Result<GitHubRepoResponse> = withContext(Dispatchers.IO) {
        try {
            val requestBody = GitHubCreateRepoRequest(name = repoName)
            val json = gson.toJson(requestBody)

            val request = Request.Builder()
                .url("$GITHUB_API_BASE/user/repos")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val repo = gson.fromJson(body, GitHubRepoResponse::class.java)
                    return@withContext Result.success(repo)
                }
                val errorBody = response.body.string()
                Result.failure(IOException("Failed to create repository: ${response.code} - $errorBody"))
            }
        } catch (e: Exception) {
            NPLogger.e(TAG, "Create repository error", e)
            Result.failure(e)
        }
    }

    /**
     * 检查仓库是否存在
     */
    suspend fun checkRepository(owner: String, repo: String): Result<GitHubRepoResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$GITHUB_API_BASE/repos/$owner/$repo")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val repoInfo = gson.fromJson(body, GitHubRepoResponse::class.java)
                    return@withContext Result.success(repoInfo)
                }

                val errorBody = response.body.string().takeIf { it.isNotBlank() }
                val error = when (response.code) {
                    401 -> TokenExpiredException(appContext.getString(R.string.github_token_expired_message))
                    else -> GitHubApiException(
                        statusCode = response.code,
                        message = "Failed to check repository: ${response.code}${errorBody?.let { " - $it" } ?: ""}"
                    )
                }
                Result.failure(error)
            }
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
        return GitHubRepositorySyncTransport(appContext, client, token, GITHUB_API_BASE)
    }
}
