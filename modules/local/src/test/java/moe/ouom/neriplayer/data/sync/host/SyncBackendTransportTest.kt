package moe.ouom.neriplayer.data.sync.host

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.github.GitHubContentConflictException
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException
import moe.ouom.neriplayer.api.sync.webdav.WebDavFileNotFoundException
import moe.ouom.neriplayer.api.sync.webdav.WebDavMissingConcurrencyTokenException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.transport.WebDavConcurrencyToken
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
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
import org.junit.After
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetRemoteSnapshot
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.Base64
import java.security.MessageDigest

class SyncBackendTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ownedDatasets = ArrayList<SyncDataset>()
    @After fun releaseDatasets() { ownedDatasets.forEach { it.close() } }
    private suspend fun staged(data: SyncData): SyncDataset =
        FileSyncPlaybackDatasetStore(temporary.newFolder()).fromLegacy(data).also(ownedDatasets::add)
    private fun <T> SyncDatasetRemoteSnapshot<T>.tracked(): SyncDatasetRemoteSnapshot<T> =
        also { it.dataset?.let(ownedDatasets::add) }
    private val payload = SyncData(deviceId = "device", lastModified = 10,
        playlists = listOf(SyncPlaylist(id = 1, name = "playlist", songs = listOf(SyncSong(id = 2, name = "song")))))
    private val content = SyncDataSerializer.serialize(payload, false)

    @Test
    fun `GitHub conflict refetch to an empty target cannot republish a stale legacy source`() = runTest {
        val fixture = GitHubFixture()
        val legacy = payload.copy(playlists = listOf(payload.playlists.single().copy(
            songs = listOf(payload.playlists.single().songs.single().copy(matchedLyric = "legacy edit")))))
        fixture.files["backup.json"] = SyncDataSerializer.serialize(legacy, false)
        val initial = fixture.backend.fetch().getOrThrow().tracked()
        val published = fixture.backend.upload(initial.dataset!!, initial.version).getOrThrow()
        fixture.backend.fetch().getOrThrow().tracked()
        fixture.files.clear()
        val removed = fixture.backend.refetch(published).getOrThrow().tracked()
        assertNull(removed.dataset)
        fixture.backend.upload(staged(payload), removed.version).getOrThrow()
        val readable = SyncArchiveRepository(temporary.newFolder()).read(fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)) {
            fixture.files[it]?.let { bytes -> Result.success(bytes) } ?: Result.failure(IOException("removed legacy source"))
        }
        assertTrue("published archive must not reference removed legacy objects", readable.isSuccess)
        assertEquals(payload, readable.getOrThrow())
    }

    @Test
    fun `WebDAV conflict refetch to an empty target cannot republish a stale legacy source`() = runTest {
        val fixture = WebDavFixture()
        val legacy = payload.copy(playlists = listOf(payload.playlists.single().copy(
            songs = listOf(payload.playlists.single().songs.single().copy(matchedLyric = "legacy edit")))))
        fixture.body = SyncDataSerializer.serialize(legacy, false)
        val initial = fixture.backend.fetch().getOrThrow().tracked()
        val published = fixture.backend.upload(initial.dataset!!, initial.version).getOrThrow()
        fixture.backend.fetch().getOrThrow().tracked()
        fixture.files.clear()
        fixture.readCode = 404
        val removed = fixture.backend.refetch(published).getOrThrow().tracked()
        assertNull(removed.dataset)
        fixture.readCode = 200
        fixture.backend.upload(staged(payload), removed.version).getOrThrow()
        val readable = SyncArchiveRepository(temporary.newFolder()).read(fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)) {
            fixture.files[it]?.let { bytes -> Result.success(bytes) } ?: Result.failure(IOException("removed legacy source"))
        }
        assertTrue("published archive must not reference removed legacy objects", readable.isSuccess)
        assertEquals(payload, readable.getOrThrow())
    }

    @Test
    fun `GitHub requires target specific approval for initial and conflict legacy reads`() = runTest {
        val fixture = GitHubFixture()
        val required = SyncProtocolUpgradeRequiredException("upgrade", SyncProtocolUpgradeChallenge("a".repeat(64), "1".repeat(64)))
        fixture.files["backup.json"] = content
        fixture.authorizeMigration = { assertArrayEquals(content, it); throw required }
        assertSame(required, fixture.backend.fetch().exceptionOrNull())
        assertSame(required, fixture.backend.refetch(GitHubSyncBackend.Version("head", "backup.json")).exceptionOrNull())
        assertTrue(fixture.writes.isEmpty())
        assertEquals(0, fixture.currentObservations)
        fixture.authorizeMigration = {}
        val migrated = fixture.backend.fetch().getOrThrow().tracked()
        assertEquals(payload, migrated.dataset?.data)
        fixture.backend.upload(migrated.dataset!!, migrated.version).getOrThrow()
        fixture.authorizeMigration = { error("current archive must not request legacy upgrade") }
        fixture.backend.fetch().getOrThrow().tracked()
        assertEquals(2, fixture.currentObservations)
    }

    @Test
    fun `WebDAV requires target specific approval for initial and conflict legacy reads`() = runTest {
        val fixture = WebDavFixture()
        val required = SyncProtocolUpgradeRequiredException("upgrade", SyncProtocolUpgradeChallenge("b".repeat(64), "2".repeat(64)))
        fixture.authorizeMigration = { assertArrayEquals(content, it); throw required }
        assertSame(required, fixture.backend.fetch().exceptionOrNull())
        assertSame(required, fixture.backend.refetch(WebDavSyncBackend.Version(null, true)).exceptionOrNull())
        assertTrue(fixture.writes.isEmpty())
        assertEquals(0, fixture.currentObservations)
        fixture.authorizeMigration = {}
        val migrated = fixture.backend.fetch().getOrThrow().tracked()
        assertEquals(payload, migrated.dataset?.data)
        fixture.backend.upload(migrated.dataset!!, migrated.version).getOrThrow()
        fixture.authorizeMigration = { error("current archive must not request legacy upgrade") }
        fixture.backend.fetch().getOrThrow().tracked()
        assertEquals(2, fixture.currentObservations)
    }

    @Test
    fun `empty GitHub and WebDAV targets clear stale upgrade state without legacy permission`() = runTest {
        val github = GitHubFixture()
        github.authorizeMigration = { error("empty target must not require upgrade") }
        assertNull(github.backend.fetch().getOrThrow().tracked().dataset)
        assertEquals(1, github.currentObservations)
        val webdav = WebDavFixture()
        webdav.readCode = 404
        webdav.authorizeMigration = { error("empty target must not require upgrade") }
        assertNull(webdav.backend.fetch().getOrThrow().tracked().dataset)
        assertEquals(1, webdav.currentObservations)
    }

    @Test
    fun `GitHub fallback requests migration while corrupt and unavailable files fail`() = runTest {
        val fixture = GitHubFixture()
        assertNull(fixture.backend.fetch().getOrThrow().tracked().dataset?.data)
        fixture.files["backup.bin"] = SyncDataSerializer.serialize(payload, true)
        val fallback = fixture.backend.fetch().getOrThrow().tracked()
        assertEquals(payload, fallback.dataset?.data)
        assertTrue(fallback.requiresMigrationUpload)
        assertEquals(SyncArchiveRepository.MANIFEST_FILE_NAME, fallback.version.fileName)
        assertEquals("head", fallback.version.sha)
        assertTrue(fixture.reads.all { it.url.queryParameter("ref") == "head" })
        fixture.files["backup.json"] = byteArrayOf(1, 2, 3)
        assertTrue(fixture.backend.fetch().isFailure)
        fixture.files["backup.json"] = content
        val primary = fixture.backend.refetch(fallback.version).getOrThrow().tracked()
        assertEquals(payload, primary.dataset?.data)
        assertTrue(primary.requiresMigrationUpload)
        fixture.files[SyncArchiveRepository.MANIFEST_FILE_NAME] = byteArrayOf(1, 2)
        assertTrue(fixture.backend.fetch().isFailure)
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
        val version = fixture.backend.upload(staged(payload), GitHubSyncBackend.Version(null, "backup.json")).getOrThrow()
        assertEquals("commit", version.sha)
        assertEquals(SyncArchiveRepository.MANIFEST_FILE_NAME, version.fileName)
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
        val failed = fixture.backend.upload(staged(payload), version)
        assertTrue(failed.isFailure)
        assertTrue(fixture.backend.isConflict(GitHubContentConflictException(409, "changed")))
        assertFalse(fixture.backend.isConflict(null))
    }

    @Test
    fun `GitHub reuses immutable uploads and verifies the complete remote archive on every read`() = runTest {
        val fixture = GitHubFixture()
        val empty = fixture.backend.fetch().getOrThrow().tracked()
        assertEquals("head", empty.version.sha)
        val uploaded = fixture.backend.upload(staged(payload), empty.version).getOrThrow()
        assertTrue(uploaded.knownPaths.isNotEmpty())
        fixture.writes.clear()

        fixture.backend.upload(staged(payload), uploaded).getOrThrow()

        assertEquals(1, fixture.writes.count { it.url.encodedPath.endsWith("/git/blobs") })
        assertEquals(1, fixture.writes.count { it.url.encodedPath.endsWith("/git/commits") })
        fixture.reads.clear()
        assertEquals(payload, fixture.backend.fetch().getOrThrow().tracked().dataset?.data)
        assertEquals(uploaded.knownPaths + SyncArchiveRepository.MANIFEST_FILE_NAME,
            fixture.reads.map { it.url.pathSegments.last() }.toSet())
        assertEquals(uploaded.knownPaths.size + 1, fixture.reads.size)
        assertTrue(fixture.reads.all { it.url.queryParameter("ref") == "commit" })
    }

    @Test
    fun `GitHub migrates an archive manifest discovered at a legacy file name`() = runTest {
        val fixture = GitHubFixture()
        fixture.backend.upload(staged(payload), fixture.backend.fetch().getOrThrow().tracked().version).getOrThrow()
        fixture.files["backup.json"] = fixture.files.remove(SyncArchiveRepository.MANIFEST_FILE_NAME)!!

        val legacy = fixture.backend.fetch().getOrThrow().tracked()

        assertEquals(payload, legacy.dataset?.data)
        assertTrue(legacy.requiresMigrationUpload)
        assertTrue(legacy.version.knownPaths.isNotEmpty())
        fixture.backend.upload(legacy.dataset!!, legacy.version).getOrThrow()
        assertTrue(fixture.files.containsKey(SyncArchiveRepository.MANIFEST_FILE_NAME))
    }

    @Test
    fun `GitHub head resolution failures prevent upload and preserve an explicitly expected head`() = runTest {
        val fixture = GitHubFixture()
        fixture.failure = 500
        assertTrue(fixture.backend.upload(staged(payload), GitHubSyncBackend.Version(null, "backup.json")).isFailure)
        assertTrue(fixture.writes.isEmpty())
        fixture.failure = 0
        fixture.headBody = "{}"
        assertTrue(fixture.backend.fetch().isFailure)
        assertTrue(fixture.reads.isEmpty())
        fixture.headBody = """{"object":{"sha":"current-head"}}"""

        val result = fixture.backend.upload(staged(payload), GitHubSyncBackend.Version("expected-head", "backup.json"))
        assertTrue(result.exceptionOrNull() is GitHubContentConflictException)

        val commit = fixture.writes.single { it.url.encodedPath.endsWith("/git/commits") }
        val body = Buffer().use { commit.body!!.writeTo(it); JSONObject(it.readUtf8()) }
        assertEquals("expected-head", body.getJSONArray("parents").getString(0))
        val publication = fixture.writes.single { it.url.encodedPath == "/graphql" }
        val publicationBody = Buffer().use { publication.body!!.writeTo(it); JSONObject(it.readUtf8()) }
        val update = publicationBody.getJSONObject("variables").getJSONObject("input").getJSONArray("refUpdates").getJSONObject(0)
        assertEquals("expected-head", update.getString("beforeOid"))
        assertFalse(update.getBoolean("force"))
        assertTrue(fixture.writes.none { it.method == "PATCH" })
    }

    @Test
    fun `GitHub strict publication rejects an external branch rollback without replacing the committed snapshot`() = runTest {
        val fixture = GitHubFixture()
        val version = fixture.backend.upload(staged(payload), fixture.backend.fetch().getOrThrow().tracked().version).getOrThrow()
        val committedFiles = fixture.files.toMap()
        fixture.headBody = """{"object":{"sha":"ancestor-head"}}"""
        fixture.writes.clear()

        val result = fixture.backend.upload(staged(payload.copy(lastModified = 20)), version)

        assertTrue(result.exceptionOrNull() is GitHubContentConflictException)
        assertEquals(committedFiles, fixture.files)
        assertEquals(1, fixture.writes.count { it.url.encodedPath == "/graphql" })
        assertTrue(fixture.writes.none { it.method == "PATCH" })
    }

    @Test
    fun `GitHub unavailable manifest and legacy body cannot use a stale fallback`() = runTest {
        val fixture = GitHubFixture()
        fixture.files["backup.bin"] = SyncDataSerializer.serialize(payload, true)
        fixture.fileFailures[SyncArchiveRepository.MANIFEST_FILE_NAME] = 500
        assertTrue(fixture.backend.fetch().isFailure)
        assertEquals(1, fixture.reads.size)
        fixture.fileFailures.clear()
        fixture.reads.clear()
        fixture.fileFailures["backup.json"] = 500
        assertTrue(fixture.backend.fetch().isFailure)
        assertTrue(fixture.reads.none { it.url.encodedPath.endsWith("backup.bin") })
    }

    @Test
    fun `WebDAV distinguishes missing corrupt valid and authentication failures`() = runTest {
        val fixture = WebDavFixture()
        fixture.readCode = 404
        assertTrue(fixture.backend.fetch().getOrThrow().tracked().version.createOnly)
        fixture.readCode = 401
        assertTrue(fixture.backend.fetch().exceptionOrNull() is WebDavAuthException)
        fixture.readCode = 200
        fixture.body = byteArrayOf(1, 2)
        assertTrue(fixture.backend.fetch().isFailure)
        fixture.body = content
        val snapshot = fixture.backend.fetch().getOrThrow().tracked()
        assertEquals(payload, snapshot.dataset?.data)
        assertTrue(snapshot.version.createOnly)
        assertNull(snapshot.version.token)
        assertTrue(snapshot.requiresMigrationUpload)
        assertEquals(payload, fixture.backend.refetch(snapshot.version).getOrThrow().tracked().dataset?.data)
    }

    @Test
    fun `WebDAV migrates an archive manifest discovered at the configured legacy file`() = runTest {
        val fixture = WebDavFixture()
        fixture.backend.upload(staged(payload), WebDavSyncBackend.Version(null, true)).getOrThrow()
        fixture.body = fixture.files.remove(SyncArchiveRepository.MANIFEST_FILE_NAME)!!

        val legacy = fixture.backend.fetch().getOrThrow().tracked()

        assertEquals(payload, legacy.dataset?.data)
        assertTrue(legacy.requiresMigrationUpload)
        assertTrue(legacy.version.createOnly)
        assertTrue(legacy.version.knownPaths.isNotEmpty())
        fixture.backend.upload(legacy.dataset!!, legacy.version).getOrThrow()
        assertTrue(fixture.files.containsKey(SyncArchiveRepository.MANIFEST_FILE_NAME))
    }

    @Test
    fun `WebDAV missing parent directory fails instead of producing an initial upload snapshot`() = runTest {
        val fixture = WebDavFixture()
        fixture.readCode = 404
        fixture.directoryCode = 404

        val result = fixture.backend.fetch()

        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
        val error = result.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(error is WebDavFileNotFoundException)
        assertFalse(error is WebDavAuthException)
    }

    @Test
    fun `WebDAV forbidden file and parent directory reads fail without an authentication error`() = runTest {
        for ((readCode, directoryCode) in listOf(403 to 207, 404 to 403)) {
            val fixture = WebDavFixture()
            fixture.readCode = readCode
            fixture.directoryCode = directoryCode

            val result = fixture.backend.fetch()

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(error is WebDavAuthException)
        }
    }

    @Test
    fun `WebDAV rejects unsafe manifest writes and propagates protected write failures`() = runTest {
        val fixture = WebDavFixture()
        val fingerprint = WebDavApiClient.calculateFingerprint(content)
        val withoutToken = WebDavSyncBackend.Version(WebDavConcurrencyToken(), false, fingerprint)
        for (token in listOf(WebDavConcurrencyToken(), WebDavConcurrencyToken(etag = "W/\"weak\""),
            WebDavConcurrencyToken(etag = "\"first\", \"second\""), WebDavConcurrencyToken(etag = "\"space inside\""),
            WebDavConcurrencyToken(lastModified = "Thu, 01 Oct 2026 00:00:00 GMT"))) {
            assertTrue(fixture.backend.upload(staged(payload), withoutToken.copy(token = token)).exceptionOrNull()
                is WebDavMissingConcurrencyTokenException)
        }
        assertTrue(fixture.writes.isEmpty())
        val uploaded = fixture.backend.upload(staged(payload), WebDavSyncBackend.Version(null, true)).getOrThrow()
        assertTrue(uploaded.knownPaths.isNotEmpty())
        assertEquals("*", fixture.writes.last().header("If-None-Match"))
        assertEquals("application/octet-stream", fixture.writes.last().body?.contentType().toString())
        fixture.writeCode = 500
        val guarded = WebDavSyncBackend.Version(WebDavConcurrencyToken(etag = "\"etag\""), false)
        assertTrue(fixture.backend.upload(staged(payload), guarded).isFailure)
        fixture.writeCode = 201
        fixture.backend.upload(staged(payload), uploaded).getOrThrow()
        val request = fixture.writes.last()
        assertEquals("\"etag\"", request.header("If-Match"))
        assertEquals("application/octet-stream", request.body?.contentType().toString())
        assertEquals("/" + SyncArchiveRepository.MANIFEST_FILE_NAME, request.url.encodedPath)
        assertEquals(payload, fixture.backend.fetch().getOrThrow().tracked().dataset?.data)
    }

    @Test
    fun `WebDAV immutable conflicts verify content before publishing and corruption never falls back`() = runTest {
        val fixture = WebDavFixture()
        val uploaded = fixture.backend.upload(staged(payload), WebDavSyncBackend.Version(null, true)).getOrThrow()
        fixture.writes.clear()

        fixture.backend.upload(staged(payload), uploaded.copy(knownPaths = emptySet())).getOrThrow()

        assertTrue(fixture.writes.size > 1)
        assertTrue(fixture.writes.dropLast(1).all { it.header("If-None-Match") == "*" })
        fixture.writes.clear()
        fixture.backend.upload(staged(payload), uploaded).getOrThrow()
        assertEquals(1, fixture.writes.size)
        fixture.reads.clear()
        assertEquals(payload, fixture.backend.fetch().getOrThrow().tracked().dataset?.data)
        assertEquals(uploaded.knownPaths + SyncArchiveRepository.MANIFEST_FILE_NAME,
            fixture.reads.map { it.url.pathSegments.last() }.toSet())
        assertEquals(uploaded.knownPaths.size + 1, fixture.reads.size)
        fixture.files[SyncArchiveRepository.MANIFEST_FILE_NAME] = byteArrayOf(1, 2)
        fixture.reads.clear()
        assertTrue(fixture.backend.fetch().isFailure)
        assertEquals(1, fixture.reads.size)
    }

    @Test
    fun `GitHub cached objects cannot conceal missing or corrupt remote archive objects`() = runTest {
        for (remove in listOf(false, true)) {
            val fixture = GitHubFixture()
            val uploaded = fixture.backend.upload(staged(payload), fixture.backend.fetch().getOrThrow().tracked().version).getOrThrow()
            fixture.backend.fetch().getOrThrow().tracked()
            val path = uploaded.knownPaths.first()
            if (remove) fixture.files.remove(path) else fixture.files[path] = byteArrayOf(1, 2)
            fixture.reads.clear()
            fixture.writes.clear()

            assertTrue(fixture.backend.fetch().isFailure)

            assertTrue(fixture.reads.any { it.url.pathSegments.last() == path })
            assertTrue(fixture.reads.none { it.url.pathSegments.last() in setOf("backup.json", "backup.bin") })
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test
    fun `WebDAV cached objects cannot conceal missing or corrupt remote archive objects`() = runTest {
        for (remove in listOf(false, true)) {
            val fixture = WebDavFixture()
            val uploaded = fixture.backend.upload(staged(payload), WebDavSyncBackend.Version(null, true)).getOrThrow()
            fixture.backend.fetch().getOrThrow().tracked()
            val path = uploaded.knownPaths.first()
            if (remove) fixture.files.remove(path) else fixture.files[path] = byteArrayOf(1, 2)
            fixture.reads.clear()
            fixture.writes.clear()

            assertTrue(fixture.backend.fetch().isFailure)

            assertTrue(fixture.reads.any { it.url.pathSegments.last() == path })
            assertTrue(fixture.reads.none { it.url.pathSegments.last() == "backup" })
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test
    fun `WebDAV mismatched immutable content cannot update the manifest`() = runTest {
        val fixture = WebDavFixture()
        val uploaded = fixture.backend.upload(staged(payload), WebDavSyncBackend.Version(null, true)).getOrThrow()
        val path = uploaded.knownPaths.first()
        fixture.files[path] = byteArrayOf(1, 2)
        fixture.writes.clear()

        val result = fixture.backend.upload(staged(payload), uploaded.copy(knownPaths = emptySet()))

        assertTrue(result.isFailure)
        assertTrue(fixture.writes.none { it.url.pathSegments.last() == SyncArchiveRepository.MANIFEST_FILE_NAME })
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
        val reads = mutableListOf<Request>()
        val writes = mutableListOf<Request>()
        val fileFailures = mutableMapOf<String, Int>()
        var headBody = """{"object":{"sha":"head"}}"""
        private val blobs = mutableMapOf<String, ByteArray>()
        private val pending = mutableMapOf<String, ByteArray?>()
        var failure = 0
        var followUps = 0
        var authorizeMigration: suspend (ByteArray) -> Unit = {}
        var currentObservations = 0
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            when {
                failure != 0 -> response(request, failure, "{}".toByteArray())
                path.contains("/contents/") -> {
                    reads += request
                    val name = path.substringAfterLast('/')
                    val bytes = files[name]
                    response(request, fileFailures[name] ?: if (bytes == null) 404 else 200, bytes ?: byteArrayOf())
                }
                path.endsWith("/git/ref/heads/main") -> response(request, 200, headBody.toByteArray())
                path.contains("/git/commits/") -> response(request, 200, """{"tree":{"sha":"tree"}}""".toByteArray())
                path.endsWith("/git/trees/tree") && request.method == "GET" -> {
                    val entries = JSONArray()
                    files.forEach { (name, bytes) -> entries.put(JSONObject().put("path", name)
                        .put("mode", "100644").put("type", "blob").put("sha", blobSha(bytes))) }
                    val listing = JSONObject().put("sha", "tree").put("tree", entries).put("truncated", false)
                    response(request, 200, listing.toString().toByteArray())
                }
                path == "/graphql" -> {
                    writes += request
                    val body = Buffer().use { request.body!!.writeTo(it); JSONObject(it.readUtf8()) }
                    val input = body.getJSONObject("variables").getJSONObject("input")
                    val update = input.getJSONArray("refUpdates").getJSONObject(0)
                    check(input.getString("repositoryId") == "repository-node")
                    check(update.getString("name") == "refs/heads/main")
                    check(!update.getBoolean("force"))
                    if (update.getString("beforeOid") != JSONObject(headBody).getJSONObject("object").getString("sha")) {
                        response(request, 200, """{"data":{"updateRefs":null},"errors":[{"type":"STALE_DATA","message":"Reference has changed"}]}""".toByteArray())
                    } else {
                        publishPending()
                        headBody = JSONObject().put("object", JSONObject().put("sha", update.getString("afterOid"))).toString()
                        val result = JSONObject().put("data", JSONObject().put("updateRefs", JSONObject().put("clientMutationId", input.optString("clientMutationId"))))
                        response(request, 200, result.toString().toByteArray())
                    }
                }
                path.endsWith("/git/refs/heads/main") -> {
                    publishPending()
                    response(request, 200, "{}".toByteArray())
                }
                request.method == "POST" -> {
                    writes += request
                    val body = Buffer().use { request.body!!.writeTo(it); JSONObject(it.readUtf8()) }
                    val sha = if (path.endsWith("/git/blobs")) {
                        val bytes = Base64.getDecoder().decode(body.getString("content"))
                        val sha = blobSha(bytes)
                        blobs[sha] = bytes
                        sha
                    } else {
                        if (path.endsWith("/git/trees")) {
                            val entries = body.getJSONArray("tree")
                            for (i in 0 until entries.length()) {
                                val entry = entries.getJSONObject(i)
                                pending[entry.getString("path")] = if (entry.isNull("sha")) null else blobs.getValue(entry.getString("sha"))
                            }
                        }
                        "commit"
                    }
                    response(request, 201, """{"sha":"$sha"}""".toByteArray())
                }
                else -> response(request, 200, """{"default_branch":"main","node_id":"repository-node"}""".toByteArray())
            }
        }.build()

        private fun publishPending() {
            pending.forEach { (path, bytes) -> if (bytes == null) files.remove(path) else files[path] = bytes }
            pending.clear()
        }

        private fun blobSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").run {
            update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
            digest(bytes).joinToString("") { "%02x".format(it) }
        }
        val backend = GitHubSyncBackend(storage, GitHubApiClient("test-token", client, "expired", "https://sync.test"),
            "owner", "repo", SyncRemoteSnapshotDecoder { it }, { IOException("invalid") }, { followUps++ },
            SyncArchiveRepository(temporary.newFolder()),
            authorizeLegacyMigration = { authorizeMigration(it) },
            currentProtocolObserved = { currentObservations++ }, metadataGuard = { write -> write(); true })
    }

    private inner class WebDavFixture {
        val webDavStorage = mock(WebDavStorage::class.java)
        val files = mutableMapOf<String, ByteArray>()
        val reads = mutableListOf<Request>()
        var readCode = 200
        var directoryCode = 207
        var writeCode = 201
        var body = content
        var followUps = 0
        var authorizeMigration: suspend (ByteArray) -> Unit = {}
        var currentObservations = 0
        val writes = mutableListOf<Request>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.method) {
                "LOCK" -> response(request, 405, byteArrayOf())
                "PUT" -> {
                    writes += request
                    val name = request.url.pathSegments.last()
                    val code = if (writeCode != 201) writeCode else if (
                        request.header("If-None-Match") == "*" && files.containsKey(name)
                    ) 412 else 201
                    if (code == 201) files[name] = Buffer().use { request.body!!.writeTo(it); it.readByteArray() }
                    response(request, code, byteArrayOf())
                }
                "PROPFIND" -> {
                    val directoryBody = if (directoryCode == 207) {
                        """
                            <d:multistatus xmlns:d="DAV:">
                                <d:response>
                                    <d:href>/</d:href>
                                    <d:propstat>
                                        <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                                        <d:status>HTTP/1.1 200 OK</d:status>
                                    </d:propstat>
                                </d:response>
                            </d:multistatus>
                        """.trimIndent().toByteArray()
                    } else byteArrayOf()
                    response(request, directoryCode, directoryBody)
                }
                else -> {
                    reads += request
                    val name = request.url.pathSegments.last()
                    val bytes = if (name == "backup") body else files[name]
                    val code = if (readCode != 200) readCode else if (bytes == null) 404 else 200
                    response(request, code, bytes ?: byteArrayOf())
                }
            }
        }.build()
        val backend = WebDavSyncBackend(webDavStorage, WebDavApiClient("test-user", "test-password", client, "auth"),
            "https://sync.test/backup", SyncRemoteSnapshotDecoder { it }, { IOException("invalid") }, { followUps++ },
            SyncArchiveRepository(temporary.newFolder()),
            authorizeLegacyMigration = { authorizeMigration(it) },
            currentProtocolObserved = { currentObservations++ }, metadataGuard = { write -> write(); true })
    }

    private fun response(request: Request, code: Int, body: ByteArray): Response = Response.Builder()
        .request(request).code(code).message("test").protocol(Protocol.HTTP_1_1)
        .header("ETag", "\"etag\"").body(body.toResponseBody()).build()
}
