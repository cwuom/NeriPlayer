package moe.ouom.neriplayer.data.sync.host

import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.github.GitHubContentConflictException
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.transport.WebDavConcurrencyToken
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.github.GitHubSyncBackend
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncBackend
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SyncBackendTransportTest {
    private val payload = SyncData(deviceId = "device", lastModified = 10)
    private val content = SyncDataSerializer.serialize(payload, false)

    @Test
    fun `GitHub fallback requests migration while corrupt and unavailable files fail`() = runTest {
        val fixture = GitHubFixture()
        assertNull(fixture.backend.fetch().getOrThrow().data)
        fixture.files["backup.bin"] = SyncDataSerializer.serialize(payload, true)
        val fallback = fixture.backend.fetch().getOrThrow()
        assertEquals(payload, fallback.data)
        assertTrue(fallback.requiresMigrationUpload)
        assertEquals("backup.json", fallback.version.fileName)
        fixture.files["backup.json"] = byteArrayOf(1, 2, 3)
        assertTrue(fixture.backend.fetch().isFailure)
        fixture.files["backup.json"] = content
        val primary = fixture.backend.refetch(fallback.version).getOrThrow()
        assertEquals(payload, primary.data)
        assertFalse(primary.requiresMigrationUpload)
        fixture.failure = 500
        assertTrue(fixture.backend.fetch().isFailure)
    }

    @Test
    fun `GitHub uploads retain version and state persistence contracts`() = runTest {
        val fixture = GitHubFixture()
        assertTrue(fixture.backend.isFirstSync)
        assertFalse(fixture.backend.remoteChanged(GitHubSyncBackend.Version("new", "backup.json")))
        `when`(fixture.storage.getLastRemoteSha()).thenReturn("old")
        assertFalse(fixture.backend.isFirstSync)
        assertFalse(fixture.backend.remoteChanged(GitHubSyncBackend.Version(null, "backup.json")))
        assertFalse(fixture.backend.remoteChanged(GitHubSyncBackend.Version("old", "backup.json")))
        assertTrue(fixture.backend.remoteChanged(GitHubSyncBackend.Version("new", "backup.json")))
        val version = fixture.backend.upload(payload, GitHubSyncBackend.Version(null, "backup.json")).getOrThrow()
        assertEquals("commit", version.sha)
        assertEquals("backup.json", version.fileName)
        fixture.backend.saveRemoteVersion(version)
        fixture.backend.saveRemoteVersion(version.copy(sha = null))
        fixture.backend.saveSyncTime(42)
        fixture.backend.scheduleFollowUp()
        fixture.backend.onFailure(TokenExpiredException("expired"))
        fixture.backend.onFailure(IOException("offline"))
        verify(fixture.storage).saveLastRemoteSha("commit")
        verify(fixture.storage).saveLastSyncTime(42)
        verify(fixture.storage).clearToken()
        assertEquals(1, fixture.followUps)
        fixture.failure = 409
        val failed = fixture.backend.upload(payload, version)
        assertTrue(failed.isFailure)
        assertTrue(fixture.backend.isConflict(GitHubContentConflictException(409, "changed")))
        assertFalse(fixture.backend.isConflict(null))
    }

    @Test
    fun `WebDAV distinguishes missing corrupt valid and authentication failures`() = runTest {
        val fixture = WebDavFixture()
        fixture.readCode = 404
        assertTrue(fixture.backend.fetch().getOrThrow().version.createOnly)
        fixture.readCode = 401
        assertTrue(fixture.backend.fetch().exceptionOrNull() is WebDavAuthException)
        fixture.readCode = 200
        fixture.body = byteArrayOf(1, 2)
        assertTrue(fixture.backend.fetch().isFailure)
        fixture.body = content
        val snapshot = fixture.backend.fetch().getOrThrow()
        assertEquals(payload, snapshot.data)
        assertFalse(snapshot.version.createOnly)
        assertEquals("\"etag\"", snapshot.version.token?.etag)
        assertEquals(payload, fixture.backend.refetch(snapshot.version).getOrThrow().data)
    }

    @Test
    fun `WebDAV revalidates fingerprint before fallback and propagates write failures`() = runTest {
        val fixture = WebDavFixture()
        val fingerprint = WebDavApiClient.calculateFingerprint(content)
        val withoutToken = WebDavSyncBackend.Version(WebDavConcurrencyToken(), false, fingerprint)
        val uploaded = fixture.backend.upload(payload, withoutToken).getOrThrow()
        assertEquals(fingerprint, uploaded.lastKnownFingerprint)
        assertEquals("application/json; charset=utf-8", fixture.writes.last().body?.contentType().toString())
        assertNull(fixture.writes.last().header("If-Match"))
        fixture.body = SyncDataSerializer.serialize(payload.copy(lastModified = 11), false)
        assertTrue(fixture.backend.upload(payload, withoutToken).exceptionOrNull() is WebDavContentConflictException)
        fixture.writeCode = 500
        val guarded = WebDavSyncBackend.Version(WebDavConcurrencyToken(etag = "\"etag\""), false)
        assertTrue(fixture.backend.upload(payload, guarded).isFailure)
        fixture.writeCode = 201
        `when`(fixture.storage.isDataSaverMode()).thenReturn(true)
        fixture.backend.upload(payload, guarded).getOrThrow()
        val request = fixture.writes.last()
        assertEquals("\"etag\"", request.header("If-Match"))
        assertEquals("application/octet-stream", request.body?.contentType().toString())
        val bytes = Buffer().use { request.body!!.writeTo(it); it.readByteArray() }
        assertEquals(payload, SyncDataSerializer.deserialize(bytes))
    }

    @Test
    fun `WebDAV first sync remote versions and follow up bindings remain stable`() {
        val fixture = WebDavFixture()
        val version = WebDavSyncBackend.Version(null, false, "new")
        assertTrue(fixture.backend.isFirstSync)
        assertFalse(fixture.backend.remoteChanged(version))
        `when`(fixture.webDavStorage.getLastRemoteFingerprint()).thenReturn("old")
        assertFalse(fixture.backend.isFirstSync)
        assertTrue(fixture.backend.remoteChanged(version))
        assertFalse(fixture.backend.remoteChanged(version.copy(lastKnownFingerprint = "old")))
        assertFalse(fixture.backend.remoteChanged(version.copy(lastKnownFingerprint = null)))
        fixture.backend.saveRemoteVersion(version)
        fixture.backend.saveRemoteVersion(version.copy(lastKnownFingerprint = null))
        fixture.backend.saveSyncTime(42)
        fixture.backend.scheduleFollowUp()
        fixture.backend.onFailure(IOException())
        verify(fixture.webDavStorage).saveLastRemoteFingerprint("new")
        verify(fixture.webDavStorage).saveLastSyncTime(42)
        assertEquals(1, fixture.followUps)
        assertTrue(fixture.backend.isConflict(WebDavContentConflictException(412, "changed")))
        assertFalse(fixture.backend.isConflict(null))
    }

    private inner class GitHubFixture {
        val storage = mock(SecureTokenStorage::class.java)
        val files = mutableMapOf<String, ByteArray>()
        var failure = 0
        var followUps = 0
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            when {
                failure != 0 -> response(request, failure, "{}".toByteArray())
                path.contains("/contents/") -> {
                    val bytes = files[path.substringAfterLast('/')]
                    response(request, if (bytes == null) 404 else 200, bytes ?: byteArrayOf())
                }
                path.endsWith("/git/ref/heads/main") -> response(request, 200, """{"object":{"sha":"head"}}""".toByteArray())
                path.contains("/git/commits/") -> response(request, 200, """{"tree":{"sha":"tree"}}""".toByteArray())
                path.endsWith("/git/refs/heads/main") -> response(request, 200, "{}".toByteArray())
                request.method == "POST" -> response(request, 201, """{"sha":"commit"}""".toByteArray())
                else -> response(request, 200, """{"default_branch":"main"}""".toByteArray())
            }
        }.build()
        val backend = GitHubSyncBackend(storage, GitHubApiClient("test-token", client, "expired", "https://sync.test"),
            "owner", "repo", SyncRemoteSnapshotDecoder { it }, { IOException("invalid") }, { followUps++ })
    }

    private inner class WebDavFixture {
        val storage = mock(SecureTokenStorage::class.java)
        val webDavStorage = mock(WebDavStorage::class.java)
        var readCode = 200
        var writeCode = 201
        var body = content
        var followUps = 0
        val writes = mutableListOf<Request>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "PUT") {
                writes += request
                response(request, writeCode, byteArrayOf())
            } else response(request, readCode, body)
        }.build()
        val backend = WebDavSyncBackend(storage, webDavStorage, WebDavApiClient("test-user", "test-password", client, "auth"),
            "https://sync.test/backup", SyncRemoteSnapshotDecoder { it }, { IOException("invalid") }, { followUps++ })
    }

    private fun response(request: Request, code: Int, body: ByteArray): Response = Response.Builder()
        .request(request).code(code).message("test").protocol(Protocol.HTTP_1_1)
        .header("ETag", "\"etag\"").body(body.toResponseBody()).build()
}
