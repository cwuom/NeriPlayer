package moe.ouom.neriplayer.data.sync.github

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/** 直接读写仓库中的同步文件 */
internal class GitHubRepositorySyncTransport(
    context: Context,
    private val client: OkHttpClient,
    private val token: String,
    private val apiBase: String
) {
    private val appContext = context.applicationContext
    private val gson = Gson()

    suspend fun getFileContent(
        owner: String,
        repo: String,
        path: String,
        strict: Boolean
    ): Result<Pair<ByteArray, String>> = withContext(Dispatchers.IO) {
        runCatching {
            readRemoteContent(owner, repo, path, strict)
        }.onFailure {
            NPLogger.e(TAG, "Get GitHub sync content failed", it)
        }
    }

    suspend fun updateFileContent(
        owner: String,
        repo: String,
        content: ByteArray,
        remoteHead: String?,
        path: String,
        message: String,
        branch: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(content.isNotEmpty()) { "Refusing to upload an empty sync payload" }
            require(content.size <= MAX_SYNC_FILE_BYTES) { "Sync payload is too large" }

            val targetBranch = branch ?: getDefaultBranch(owner, repo)
            val expectedHead = remoteHead?.takeIf(String::isNotBlank)
                ?: getBranchHead(owner, repo, targetBranch)
            commitSyncFile(
                owner = owner,
                repo = repo,
                branch = targetBranch,
                expectedHead = expectedHead,
                path = path,
                content = content,
                message = message
            )
        }.onFailure {
            NPLogger.e(TAG, "Upload GitHub sync content failed", it)
        }
    }

    private fun readRemoteContent(
        owner: String,
        repo: String,
        path: String,
        strict: Boolean
    ): Pair<ByteArray, String> {
        val branch = getDefaultBranch(owner, repo)
        val head = getBranchHead(owner, repo, branch)
        val directContent = getRawFileAtRef(owner, repo, path, head)
        if (directContent != null) {
            return directContent to head
        }

        if (strict) {
            throw GitHubFileNotFoundException("Remote backup file not found: $path")
        }
        return ByteArray(0) to ""
    }

    private fun getDefaultBranch(owner: String, repo: String): String {
        val request = authenticatedRequest(endpoint("repos/$owner/$repo"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "resolve repository")
            }
            val body = response.body.string()
            parseObject(body, "repository").requiredString("default_branch").ifBlank { "main" }
        }
    }

    private fun getBranchHead(owner: String, repo: String, branch: String): String {
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/git/ref/heads/$branch"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "read sync branch")
            }
            val body = response.body.string()
            parseObject(body, "branch reference")
                .getAsJsonObject("object")
                ?.requiredString("sha")
                ?: throw IOException("GitHub branch reference has no SHA")
        }
    }

    private fun getRawFileAtRef(
        owner: String,
        repo: String,
        path: String,
        ref: String
    ): ByteArray? {
        val url = endpoint("repos/$owner/$repo/contents/$path")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("ref", ref)
            .build()
        val request = authenticatedRequest(url.toString())
            .header("Accept", GITHUB_RAW_MEDIA_TYPE)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> readBoundedBytes(response, "read sync file")
                else -> throwForResponse(response, "read sync file")
            }
        }
    }

    private fun commitSyncFile(
        owner: String,
        repo: String,
        branch: String,
        expectedHead: String,
        path: String,
        content: ByteArray,
        message: String
    ): String {
        val treeSha = getCommitTree(owner, repo, expectedHead)
        val contentBlobSha = createBinaryBlob(owner, repo, content)
        val updatedTreeSha = createSyncFileTree(owner, repo, treeSha, path, contentBlobSha)
        val commitSha = createSyncFileCommit(owner, repo, updatedTreeSha, expectedHead, message)
        updateBranchRef(owner, repo, branch, commitSha)
        return commitSha
    }

    private fun getCommitTree(owner: String, repo: String, commitSha: String): String {
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/git/commits/$commitSha"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "read sync commit")
            }
            parseObject(response.body.string(), "sync commit")
                .getAsJsonObject("tree")
                ?.requiredString("sha")
                ?: throw IOException("GitHub sync commit has no tree SHA")
        }
    }

    private fun createBinaryBlob(owner: String, repo: String, content: ByteArray): String {
        val requestBody = JSONObject().apply {
            // 服务端会解码 API 信封，并在 Git blob 中保存原始字节
            put("content", Base64.getEncoder().encodeToString(content))
            put("encoding", "base64")
        }.toString()
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/git/blobs"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "create sync binary blob")
            }
            parseObject(response.body.string(), "sync binary blob").requiredString("sha")
        }
    }

    private fun createSyncFileTree(
        owner: String,
        repo: String,
        baseTreeSha: String,
        path: String,
        contentBlobSha: String
    ): String {
        val treeEntry = JSONObject().apply {
            put("path", path)
            put("mode", "100644")
            put("type", "blob")
            put("sha", contentBlobSha)
        }
        val requestBody = JSONObject().apply {
            put("base_tree", baseTreeSha)
            put("tree", JSONArray().put(treeEntry))
        }.toString()
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/git/trees"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "create sync binary tree")
            }
            parseObject(response.body.string(), "sync binary tree").requiredString("sha")
        }
    }

    private fun createSyncFileCommit(
        owner: String,
        repo: String,
        treeSha: String,
        parentSha: String,
        message: String
    ): String {
        val requestBody = JSONObject().apply {
            put("message", message)
            put("tree", treeSha)
            put("parents", JSONArray().put(parentSha))
        }.toString()
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/git/commits"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "create sync binary commit")
            }
            parseObject(response.body.string(), "sync binary commit").requiredString("sha")
        }
    }

    private fun updateBranchRef(owner: String, repo: String, branch: String, commitSha: String) {
        val requestBody = JSONObject().apply {
            put("sha", commitSha)
            put("force", false)
        }.toString()
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/git/refs/heads/$branch"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .patch(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "update sync branch", detectConflict = true)
            }
        }
    }

    private fun authenticatedRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
    }

    private fun endpoint(path: String): String = "$apiBase/${path.trimStart('/')}"

    private fun readBoundedBytes(response: Response, operation: String): ByteArray {
        val body = response.body
        val declaredSize = body.contentLength()
        require(declaredSize < 0L || declaredSize <= MAX_SYNC_FILE_BYTES) {
            "GitHub sync payload is too large"
        }
        val content = body.bytes()
        require(content.size <= MAX_SYNC_FILE_BYTES) { "GitHub sync payload is too large" }
        return content
    }

    private fun parseObject(body: String, subject: String): JsonObject {
        return runCatching { gson.fromJson(body, JsonObject::class.java) }
            .getOrNull()
            ?: throw IOException("Invalid GitHub $subject response")
    }

    private fun JsonObject.requiredString(name: String): String {
        return get(name)
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("GitHub response has no $name")
    }

    private fun throwForResponse(response: Response, operation: String, detectConflict: Boolean = false): Nothing {
        throwForResponse(response.code, response.body.string(), operation, detectConflict)
    }

    private fun throwForResponse(
        statusCode: Int,
        body: String,
        operation: String,
        detectConflict: Boolean = false
    ): Nothing {
        if (statusCode == 401) {
            throw TokenExpiredException(appContext.getString(R.string.github_token_expired_message))
        }
        val message = "$operation failed: $statusCode - ${errorMessage(body)}"
        if (
            detectConflict &&
            (statusCode == 409 || (statusCode == 422 && body.contains("reference", ignoreCase = true)))
        ) {
            throw GitHubContentConflictException(statusCode, message)
        }
        throw GitHubApiException(statusCode, message)
    }

    private fun errorMessage(body: String): String {
        val message = runCatching {
            gson.fromJson(body, JsonObject::class.java)?.get("message")?.asString
        }.getOrNull().orEmpty().ifBlank { body.trim() }
        return message.take(MAX_ERROR_MESSAGE_LENGTH).ifBlank { "Unknown error" }
    }

    private companion object {
        const val TAG = "GitHubRepositorySync"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val GITHUB_JSON_MEDIA_TYPE = "application/vnd.github+json"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val GITHUB_RAW_MEDIA_TYPE = "application/vnd.github.raw"
        const val MAX_SYNC_FILE_BYTES = 12 * 1024 * 1024
        const val MAX_ERROR_MESSAGE_LENGTH = 240
    }
}
