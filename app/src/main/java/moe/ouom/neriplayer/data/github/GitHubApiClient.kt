package moe.ouom.neriplayer.data.github

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
 * File: moe.ouom.neriplayer.data.github/GitHubApiClient
 * Created: 2025/1/7
 */

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Token过期异常
 */
class TokenExpiredException(message: String) : IOException(message)

/**
 * GitHub API客户端
 * 使用GitHub Contents API进行文件读写
 */
class GitHubApiClient(private val context: Context, private val token: String) {

    private val client = OkHttpClient.Builder().build()
    private val gson = Gson()

    companion object {
        private const val TAG = "GitHubApiClient"
        private const val GITHUB_API_BASE = "https://api.github.com"
    }

    /** GitHub API响应 - 文件内容 */
    data class GitHubFileResponse(
        val name: String,
        val path: String,
        val sha: String,
        val size: Int,
        val content: String,
        val encoding: String
    )

    /** GitHub API请求 - 创建/更新文件 */
    data class GitHubFileRequest(
        val message: String,
        val content: String,
        val sha: String? = null,
        val branch: String = "main"
    )

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

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
                val user = gson.fromJson(body, Map::class.java)
                val username = user["login"] as? String ?: "Unknown"
                Result.success(username)
            } else if (response.code == 401) {
                // Token过期或无效
                Result.failure(TokenExpiredException(context.getString(R.string.github_token_expired_message)))
            } else {
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

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
                val repo = gson.fromJson(body, GitHubRepoResponse::class.java)
                Result.success(repo)
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
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

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
                val repoInfo = gson.fromJson(body, GitHubRepoResponse::class.java)
                Result.success(repoInfo)
            } else {
                Result.failure(IOException("Repository not found: ${response.code}"))
            }
        } catch (e: Exception) {
            NPLogger.e(TAG, "Check repository error", e)
            Result.failure(e)
        }
    }

    /**
     * 读取文件内容
     */
    suspend fun getFileContent(owner: String, repo: String, path: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$GITHUB_API_BASE/repos/$owner/$repo/contents/$path")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
                val fileResponse = gson.fromJson(body, GitHubFileResponse::class.java)

                // 解码Base64内容
                val decodedContent = String(Base64.decode(fileResponse.content.replace("\n", ""), Base64.DEFAULT))
                Result.success(Pair(decodedContent, fileResponse.sha))
            } else if (response.code == 404) {
                // 文件不存在
                Result.success(Pair("", ""))
            } else {
                Result.failure(IOException("Failed to get file: ${response.code}"))
            }
        } catch (e: Exception) {
            NPLogger.e(TAG, "Get file content error", e)
            Result.failure(e)
        }
    }

    /**
     * 创建或更新文件
     */
    suspend fun updateFileContent(
        owner: String,
        repo: String,
        content: String,
        sha: String? = null,
        path: String,
        message: String = "Update backup data",
        branch: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 如果没有指定分支，先获取仓库的默认分支
            val targetBranch = branch ?: run {
                val repoResult = checkRepository(owner, repo)
                if (repoResult.isSuccess) {
                    repoResult.getOrNull()?.defaultBranch ?: "main"
                } else {
                    "main"
                }
            }

            // Base64编码内容
            val encodedContent = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)

            // 使用JSONObject构建请求体，确保sha为null或空字符串时不包含该字段
            val jsonObject = org.json.JSONObject().apply {
                put("message", message)
                put("content", encodedContent)
                put("branch", targetBranch)
                if (!sha.isNullOrEmpty()) {
                    put("sha", sha)
                }
            }
            val json = jsonObject.toString()

            val request = Request.Builder()
                .url("$GITHUB_API_BASE/repos/$owner/$repo/contents/$path")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .put(json.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
                val result = gson.fromJson(body, Map::class.java)
                val newSha = (result["content"] as? Map<*, *>)?.get("sha") as? String ?: ""
                Result.success(newSha)
            } else if (response.code == 401) {
                // Token过期或无效
                Result.failure(TokenExpiredException(context.getString(R.string.github_token_expired_message)))
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Result.failure(IOException("Failed to update file: ${response.code} - $errorBody"))
            }
        } catch (e: Exception) {
            NPLogger.e(TAG, "Update file content error", e)
            Result.failure(e)
        }
    }
}
