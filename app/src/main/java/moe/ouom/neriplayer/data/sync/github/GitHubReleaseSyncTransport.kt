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
import java.util.UUID

internal class GitHubReleaseSyncTransport(
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
            val previousManifest = readManifestAtRef(owner, repo, expectedHead)
            val release = getOrCreateSyncRelease(owner, repo, targetBranch)
            val assetName = buildAssetName(path, content)
            val asset = uploadReleaseAsset(release, assetName, content)
            try {
                val manifest = GitHubReleaseSyncManifest.create(asset.id, asset.name, content)
                val commitSha = commitManifest(
                    owner = owner,
                    repo = repo,
                    branch = targetBranch,
                    expectedHead = expectedHead,
                    manifest = manifest,
                    message = message
                )
                previousManifest
                    ?.takeIf { it.assetId != asset.id }
                    ?.let { deleteReleaseAssetSafely(owner, repo, it.assetId, "superseded") }
                commitSha
            } catch (error: Throwable) {
                deleteReleaseAssetSafely(owner, repo, asset.id, "abandoned")
                throw error
            }
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
        val manifest = readManifestAtRef(owner, repo, head)
        if (manifest != null) {
            val content = downloadReleaseAsset(owner, repo, manifest)
            return content to head
        }

        val legacyContent = getRawFileAtRef(owner, repo, path, head)
        if (legacyContent != null) {
            return legacyContent to head
        }
        if (strict) {
            throw GitHubFileNotFoundException("Remote backup file not found: $path")
        }
        return ByteArray(0) to ""
    }

    private fun readManifestAtRef(
        owner: String,
        repo: String,
        ref: String
    ): GitHubReleaseSyncManifest? {
        val manifestBytes = getRawFileAtRef(owner, repo, MANIFEST_PATH, ref) ?: return null
        return GitHubReleaseSyncManifest.parse(manifestBytes.toString(Charsets.UTF_8), gson)
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
            val body = response.body?.string().orEmpty()
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
            val body = response.body?.string().orEmpty()
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

    private fun downloadReleaseAsset(
        owner: String,
        repo: String,
        manifest: GitHubReleaseSyncManifest
    ): ByteArray {
        val request = authenticatedRequest(
            endpoint("repos/$owner/$repo/releases/assets/${manifest.assetId}")
        )
            .header("Accept", "application/octet-stream")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "download sync asset")
            }
            val content = readBoundedBytes(response, "download sync asset")
            require(content.size.toLong() == manifest.contentSize) {
                "GitHub sync asset size does not match its manifest"
            }
            require(GitHubReleaseSyncManifest.sha256(content) == manifest.contentSha256) {
                "GitHub sync asset checksum does not match its manifest"
            }
            content
        }
    }

    private fun getOrCreateSyncRelease(
        owner: String,
        repo: String,
        branch: String
    ): ReleaseInfo {
        getReleaseByTag(owner, repo)?.let { return it }

        val requestBody = JSONObject().apply {
            put("tag_name", RELEASE_TAG)
            put("target_commitish", branch)
            put("name", "NeriPlayer sync storage")
            put("draft", true)
            put("prerelease", false)
        }.toString()
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/releases"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return@use parseRelease(response.body?.string().orEmpty())
            }
            val status = response.code
            val body = response.body?.string().orEmpty()
            if (status == 422) {
                return@use getReleaseByTag(owner, repo)
                    ?: throw GitHubApiException(status, "Failed to create sync release: ${errorMessage(body)}")
            }
            throwForResponse(status, body, "create sync release")
        }
    }

    private fun getReleaseByTag(owner: String, repo: String): ReleaseInfo? {
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/releases/tags/$RELEASE_TAG"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> parseRelease(response.body?.string().orEmpty())
                else -> throwForResponse(response, "read sync release")
            }
        }
    }

    private fun uploadReleaseAsset(
        release: ReleaseInfo,
        assetName: String,
        content: ByteArray
    ): ReleaseAssetInfo {
        val uploadUrl = release.uploadUrl.substringBefore('{')
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("name", assetName)
            .build()
        val request = authenticatedRequest(uploadUrl.toString())
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .post(content.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "upload sync asset")
            }
            val asset = parseObject(response.body?.string().orEmpty(), "release asset")
            val id = asset.requiredLong("id")
            val name = asset.requiredString("name")
            val size = asset.requiredLong("size")
            require(size == content.size.toLong()) { "GitHub sync asset size mismatch after upload" }
            ReleaseAssetInfo(id = id, name = name)
        }
    }

    private fun deleteReleaseAssetSafely(
        owner: String,
        repo: String,
        assetId: Long,
        reason: String
    ) {
        runCatching {
            deleteReleaseAsset(owner, repo, assetId)
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Failed to delete $reason GitHub sync asset id=$assetId",
                error
            )
        }
    }

    private fun deleteReleaseAsset(owner: String, repo: String, assetId: Long) {
        val request = authenticatedRequest(
            endpoint("repos/$owner/$repo/releases/assets/$assetId")
        )
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful || response.code == 404 -> Unit
                else -> throwForResponse(response, "delete sync asset")
            }
        }
    }

    private fun commitManifest(
        owner: String,
        repo: String,
        branch: String,
        expectedHead: String,
        manifest: GitHubReleaseSyncManifest,
        message: String
    ): String {
        val treeSha = getCommitTree(owner, repo, expectedHead)
        val manifestBlobSha = createUtf8Blob(owner, repo, gson.toJson(manifest))
        val updatedTreeSha = createManifestTree(owner, repo, treeSha, manifestBlobSha)
        val commitSha = createManifestCommit(owner, repo, updatedTreeSha, expectedHead, message)
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
            parseObject(response.body?.string().orEmpty(), "sync commit")
                .getAsJsonObject("tree")
                ?.requiredString("sha")
                ?: throw IOException("GitHub sync commit has no tree SHA")
        }
    }

    private fun createUtf8Blob(owner: String, repo: String, content: String): String {
        val requestBody = JSONObject().apply {
            put("content", content)
            put("encoding", "utf-8")
        }.toString()
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/git/blobs"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "create sync manifest blob")
            }
            parseObject(response.body?.string().orEmpty(), "sync manifest blob").requiredString("sha")
        }
    }

    private fun createManifestTree(
        owner: String,
        repo: String,
        baseTreeSha: String,
        manifestBlobSha: String
    ): String {
        val treeEntry = JSONObject().apply {
            put("path", MANIFEST_PATH)
            put("mode", "100644")
            put("type", "blob")
            put("sha", manifestBlobSha)
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
                throwForResponse(response, "create sync manifest tree")
            }
            parseObject(response.body?.string().orEmpty(), "sync manifest tree").requiredString("sha")
        }
    }

    private fun createManifestCommit(
        owner: String,
        repo: String,
        treeSha: String,
        parentSha: String,
        message: String
    ): String {
        val requestBody = JSONObject().apply {
            put("message", "$message (binary sync manifest)")
            put("tree", treeSha)
            put("parents", JSONArray().put(parentSha))
        }.toString()
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/git/commits"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "create sync manifest commit")
            }
            parseObject(response.body?.string().orEmpty(), "sync manifest commit").requiredString("sha")
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
        val body = response.body ?: throw IOException("GitHub $operation returned no body")
        val declaredSize = body.contentLength()
        require(declaredSize < 0L || declaredSize <= MAX_SYNC_FILE_BYTES) {
            "GitHub sync payload is too large"
        }
        val content = body.bytes()
        require(content.size <= MAX_SYNC_FILE_BYTES) { "GitHub sync payload is too large" }
        return content
    }

    private fun parseRelease(body: String): ReleaseInfo {
        val release = parseObject(body, "sync release")
        release.requiredLong("id")
        return ReleaseInfo(
            uploadUrl = release.requiredString("upload_url")
        )
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

    private fun JsonObject.requiredLong(name: String): Long {
        return runCatching { get(name)?.asLong }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: throw IOException("GitHub response has no valid $name")
    }

    private fun throwForResponse(response: Response, operation: String, detectConflict: Boolean = false): Nothing {
        throwForResponse(response.code, response.body?.string().orEmpty(), operation, detectConflict)
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

    private fun buildAssetName(path: String, content: ByteArray): String {
        val extension = if (path.endsWith(".json")) ".json" else ".bin"
        return "$ASSET_NAME_PREFIX${GitHubReleaseSyncManifest.sha256(content).take(16)}-" +
            "${UUID.randomUUID()}$extension"
    }

    private data class ReleaseInfo(
        val uploadUrl: String
    )

    private data class ReleaseAssetInfo(
        val id: Long,
        val name: String
    )

    private companion object {
        const val TAG = "GitHubReleaseSync"
        const val MANIFEST_PATH = ".neriplayer/sync-manifest-v1.json"
        const val RELEASE_TAG = "neriplayer-sync-storage-v1"
        const val ASSET_NAME_PREFIX = "neriplayer-sync-v1-"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val GITHUB_JSON_MEDIA_TYPE = "application/vnd.github+json"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
        const val GITHUB_RAW_MEDIA_TYPE = "application/vnd.github.raw"
        const val MAX_SYNC_FILE_BYTES = 12 * 1024 * 1024
        const val MAX_ERROR_MESSAGE_LENGTH = 240
    }
}
