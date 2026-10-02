package moe.ouom.neriplayer.api.sync.github

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.api.sync.http.SyncResponseBodyReader
import moe.ouom.neriplayer.api.sync.http.SyncFileTransferLimits
import moe.ouom.neriplayer.api.sync.http.syncTransportResult
import moe.ouom.neriplayer.api.sync.http.suspendSyncTransportResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.util.Base64
import kotlin.coroutines.coroutineContext

/** 直接读写仓库中的同步文件 */
internal class GitHubRepositorySyncTransport(
    private val client: OkHttpClient,
    private val token: String,
    private val apiBase: String,
    private val tokenExpiredMessage: String,
    private val checkpointDirectory: File? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()

    suspend fun getRepositoryHead(owner: String, repo: String): Result<GitHubSyncHead> =
        withContext(Dispatchers.IO) {
            syncTransportResult {
                val branch = getDefaultBranch(owner, repo)
                GitHubSyncHead(branch, getBranchHead(owner, repo, branch))
            }
        }

    suspend fun getFileContentAtRef(
        owner: String,
        repo: String,
        path: String,
        ref: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        syncTransportResult {
            getRawFileAtRef(owner, repo, path, ref)
                ?: throw GitHubFileNotFoundException("Remote backup file not found: $path")
        }
    }

    suspend fun updateFilesContent(
        owner: String,
        repo: String,
        files: Sequence<Pair<String, ByteArray>>,
        expectedHead: GitHubSyncHead,
        message: String,
        retainedArchivePaths: Set<String>? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        suspendSyncTransportResult {
            validateRetainedPaths(retainedArchivePaths)
            val publication = ArchivePublication(owner, repo, expectedHead)
            val treeSha = stageArchiveTree(owner, repo, files, expectedHead.sha, retainedArchivePaths)
            coroutineContext.ensureActive()
            val commitSha = createSyncFileCommit(owner, repo, treeSha, expectedHead.sha, message)
            coroutineContext.ensureActive()
            publication.publish(commitSha)
            runCatching { checkpoint(owner, repo)?.published() }
            commitSha
        }
    }

    private fun validateRetainedPaths(paths: Set<String>?) {
        paths?.forEach { require(GitHubArchiveTreeReader.isOwnedObjectPath(it)) { "Invalid retained sync object path" } }
    }

    private suspend fun stageArchiveTree(
        owner: String,
        repo: String,
        files: Sequence<Pair<String, ByteArray>>,
        expectedHead: String,
        retainedPaths: Set<String>?
    ): String {
        coroutineContext.ensureActive()
        val baseTree = getCommitTree(owner, repo, expectedHead)
        val existingPaths = if (retainedPaths == null) emptySet() else readArchiveTreePaths(owner, repo, baseTree)
        val closure = ArchiveClosure(retainedPaths, existingPaths)
        val staging = ArchiveTreeStaging(owner, repo, baseTree)
        for ((path, content) in files) {
            coroutineContext.ensureActive()
            closure.uploaded(path)
            staging.append(path, content)
        }
        coroutineContext.ensureActive()
        // 删除和新清单只进入尚未发布的树，固定旧 HEAD 的读取仍使用旧提交
        for (path in closure.obsoletePaths()) {
            coroutineContext.ensureActive()
            staging.remove(path)
        }
        return staging.finish()
    }

    private class ArchiveClosure(private val retained: Set<String>?, private val existing: Set<String>) {
        private val missing = (retained.orEmpty() - existing).toMutableSet()
        private var hasManifest = false

        fun uploaded(path: String) {
            if (path == ARCHIVE_MANIFEST_PATH) hasManifest = true else {
                require(retained == null || path in retained) { "Uploaded sync object is outside the archive closure" }
            }
            missing.remove(path)
        }

        fun obsoletePaths(): Set<String> {
            val paths = retained ?: return emptySet()
            require(hasManifest && missing.isEmpty()) { "Refusing to publish an incomplete sync archive closure" }
            return existing - paths
        }
    }

    private inner class ArchivePublication(
        private val owner: String,
        private val repo: String,
        private val expectedHead: GitHubSyncHead
    ) {
        private val url = GitHubArchiveRefUpdate.endpoint(apiBase)
        private val repositoryId = getRepositoryNodeId(owner, repo)
        private val repositoryCheckpoint = checkpoint(owner, repo)

        suspend fun publish(commitSha: String) {
            coroutineContext.ensureActive()
            repositoryCheckpoint?.ensureReady()
            val request = authenticatedRequest(url.toString())
                .header("Accept", "application/json")
                .post(GitHubArchiveRefUpdate.requestBody(repositoryId, expectedHead, commitSha).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                executeGraphQlMutation(client.newCall(request)) { response -> validatePublication(response, commitSha) }
            } catch (error: GitHubGraphQlException) {
                classifyReferenceFailure(error)
            }
        }

        private fun validatePublication(response: Response, commitSha: String) {
            if (response.request.url != url || response.request.method != "POST") {
                throw IOException("GitHub atomic sync publication response came from an unexpected endpoint")
            }
            if (!response.isSuccessful) throwForResponse(response, "publish sync archive", rateCheckpoint = repositoryCheckpoint,
                maxResponseBytes = GitHubArchiveRefUpdate.MAX_RESPONSE_BYTES)
            val body = SyncResponseBodyReader.read(response.body, GitHubArchiveRefUpdate.MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
            try {
                GitHubArchiveRefUpdate.validateResponse(body, commitSha)
            } catch (error: GitHubGraphQlException) {
                throw graphQlFailure(response, error, repositoryCheckpoint)
            }
        }

        private suspend fun classifyReferenceFailure(error: GitHubGraphQlException): Nothing {
            coroutineContext.ensureActive()
            if (error.staleReference) throw GitHubContentConflictException(409, error.message.orEmpty())
            // 不同服务器的 GraphQL 错误类型可能不同，重读只用于判断冲突，不替代原子发布
            if (getBranchHead(owner, repo, expectedHead.branch) != expectedHead.sha) {
                throw GitHubContentConflictException(409, "GitHub sync branch changed before atomic publication")
            }
            throw error
        }
    }

    private inner class ArchiveTreeStaging(
        private val owner: String,
        private val repo: String,
        private var treeSha: String
    ) {
        private val entries = ArrayList<Pair<String, String?>>(MAX_TREE_ENTRIES)
        private val cachedKeys = ArrayList<String>(MAX_TREE_ENTRIES)
        private val checkpoint = checkpoint(owner, repo)
        private var hasFiles = false

        fun append(path: String, content: ByteArray) {
            GitHubArchiveUploadValidation.validate(path, content)
            entries += path to stageBlob(path, content)
            hasFiles = true
            if (entries.size == MAX_TREE_ENTRIES) flush()
        }

        fun remove(path: String) {
            entries += path to null
            if (entries.size == MAX_TREE_ENTRIES) flush()
        }

        fun finish(): String {
            require(hasFiles) { "Refusing to commit an empty sync archive" }
            if (entries.isNotEmpty()) flush()
            return treeSha
        }

        private fun flush() {
            try {
                treeSha = createFilesTree(owner, repo, treeSha, entries)
            } catch (error: GitHubApiException) {
                // 悬空 blob 可能被远端回收，下次尝试重新上传这一批缓存对象
                if (error.statusCode == 422) checkpoint?.invalidateBlobKeys(cachedKeys)
                throw error
            }
            entries.clear()
            cachedKeys.clear()
        }

        private fun stageBlob(path: String, content: ByteArray): String {
            val cached = checkpoint?.cachedBlob(content)
            if (cached != null) {
                cachedKeys += GitHubSyncCheckpoint.contentKey(content)
                return cached
            }
            val sha = createBinaryBlob(owner, repo, content)
            checkpoint?.acknowledgeBlob(content, sha, progress = path.endsWith(".zst"))
            return sha
        }
    }

    suspend fun getFileContent(
        owner: String,
        repo: String,
        path: String,
        strict: Boolean
    ): Result<Pair<ByteArray, String>> = withContext(Dispatchers.IO) {
        syncTransportResult {
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
        syncTransportResult {
            validateUploadPayload(content)
            val target = resolveUploadTarget(owner, repo, branch, remoteHead)
            commitSyncFile(
                owner = owner,
                repo = repo,
                branch = target.branch,
                expectedHead = target.head,
                path = path,
                content = content,
                message = message
            )
        }.onFailure {
            NPLogger.e(TAG, "Upload GitHub sync content failed", it)
        }
    }

    private fun validateUploadPayload(content: ByteArray) {
        require(content.isNotEmpty()) { "Refusing to upload an empty sync payload" }
        require(content.size <= MAX_SYNC_FILE_BYTES) { "Sync payload is too large" }
    }

    private fun resolveUploadTarget(owner: String, repo: String, branch: String?, remoteHead: String?): UploadTarget {
        val targetBranch = branch ?: getDefaultBranch(owner, repo)
        val expectedHead = remoteHead?.takeIf(String::isNotBlank) ?: getBranchHead(owner, repo, targetBranch)
        return UploadTarget(targetBranch, expectedHead)
    }

    private class UploadTarget(val branch: String, val head: String)

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
            val body = SyncResponseBodyReader.readText(response.body)
            parseObject(body, "repository").requiredString("default_branch").ifBlank { "main" }
        }
    }

    private fun getRepositoryNodeId(owner: String, repo: String): String {
        val request = authenticatedRequest(endpoint("repos/$owner/$repo"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throwForResponse(response, "resolve repository node ID")
            GitHubArchiveRefUpdate.repositoryId(SyncResponseBodyReader.readText(response.body))
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
            val body = SyncResponseBodyReader.readText(response.body)
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
        val call = client.newCall(request)
        return call.execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> try {
                    SyncResponseBodyReader.read(response.body, SyncFileTransferLimits.responseBudget(path))
                } catch (error: IOException) {
                    // 先切断连接，避免关闭响应时为复用连接继续读取超限正文
                    call.cancel()
                    throw error
                }
                else -> throwForResponse(response, "read sync file",
                    maxResponseBytes = SyncFileTransferLimits.responseBudget(path), call = call)
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
            parseObject(SyncResponseBodyReader.readText(response.body), "sync commit")
                .getAsJsonObject("tree")
                ?.requiredString("sha")
                ?: throw IOException("GitHub sync commit has no tree SHA")
        }
    }

    private suspend fun readArchiveTreePaths(owner: String, repo: String, treeSha: String): Set<String> {
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/git/trees/$treeSha"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE).get().build()
        val context = coroutineContext
        val call = client.newCall(request)
        return call.execute().use { response ->
            validateArchiveTreeResponse(response, request, call)
            GitHubArchiveTreeReader.readOwnedObjectPaths(response.body, call, treeSha) { context.ensureActive() }
        }
    }

    private fun validateArchiveTreeResponse(response: Response, request: Request, call: Call) {
        if (response.request.url != request.url || response.request.method != "GET") {
            call.cancel()
            throw IOException("GitHub archive tree response came from an unexpected endpoint")
        }
        if (!response.isSuccessful) throwForResponse(response, "read archive tree", call = call)
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
            parseObject(SyncResponseBodyReader.readText(response.body), "sync binary blob").requiredString("sha")
        }
    }

    private fun createSyncFileTree(
        owner: String,
        repo: String,
        baseTreeSha: String,
        path: String,
        contentBlobSha: String
    ): String {
        return createFilesTree(owner, repo, baseTreeSha, listOf(path to contentBlobSha))
    }

    private fun createFilesTree(
        owner: String,
        repo: String,
        baseTreeSha: String,
        entries: List<Pair<String, String?>>
    ): String {
        val treeEntries = JSONArray()
        for ((path, sha) in entries) {
            treeEntries.put(JSONObject().apply {
                put("path", path)
                put("mode", "100644")
                put("type", "blob")
                put("sha", sha ?: JSONObject.NULL)
            })
        }
        val requestBody = JSONObject().apply {
            put("base_tree", baseTreeSha)
            put("tree", treeEntries)
        }.toString()
        val request = authenticatedRequest(endpoint("repos/$owner/$repo/git/trees"))
            .header("Accept", GITHUB_JSON_MEDIA_TYPE)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        return call.execute().use { response ->
            if (!response.isSuccessful) {
                throwForResponse(response, "create sync binary tree")
            }
            GitHubTreeResponseReader.readSha(response.body, call)
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
            parseObject(SyncResponseBodyReader.readText(response.body), "sync binary commit").requiredString("sha")
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
        val requestUrl = url.toHttpUrl()
        checkpointFor(requestUrl)?.ensureReady()
        return Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
    }

    private fun endpoint(path: String): String = "$apiBase/${path.trimStart('/')}"

    private fun checkpoint(owner: String, repo: String): GitHubSyncCheckpoint? = checkpointDirectory?.let {
        GitHubSyncCheckpoint(it, GitHubSyncCheckpoint.namespace(apiBase.trimEnd('/'), owner, repo), nowMillis)
    }

    private fun checkpointFor(url: HttpUrl): GitHubSyncCheckpoint? {
        val baseSegments = apiBase.toHttpUrl().pathSegments.filter(String::isNotEmpty)
        val segments = url.pathSegments.drop(baseSegments.size)
        if (segments.size < 3 || segments[0] != "repos") return null
        return checkpoint(segments[1], segments[2])
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

    private fun throwForResponse(
        response: Response,
        operation: String,
        detectConflict: Boolean = false,
        rateCheckpoint: GitHubSyncCheckpoint? = checkpointFor(response.request.url),
        maxResponseBytes: Int = SyncResponseBodyReader.MAX_SYNC_FILE_BYTES,
        call: Call? = null
    ): Nothing {
        val body = SyncResponseBodyReader.read(response.body, maxResponseBytes, call?.let { it::cancel }).toString(Charsets.UTF_8)
        val rateLimit = GitHubRateLimitPolicy.fromResponse(response, body, nowMillis())
        if (rateLimit != null) throw rateCheckpoint?.recordRateLimit(rateLimit) ?: rateLimit
        throwForResponse(response.code, body, operation, detectConflict)
    }

    private fun graphQlFailure(
        response: Response,
        error: GitHubGraphQlException,
        repositoryCheckpoint: GitHubSyncCheckpoint?
    ): Exception {
        if (!error.rateLimited && response.header("X-RateLimit-Remaining") != "0" && response.header("Retry-After") == null) return error
        val rateResponse = response.newBuilder().code(403).build()
        val rate = checkNotNull(GitHubRateLimitPolicy.fromResponse(rateResponse, "rate limit", nowMillis()))
        return repositoryCheckpoint?.recordRateLimit(rate) ?: rate
    }

    private fun throwForResponse(
        statusCode: Int,
        body: String,
        operation: String,
        detectConflict: Boolean = false
    ): Nothing {
        if (statusCode == 401) {
            throw TokenExpiredException(tokenExpiredMessage)
        }
        val message = "$operation failed: $statusCode - ${errorMessage(body)}"
        if (
            detectConflict &&
            (statusCode == 409 || (statusCode == 422 && isReferenceConflict(body)))
        ) {
            throw GitHubContentConflictException(statusCode, message)
        }
        throw GitHubApiException(statusCode, message)
    }

    private fun isReferenceConflict(body: String): Boolean =
        body.contains("reference", ignoreCase = true) ||
            body.contains("fast forward", ignoreCase = true) ||
            body.contains("fast-forward", ignoreCase = true)

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
        const val MAX_TREE_ENTRIES = 1000
        private const val ARCHIVE_MANIFEST_PATH = "neriplayer-sync-v3.manifest"
        const val MAX_ERROR_MESSAGE_LENGTH = 240
    }
}
