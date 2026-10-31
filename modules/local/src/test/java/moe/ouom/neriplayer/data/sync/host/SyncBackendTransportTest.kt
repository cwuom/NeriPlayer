package moe.ouom.neriplayer.data.sync.host

import android.content.SharedPreferences
import moe.ouom.neriplayer.common.storage.VolatileSharedPreferences
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.github.GitHubContentConflictException
import moe.ouom.neriplayer.api.sync.github.GitHubFileNotFoundException
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException
import moe.ouom.neriplayer.api.sync.webdav.WebDavFileNotFoundException
import moe.ouom.neriplayer.api.sync.webdav.WebDavMissingConcurrencyTokenException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
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
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataStore
import moe.ouom.neriplayer.data.sync.runtime.SyncSession
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.Base64
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.io.File
import kotlinx.coroutines.runBlocking

class SyncBackendTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ownedDatasets = ArrayList<SyncDataset>()
    private val ownedClients = ArrayList<OkHttpClient>()
    @After fun releaseResources() {
        try { ownedDatasets.forEach { it.close() } } finally {
            ownedClients.forEach { it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        }
    }
    private suspend fun staged(data: SyncData): SyncDataset =
        FileSyncPlaybackDatasetStore(temporary.newFolder()).fromLegacy(data).also(ownedDatasets::add)
    private fun <T> SyncDatasetRemoteSnapshot<T>.tracked(): SyncDatasetRemoteSnapshot<T> =
        also { it.dataset?.let(ownedDatasets::add) }
    private val payload = SyncData(deviceId = "device", lastModified = 10,
        playlists = listOf(SyncPlaylist(id = 1, name = "playlist", songs = listOf(SyncSong(id = 2, name = "song")))))
    private val content = SyncDataSerializer.serialize(payload, false)
    private val githubHead = "1".repeat(40)
    private val githubCommit = "2".repeat(40)

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
        assertSame(required, fixture.backend.refetch(GitHubSyncBackend.Version(githubHead, "backup.json")).exceptionOrNull())
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
        assertEquals(githubHead, fallback.version.sha)
        assertTrue(fixture.reads.all { it.url.queryParameter("ref") == githubHead })
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
        assertEquals(githubCommit, version.sha)
        assertEquals(SyncArchiveRepository.MANIFEST_FILE_NAME, version.fileName)
        val publication = fixture.writes.single()
        assertEquals("/graphql", publication.url.encodedPath)
        val publicationBody = Buffer().use { publication.body!!.writeTo(it); JSONObject(it.readUtf8()) }
        assertTrue(publicationBody.getString("query").contains("createCommitOnBranch"))
        val input = publicationBody.getJSONObject("variables").getJSONObject("input")
        assertEquals(githubHead, input.getString("expectedHeadOid"))
        val additions = input.getJSONObject("fileChanges").getJSONArray("additions")
        assertEquals(version.knownPaths + SyncArchiveRepository.MANIFEST_FILE_NAME,
            (0 until additions.length()).map { additions.getJSONObject(it).getString("path") }.toSet())
        assertEquals(version.knownPaths.size + 1, additions.length())
        fixture.backend.saveRemoteVersion(version)
        fixture.backend.saveRemoteVersion(version.copy(sha = null))
        fixture.backend.saveSyncTime(42)
        fixture.backend.scheduleFollowUp()
        fixture.backend.onFailure(IOException("offline"))
        verify(fixture.storage).saveLastRemoteSha(githubCommit)
        verify(fixture.storage).saveLastSyncTime(42)
        assertEquals(1, fixture.followUps)
        fixture.failure = 409
        val failed = fixture.backend.upload(staged(payload), version)
        assertTrue(failed.isFailure)
        assertTrue(fixture.backend.isConflict(GitHubContentConflictException(409, "changed")))
        assertFalse(fixture.backend.isConflict(null))
    }

    @Test
    fun `GitHub unauthorized response preserves saved credentials and repository configuration`() = runTest {
        val store = tokenStorage()
        store.saveToken("test-token")
        store.saveRepository("owner", "repo")
        store.setAutoSyncEnabled(true)
        store.saveLastRemoteSha("previous-sha")
        store.saveLastSyncTime(50L)
        val configuration = store.snapshot()
        val fixture = GitHubFixture(storage = store, metadataGuard = store.captureSyncMetadataGuard())
        fixture.failure = 401

        val error = checkNotNull(fixture.backend.fetch().exceptionOrNull())
        assertTrue(error is TokenExpiredException)
        fixture.backend.onFailure(error)

        assertEquals(configuration.copy(autoSyncEnabled = false), store.snapshot())
        assertTrue(store.isConfigured())
        assertEquals("previous-sha", store.getLastRemoteSha())
        assertEquals(50L, store.getLastSyncTime())
    }

    @Test
    fun `GitHub unauthorized result from an old request cannot clear replacement credentials`() = runTest {
        val store = tokenStorage()
        store.saveToken("test-token")
        store.saveRepository("owner", "repo")
        val fixture = GitHubFixture(storage = store, metadataGuard = store.captureSyncMetadataGuard())
        fixture.failure = 401
        val error = checkNotNull(fixture.backend.fetch().exceptionOrNull())
        assertTrue(error is TokenExpiredException)
        assertEquals("Bearer test-token", fixture.requests.single().header("Authorization"))
        store.saveToken("replacement-token")
        store.saveRepository("replacement-owner", "replacement-repo")
        val replacement = store.snapshot()

        fixture.backend.onFailure(error)

        assertEquals(replacement, store.snapshot())
        assertTrue(store.isConfigured())
    }

    @Test
    fun `GitHub reuses immutable uploads and verifies cached objects against the fixed remote tree`() = runTest {
        val fixture = GitHubFixture()
        val empty = fixture.backend.fetch().getOrThrow().tracked()
        assertEquals(githubHead, empty.version.sha)
        val uploaded = fixture.backend.upload(staged(payload), empty.version).getOrThrow()
        assertTrue(uploaded.knownPaths.isNotEmpty())
        val previousObjects = uploaded.knownPaths.associateWith { fixture.files.getValue(it).copyOf() }
        fixture.writes.clear()

        val repeated = fixture.backend.upload(staged(payload), uploaded).getOrThrow()

        val publication = fixture.writes.single()
        assertEquals("/graphql", publication.url.encodedPath)
        val publicationBody = Buffer().use { publication.body!!.writeTo(it); JSONObject(it.readUtf8()) }
        val input = publicationBody.getJSONObject("variables").getJSONObject("input")
        assertEquals(githubCommit, input.getString("expectedHeadOid"))
        val additions = input.getJSONObject("fileChanges").getJSONArray("additions")
        assertEquals(1, additions.length())
        assertEquals(SyncArchiveRepository.MANIFEST_FILE_NAME, additions.getJSONObject(0).getString("path"))
        previousObjects.forEach { (path, bytes) -> assertArrayEquals(bytes, fixture.files.getValue(path)) }
        fixture.reads.clear()
        assertEquals(payload, fixture.backend.fetch().getOrThrow().tracked().dataset?.data)
        assertEquals(setOf(SyncArchiveRepository.MANIFEST_FILE_NAME),
            fixture.reads.map { it.url.pathSegments.last() }.toSet())
        assertEquals(1, fixture.reads.size)
        assertTrue(fixture.reads.all { it.url.queryParameter("ref") == repeated.sha })
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
        fixture.headBody = """{"object":{"sha":"${"3".repeat(40)}"}}"""
        val expectedHead = "4".repeat(40)

        val result = fixture.backend.upload(staged(payload), GitHubSyncBackend.Version(expectedHead, "backup.json"))
        assertTrue(result.exceptionOrNull() is GitHubContentConflictException)

        val publication = fixture.writes.single()
        assertEquals("/graphql", publication.url.encodedPath)
        val publicationBody = Buffer().use { publication.body!!.writeTo(it); JSONObject(it.readUtf8()) }
        val input = publicationBody.getJSONObject("variables").getJSONObject("input")
        assertEquals(expectedHead, input.getString("expectedHeadOid"))
        assertEquals("main", input.getJSONObject("branch").getString("branchName"))
        assertEquals("owner/repo", input.getJSONObject("branch").getString("repositoryNameWithOwner"))
        assertTrue(fixture.writes.none { it.method == "PATCH" })
        assertTrue(fixture.files.isEmpty())
    }

    @Test
    fun `GitHub strict publication rejects an external branch rollback without replacing the committed snapshot`() = runTest {
        val fixture = GitHubFixture()
        val version = fixture.backend.upload(staged(payload), fixture.backend.fetch().getOrThrow().tracked().version).getOrThrow()
        val committedFiles = fixture.files.toMap()
        fixture.headBody = """{"object":{"sha":"$githubHead"}}"""
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
        val withoutToken = WebDavSyncBackend.Version(WebDavConcurrencyToken(), false)
        for (token in listOf(WebDavConcurrencyToken(), WebDavConcurrencyToken(etag = "W/\"weak\""),
            WebDavConcurrencyToken(etag = "\"first\", \"second\""), WebDavConcurrencyToken(etag = "\"space inside\""),
            WebDavConcurrencyToken(lastModified = "Thu, 01 Oct 2026 00:00:00 GMT"))) {
            assertTrue(fixture.backend.upload(staged(payload), withoutToken.copy(token = token)).exceptionOrNull()
                is WebDavMissingConcurrencyTokenException)
            // 只有内容指纹时必须先确认远端仍是同一版本，远端缺失或变化都不能写入
            assertTrue(fixture.backend.upload(staged(payload), withoutToken.copy(token = token, lastKnownFingerprint = fingerprint))
                .exceptionOrNull() is WebDavContentConflictException)
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

        val republished = fixture.backend.upload(staged(payload), uploaded.copy(knownPaths = emptySet())).getOrThrow()

        assertTrue(fixture.writes.size > 1)
        assertTrue(fixture.writes.dropLast(1).all { it.header("If-None-Match") == "*" })
        fixture.writes.clear()
        fixture.backend.upload(staged(payload), republished).getOrThrow()
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

            val result = fixture.backend.fetch()
            assertTrue(result.isFailure)

            if (remove) {
                assertTrue(result.exceptionOrNull() is GitHubFileNotFoundException)
                assertTrue(fixture.reads.none { it.url.pathSegments.last() == path })
            } else {
                assertTrue(fixture.reads.any { it.url.pathSegments.last() == path })
            }
            assertTrue(fixture.reads.none { it.url.pathSegments.last() in setOf("backup.json", "backup.bin") })
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test
    fun `GitHub fixed tree proof cannot approve mismatched object responses`() = runTest {
        val fixture = GitHubFixture()
        val version = fixture.backend.upload(staged(payload), fixture.backend.fetch().getOrThrow().tracked().version).getOrThrow()
        val path = version.knownPaths.first()
        fixture.objectIdOverrides[path] = "f".repeat(40)
        fixture.reads.clear()

        val result = fixture.backend.fetch()

        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("differs from its fixed commit tree"))
        assertTrue(fixture.reads.any { it.url.pathSegments.last() == path })
        assertTrue(fixture.reads.none { it.url.pathSegments.last() in setOf("backup.json", "backup.bin") })
    }

    @Test
    fun `GitHub repairs a corrupt cached object while reusing the rest`() = runTest {
        val fixture = GitHubFixture()
        val version = fixture.backend.upload(staged(payload), fixture.backend.fetch().getOrThrow().tracked().version).getOrThrow()
        val path = version.knownPaths.first()
        val cached = File(fixture.cacheDirectory, path)
        val corrupted = cached.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        cached.writeBytes(corrupted)
        fixture.reads.clear()

        assertEquals(payload, fixture.backend.fetch().getOrThrow().tracked().dataset?.data)

        assertEquals(setOf(SyncArchiveRepository.MANIFEST_FILE_NAME, path), fixture.reads.map { it.url.pathSegments.last() }.toSet())
        assertEquals(2, fixture.reads.size)
        assertArrayEquals(fixture.files.getValue(path), cached.readBytes())
    }

    @Test
    fun `GitHub rechecks the tree at a new head and in a different repository`() = runTest {
        val fixture = GitHubFixture()
        val version = fixture.backend.upload(staged(payload), fixture.backend.fetch().getOrThrow().tracked().version).getOrThrow()
        fixture.headBody = """{"object":{"sha":"${"7".repeat(40)}"}}"""
        fixture.requests.clear()

        assertEquals(payload, fixture.backend.fetch().getOrThrow().tracked().dataset?.data)
        assertEquals(1, fixture.requests.count { it.url.encodedPath.contains("/contents/") })
        assertTrue(fixture.requests.any { it.url.encodedPath.endsWith("/branches/main") })
        assertTrue(fixture.requests.any { it.url.encodedPath.endsWith("/git/trees/${"5".repeat(40)}") })
        assertEquals("7".repeat(40), fixture.requests.single { it.url.encodedPath.contains("/contents/") }.url.queryParameter("ref"))

        val other = GitHubFixture(fixture.cacheDirectory, "other")
        other.files[SyncArchiveRepository.MANIFEST_FILE_NAME] = fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)
        assertTrue(other.backend.fetch().exceptionOrNull() is GitHubFileNotFoundException)
        assertEquals(1, other.reads.size)
        assertTrue(other.requests.all { it.url.pathSegments.take(3) == listOf("repos", "owner", "other") })
        assertTrue(version.knownPaths.all { File(fixture.cacheDirectory, it).exists() })
    }

    @Test
    fun `GitHub publication reuses the read proof while removing all obsolete owned objects`() = runTest {
        val fixture = GitHubFixture()
        fixture.backend.upload(staged(payload), fixture.backend.fetch().getOrThrow().tracked().version).getOrThrow()
        val obsolete = "neriplayer-sync-v4-${"9".repeat(64)}.zst"
        fixture.files[obsolete] = "unreferenced object".toByteArray()
        val remote = fixture.backend.fetch().getOrThrow().tracked()
        assertFalse(obsolete in remote.version.knownPaths)
        fixture.requests.clear()

        fixture.backend.upload(staged(payload), remote.version).getOrThrow()

        assertEquals(1, fixture.requests.size)
        assertEquals("/graphql", fixture.requests.single().url.encodedPath)
        assertFalse(fixture.files.containsKey(obsolete))
        assertTrue(remote.version.knownPaths.all(fixture.files::containsKey))
    }

    @Test
    fun `complete warm GitHub sessions use bounded requests with simulated network latency`() = runBlocking {
        val fixture = GitHubFixture()
        fixture.persistMetadata()
        val clean = payload.copy(playlists = listOf(payload.playlists.single().copy(
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION,
            songs = List(1000) { index -> SyncSong(id = index + 2L, name = "song-$index",
                syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION) }
        )))
        val merger = SyncDataMerger(mock(SyncMergeHost::class.java) { call ->
            when (call.method.name) {
                "getMergeSuccessMessage" -> "merged"
                "getInitialUploadMessage" -> "uploaded"
                "localRenameMessage", "remoteRenameMessage" -> "renamed ${call.arguments[0]}"
                else -> null
            }
        }) { 10L }
        val initial = merger.merge(clean, clean, 0L).mergedData
        val version = fixture.backend.upload(staged(initial), fixture.backend.fetch().getOrThrow().tracked().version).getOrThrow()
        fixture.backend.saveRemoteVersion(version)
        fixture.backend.saveSyncTime(10L)
        var data = initial
        var applied: SyncData? = null
        val local = object : SyncLocalDataStore {
            override suspend fun awaitInitialized() = true
            override fun mutationVersion() = 1L
            override suspend fun snapshot() = fixture.archive.playbackDatasets.fromLegacy(data)
            override suspend fun apply(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
                assertEquals(1L, expectedMutationVersion)
                applied = dataset.data
                return true
            }
        }
        val session = SyncSession(local, merger, fixture.archive.playbackDatasets,
            "unchanged", "uploaded", { IOException("busy") }, nowMs = { 20L }, deferredMessage = "pending")
        fixture.requestDelayMs = 400L
        fixture.requests.clear()
        val unchangedStart = System.nanoTime()
        val unchanged = session.execute { fixture.backend }.getOrThrow()
        val unchangedMs = (System.nanoTime() - unchangedStart) / 1_000_000L
        assertTrue(unchanged.success)
        assertEquals(initial.playlists, requireNotNull(applied).playlists)
        assertEquals(4, fixture.requests.size)
        assertEquals(1, fixture.requests.count { it.url.encodedPath.contains("/contents/") })
        assertTrue(fixture.requests.all { it.method == "GET" })

        data = initial.copy(playlists = listOf(initial.playlists.single().copy(name = "renamed", modifiedAt = 30L)))
        fixture.requests.clear()
        val changedStart = System.nanoTime()
        val changed = session.execute { fixture.backend }.getOrThrow()
        val changedMs = (System.nanoTime() - changedStart) / 1_000_000L
        assertTrue(changed.success)
        assertEquals("renamed", requireNotNull(applied).playlists.single().name)
        assertEquals(5, fixture.requests.size)
        assertEquals(1, fixture.requests.count { it.method == "POST" })
        assertEquals(1, fixture.requests.count { it.url.encodedPath == "/graphql" })
        println("GitHub complete session: 1000 songs, ${version.knownPaths.size} objects, 400ms/request, unchanged=${unchangedMs}ms (4 requests), one edit=${changedMs}ms (5 requests)")
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

    private fun tokenStorage(): SecureTokenStorage = SecureTokenStorage::class.java.getDeclaredConstructor(
        SharedPreferences::class.java, File::class.java, Function1::class.java
    ).newInstance(VolatileSharedPreferences.shared("backend-credentials-${UUID.randomUUID()}"), null, null)

    private inner class GitHubFixture(
        val cacheDirectory: File = temporary.newFolder(),
        private val repoName: String = "repo",
        val storage: SecureTokenStorage = mock(SecureTokenStorage::class.java),
        metadataGuard: (() -> Unit) -> Boolean = { write -> write(); true }
    ) {
        val files = mutableMapOf<String, ByteArray>()
        val reads = CopyOnWriteArrayList<Request>()
        val writes = CopyOnWriteArrayList<Request>()
        val requests = CopyOnWriteArrayList<Request>()
        val objectIdOverrides = mutableMapOf<String, String>()
        var requestDelayMs = 0L
        val fileFailures = mutableMapOf<String, Int>()
        var headBody = """{"object":{"sha":"$githubHead"}}"""
        private val blobs = mutableMapOf<String, ByteArray>()
        private val pending = mutableMapOf<String, ByteArray?>()
        private var commitNumber = 1
        var failure = 0
        var followUps = 0
        var authorizeMigration: suspend (ByteArray) -> Unit = {}
        var currentObservations = 0
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            if (requestDelayMs > 0) Thread.sleep(requestDelayMs)
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
                path.endsWith("/branches/main") -> {
                    val head = JSONObject(headBody).getJSONObject("object").getString("sha")
                    response(request, 200, """{"name":"main","commit":{"sha":"$head","commit":{"tree":{"sha":"${"5".repeat(40)}"}}}}""".toByteArray())
                }
                path.contains("/git/commits/") -> response(request, 200, """{"tree":{"sha":"${"5".repeat(40)}"}}""".toByteArray())
                path.endsWith("/git/trees/${"5".repeat(40)}") && request.method == "GET" -> {
                    val entries = JSONArray()
                    files.forEach { (name, bytes) -> entries.put(JSONObject().put("path", name)
                        .put("mode", "100644").put("type", "blob").put("sha", objectIdOverrides[name] ?: blobSha(bytes))) }
                    val listing = JSONObject().put("sha", "5".repeat(40)).put("tree", entries).put("truncated", false)
                    response(request, 200, listing.toString().toByteArray())
                }
                path == "/graphql" -> {
                    writes += request
                    val body = Buffer().use { request.body!!.writeTo(it); JSONObject(it.readUtf8()) }
                    val input = body.getJSONObject("variables").getJSONObject("input")
                    if (body.getString("query").contains("createCommitOnBranch")) {
                        return@addInterceptor publishBatch(request, input)
                    }
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
                        if (path.endsWith("/git/commits")) nextCommit() else githubCommit
                    }
                    response(request, 201, """{"sha":"$sha"}""".toByteArray())
                }
                else -> response(request, 200, """{"default_branch":"main","node_id":"repository-node"}""".toByteArray())
            }
        }.build()

        init { ownedClients += client }

        private fun publishBatch(request: Request, input: JSONObject): Response {
            val branch = input.getJSONObject("branch")
            check(branch.getString("branchName") == "main")
            check(branch.getString("repositoryNameWithOwner") == "owner/$repoName")
            val expected = input.getString("expectedHeadOid")
            if (expected != JSONObject(headBody).getJSONObject("object").getString("sha")) {
                return response(request, 200,
                    """{"data":{"createCommitOnBranch":null},"errors":[{"type":"STALE_DATA","message":"Reference has changed"}]}""".toByteArray())
            }
            val next = LinkedHashMap(files)
            val changes = input.getJSONObject("fileChanges")
            val additions = changes.getJSONArray("additions")
            for (index in 0 until additions.length()) {
                val addition = additions.getJSONObject(index)
                val encoded = addition.getString("contents")
                val bytes = Base64.getDecoder().decode(encoded)
                check(Base64.getEncoder().encodeToString(bytes) == encoded)
                next[addition.getString("path")] = bytes
            }
            val deletions = changes.getJSONArray("deletions")
            for (index in 0 until deletions.length()) next.remove(deletions.getJSONObject(index).getString("path"))
            val commit = nextCommit()
            files.clear()
            files.putAll(next)
            headBody = """{"object":{"sha":"$commit"}}"""
            val result = JSONObject().put("clientMutationId", input.getString("clientMutationId"))
                .put("commit", JSONObject().put("oid", commit)
                    .put("parents", JSONObject().put("nodes", JSONArray().put(JSONObject().put("oid", expected)))))
                .put("ref", JSONObject().put("name", "main").put("prefix", "refs/heads/")
                    .put("repository", JSONObject().put("nameWithOwner", "owner/$repoName"))
                    .put("target", JSONObject().put("oid", commit)))
            val body = JSONObject().put("data", JSONObject().put("createCommitOnBranch", result))
            return response(request, 200, body.toString().toByteArray())
        }

        private fun nextCommit(): String {
            val number = ++commitNumber
            return if (number == 2) githubCommit else number.toString(16).padStart(40, '0')
        }

        private fun publishPending() {
            pending.forEach { (path, bytes) -> if (bytes == null) files.remove(path) else files[path] = bytes }
            pending.clear()
        }

        private fun blobSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").run {
            update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
            digest(bytes).joinToString("") { "%02x".format(it) }
        }
        fun persistMetadata() {
            var sha: String? = null
            var syncTime = 0L
            `when`(storage.getLastRemoteSha()).thenAnswer { sha }
            `when`(storage.getLastSyncTime()).thenAnswer { syncTime }
            doAnswer { sha = it.getArgument(0); null }.`when`(storage).saveLastRemoteSha(anyString())
            doAnswer { syncTime = it.getArgument(0); null }.`when`(storage).saveLastSyncTime(anyLong())
        }

        val archive = SyncArchiveRepository(cacheDirectory)
        val backend = GitHubSyncBackend(storage, GitHubApiClient("test-token", client, "expired", "https://sync.test"),
            "owner", repoName, SyncRemoteSnapshotDecoder { it }, { IOException("invalid") }, { followUps++ },
            archive,
            authorizeLegacyMigration = { authorizeMigration(it) },
            currentProtocolObserved = { currentObservations++ }, metadataGuard = metadataGuard)
    }

    private inner class WebDavFixture {
        val webDavStorage = mock(WebDavStorage::class.java)
        val files = mutableMapOf<String, ByteArray>()
        val reads = CopyOnWriteArrayList<Request>()
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
