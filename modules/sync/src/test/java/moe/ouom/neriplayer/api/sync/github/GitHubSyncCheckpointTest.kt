package moe.ouom.neriplayer.api.sync.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GitHubSyncCheckpointTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `namespaces isolate API origins and repositories while normalizing repository case`() {
        val first = GitHubSyncCheckpoint.namespace("https://api.github.com", "owner", "repo")
        assertEquals(first, GitHubSyncCheckpoint.namespace("https://api.github.com", "OWNER", "REPO"))
        assertNotEquals(first, GitHubSyncCheckpoint.namespace("https://enterprise.test/api/v3", "owner", "repo"))
        assertNotEquals(first, GitHubSyncCheckpoint.namespace("https://api.github.com", "another", "repo"))
        assertNotEquals(first, GitHubSyncCheckpoint.namespace("https://api.github.com", "owner", "another"))
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `only matching acknowledged Git blobs are reusable after restart`() {
        val root = temporary.newFolder()
        val content = "confirmed".toByteArray()
        val checkpoint = checkpoint(root)
        assertNull(checkpoint.cachedBlob(content))
        val wrong = runCatching { checkpoint.acknowledgeBlob(content, "0".repeat(40), true) }.exceptionOrNull()
        assertTrue(wrong is IOException)
        val sha = GitHubSyncCheckpoint.gitBlobSha(content)
        checkpoint.acknowledgeBlob(content, sha, true)
        assertEquals(sha, checkpoint(root).cachedBlob(content))
        checkpoint.invalidateBlobKeys(listOf(GitHubSyncCheckpoint.contentKey(content)))
        assertNull(checkpoint(root).cachedBlob(content))
    }

    @Test
    fun `oversized truncated and malformed local records are not reused`() {
        val root = temporary.newFolder()
        val content = "confirmed".toByteArray()
        val directory = File(root, "namespace").apply { mkdirs() }
        val file = File(directory, "${GitHubSyncCheckpoint.contentKey(content)}.blob")
        for (record in listOf("partial", "x".repeat(400), "blob-v2\n${GitHubSyncCheckpoint.gitBlobSha(content)}\n")) {
            file.writeText(record)
            assertNull(checkpoint(root).cachedBlob(content))
        }
        checkpoint(root).acknowledgeBlob(content, GitHubSyncCheckpoint.gitBlobSha(content), true)
        assertTrue(checkpoint(root).cachedBlob(content) != null)
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `persisted cooldown is not shortened by concurrent success or another limit response`() {
        val root = temporary.newFolder()
        var now = 1_000_000L
        val checkpoint = GitHubSyncCheckpoint(root, "namespace") { now }
        val first = checkpoint.recordRateLimit(GitHubRateLimitException(429, now + 600_000L, false, "limited"))
        val content = "new progress".toByteArray()
        checkpoint.acknowledgeBlob(content, GitHubSyncCheckpoint.gitBlobSha(content), true)
        val duringCooldown = runCatching { checkpoint.ensureReady() }.exceptionOrNull() as GitHubRateLimitException
        assertEquals(first.retryAtMillis, duringCooldown.retryAtMillis)
        val later = checkpoint.recordRateLimit(GitHubRateLimitException(403, now + 60_000L, false, "limited"))
        assertEquals(first.retryAtMillis, later.retryAtMillis)
        assertTrue(later.automaticRetryAllowed)
        checkpoint.published()
        assertTrue(runCatching { checkpoint.ensureReady() }.exceptionOrNull() is GitHubRateLimitException)
        now = later.retryAtMillis + 1L
        checkpoint.ensureReady()
    }

    @Test
    fun `invalid cooldown checksums and out of range fields are ignored`() {
        val root = temporary.newFolder()
        val directory = File(root, "namespace").apply { mkdirs() }
        val file = File(directory, "rate-limit")
        for (record in listOf("partial", "rate-v1\n429\n2000000\n1\nbad-checksum", "x".repeat(400))) {
            file.writeText(record)
            checkpoint(root).ensureReady()
        }
        for (fields in listOf(listOf("500", "2000000", "1"), listOf("429", "-1", "1"), listOf("429", "2000000", "5"),
            listOf("bad-status", "2000000", "1"), listOf("429", "bad-timestamp", "1"), listOf("429", "2000000", "bad-attempts"))) {
            val prefix = "rate-v1\n${fields.joinToString("\n")}\n"
            file.writeText(prefix + GitHubSyncCheckpoint.contentKey(prefix.toByteArray()))
            checkpoint(root).ensureReady()
        }
        assertFalse(checkpoint(root).recordRateLimit(GitHubRateLimitException(429, 2_000_000L, false, "limited")).message.isNullOrBlank())
    }

    @Test
    fun `parallel checkpoint writers do not overwrite another content key`() {
        val root = temporary.newFolder()
        val pool = Executors.newFixedThreadPool(4)
        val contents = (0..19).map { "content-$it".toByteArray() }
        try {
            val futures = contents.map { content -> pool.submit {
                checkpoint(root).acknowledgeBlob(content, GitHubSyncCheckpoint.gitBlobSha(content), true)
            } }
            for (future in futures) future.get(10, TimeUnit.SECONDS)
            for (content in contents) assertEquals(GitHubSyncCheckpoint.gitBlobSha(content), checkpoint(root).cachedBlob(content))
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `publication bounds retained checkpoints while interrupted staging remains intact`() {
        val root = temporary.newFolder()
        val directory = File(root, "namespace").apply { mkdirs() }
        for (index in 0..8_192) File(directory, "$index.blob").writeText("old")
        assertEquals(8_193, directory.listFiles()!!.size)
        checkpoint(root).published()
        assertEquals(8_192, directory.listFiles()!!.size)
    }

    private fun checkpoint(root: File): GitHubSyncCheckpoint = GitHubSyncCheckpoint(root, "namespace") { 1_000_000L }
}
