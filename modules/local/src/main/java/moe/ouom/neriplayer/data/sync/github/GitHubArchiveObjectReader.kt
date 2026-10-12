package moe.ouom.neriplayer.data.sync.github

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.github.GitHubFileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

internal class GitHubArchiveObjectReader(
    private val api: GitHubApiClient,
    private val owner: String,
    private val repo: String,
    private val ref: String,
    objectIds: Map<String, String>,
    private val context: CoroutineContext
) {
    private val objectIds = objectIds.toMap()
    val cachedObjects = AtomicInteger()
    val downloadedObjects = AtomicInteger()

    fun verifyCached(path: String, content: ByteArray): Boolean {
        context.ensureActive()
        val expected = requiredObjectId(path)
        val matches = blobId(content) == expected
        context.ensureActive()
        if (matches) cachedObjects.incrementAndGet()
        return matches
    }

    suspend fun fetch(path: String): Result<ByteArray> {
        val activeContext = currentCoroutineContext()
        activeContext.ensureActive()
        val expected = requiredObjectId(path)
        return api.getFileContentAtRef(owner, repo, path, ref).mapCatching { content ->
            activeContext.ensureActive()
            if (blobId(content) != expected) throw IOException("GitHub archive object differs from its fixed commit tree")
            activeContext.ensureActive()
            downloadedObjects.incrementAndGet()
            content
        }
    }

    private fun requiredObjectId(path: String): String = objectIds[path]
        ?: throw GitHubFileNotFoundException("Remote archive object not found in fixed commit: $path")

    private fun blobId(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII))
        return digest.digest(content).joinToString("") { "%02x".format(it) }
    }
}
