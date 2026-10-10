package moe.ouom.neriplayer.data.sync.host

import java.io.Closeable
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.remote.WebDavArchiveGcState
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackCursor
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncBackend
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.mock

// 真实服务只接受 loopback 地址，每次测试独占新目录，避免触碰已有同步数据
@RunWith(Parameterized::class)
class WebDavProviderCompatibilityTest(private val providerName: String, private val baseUrl: String) {
    @get:Rule val temporary = TemporaryFolder()

    @Before
    fun requireLocalProvider() {
        assumeTrue("Set NERIPLAYER_WEBDAV_COMPAT_URLS to enable real provider tests", baseUrl.isNotEmpty())
        val endpoint = baseUrl.toHttpUrl()
        require(endpoint.host in setOf("127.0.0.1", "localhost", "::1")) { "Compatibility providers must use loopback" }
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty()) { "Use anonymous local providers" }
        require(endpoint.encodedQuery == null && endpoint.fragment == null) { "Use a collection URL without routing suffixes" }
    }

    @Test
    fun `fresh V4 upload restart change and competing stale publications preserve the current archive`() = runBlocking {
        ProviderFixture().use { fixture ->
            val writer = fixture.newBackend()
            val empty = writer.backend.fetch().getOrThrow()
            assertNull(empty.dataset)
            assertTrue(empty.version.createOnly)
            val otherEmpty = fixture.newBackend().backend.fetch().getOrThrow()
            assertNull(otherEmpty.dataset)

            val initial = payload("initial")
            val first = writer.archive.playbackDatasets.fromLegacy(initial).use {
                writer.backend.upload(it, empty.version).getOrThrow()
            }
            assertNotNull(first.lastKnownFingerprint)
            assertEquals(4, fixture.remoteProtocol())

            val restarted = fixture.newBackend()
            val fetched = restarted.backend.fetch().getOrThrow()
            assertFalse(fetched.requiresMigrationUpload)
            requireNotNull(fetched.dataset).use { assertDataset(initial, it) }

            val changed = payload("changed")
            val second = restarted.archive.playbackDatasets.fromLegacy(changed).use {
                restarted.backend.upload(it, fetched.version).getOrThrow()
            }
            assertTrue("$providerName publishes a new version", first.lastKnownFingerprint != second.lastKnownFingerprint)
            val committed = fixture.api.getFileContentStrict(fixture.manifestUrl).getOrThrow().fingerprint

            for (stale in listOf(first, otherEmpty.version)) {
                val staleWriter = fixture.newBackend()
                val result = staleWriter.archive.playbackDatasets.fromLegacy(payload("stale overwrite")).use {
                    staleWriter.backend.upload(it, stale)
                }
                assertTrue("$providerName rejects a stale publication", result.exceptionOrNull() is WebDavContentConflictException)
                assertEquals(committed, fixture.api.getFileContentStrict(fixture.manifestUrl).getOrThrow().fingerprint)
            }

            repeat(3) {
                val reader = fixture.newBackend()
                val snapshot = reader.backend.fetch().getOrThrow()
                requireNotNull(snapshot.dataset).use { assertDataset(changed, it) }
            }
            assertTrue("$providerName retains finite lease support after reopening", fixture.lockSupported)
        }
    }

    @Test
    fun `V3 migration preserves complete edited lyrics and playback statistics across V4 reopening`() = runBlocking {
        ProviderFixture().use { fixture ->
            fixture.api.updateFileContent(fixture.objectUrl(V3_OBJECT_PATH), Base64.getDecoder().decode(V3_OBJECT), createOnly = true).getOrThrow()
            fixture.api.updateFileContent(fixture.manifestUrl, Base64.getDecoder().decode(V3_MANIFEST), createOnly = true).getOrThrow()
            assertEquals(3, fixture.remoteProtocol())

            val migrating = fixture.newBackend()
            val legacy = migrating.backend.fetch().getOrThrow()
            assertTrue(legacy.requiresMigrationUpload)
            val expected = legacyPayload()
            requireNotNull(legacy.dataset).use {
                assertDataset(expected, it)
                migrating.backend.upload(it, legacy.version).getOrThrow()
            }
            assertEquals(1, fixture.migrationAuthorizations)
            assertEquals(4, fixture.remoteProtocol())

            repeat(3) {
                val upgraded = fixture.newBackend().backend.fetch().getOrThrow()
                assertFalse(upgraded.requiresMigrationUpload)
                requireNotNull(upgraded.dataset).use { assertDataset(expected, it) }
            }
            assertEquals(1, fixture.migrationAuthorizations)
            assertTrue("$providerName releases the migration lease before subsequent syncs", fixture.lockSupported)
        }
    }

    private suspend fun assertDataset(expected: SyncData, actual: SyncDataset) {
        assertEquals(expected.copy(playbackStats = emptyList(), playbackStatBuckets = emptyList()), actual.data)
        assertEquals(expected.playbackStats, actual.playback.openTracks().readAll())
        assertEquals(expected.playbackStatBuckets, actual.playback.openBuckets().readAll())
    }

    private suspend fun <T> SyncPlaybackCursor<T>.readAll(): List<T> = use { cursor ->
        buildList {
            while (true) {
                val page = cursor.nextPage()
                if (page.isEmpty()) break
                addAll(page)
            }
        }
    }

    private fun payload(name: String) = SyncSongLyricMergePolicy.converge(SyncData(
        deviceId = "provider-v4", lastModified = 2_000,
        playlists = listOf(SyncPlaylist(id = 1, name = name, songs = listOf(SyncSong(
            id = 42, name = "song", lyricSyncEdited = true, lyricSyncRevision = 100,
            matchedLyric = "[00:01.00]完整歌词\n[00:02.00]第二行", matchedTranslatedLyric = "complete translation",
            matchedRomanizedLyric = "complete romanization", originalLyric = "original baseline"
        )))),
        playbackStats = listOf(SyncTrackStat(id = 42, identityKey = "netease:42", playCount = 7, totalListenMs = 123_456)),
        playbackStatBuckets = listOf(SyncPlaybackStatBucket(id = 42, identityKey = "netease:42", dayStartAt = 1_000,
            playCount = 3, totalListenMs = 7_890))
    ))

    private fun legacyPayload() = SyncSongLyricMergePolicy.converge(SyncData(
        deviceId = "provider-v3", lastModified = 1_000,
        playlists = listOf(SyncPlaylist(id = 1, name = "legacy", songs = listOf(SyncSong(
            id = 42, name = "legacy song", lyricSyncEdited = true, lyricSyncRevision = 100,
            matchedLyric = "[00:01.00]旧歌词全文\n[00:02.00]第二行", matchedTranslatedLyric = "complete translation",
            matchedRomanizedLyric = "complete romanization", originalLyric = "original baseline"
        )))),
        playbackStats = listOf(SyncTrackStat(id = 42, identityKey = "netease:42", name = "legacy song", playCount = 7, totalListenMs = 123_456)),
        playbackStatBuckets = listOf(SyncPlaybackStatBucket(id = 42, identityKey = "netease:42", dayStartAt = 1_000,
            playCount = 3, totalListenMs = 7_890))
    ))

    private inner class ProviderFixture : Closeable {
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        private val collection: HttpUrl = (baseUrl.trimEnd('/') + '/').toHttpUrl().newBuilder()
            .addPathSegment("neriplayer-compat-${UUID.randomUUID()}").addPathSegment("").build()
        private val remoteUrl = requireNotNull(collection.resolve("neriplayer-sync.json")).toString()
        val manifestUrl = WebDavApiClient.buildSiblingFileUrl(remoteUrl, SyncArchiveRepository.MANIFEST_FILE_NAME)
        val api = WebDavApiClient("compatibility-test", "local-fixture-only", client, "Local fixture authentication failed")
        var lockSupported = false
        var migrationAuthorizations = 0
        private var gcState = WebDavArchiveGcState()
        private val storage = mock(WebDavStorage::class.java) { call ->
            when (call.method.name) {
                "archiveLockSupported" -> lockSupported
                "rememberArchiveLock" -> { lockSupported = true; null }
                "archiveGcState" -> gcState
                "saveArchiveGcState" -> { gcState = call.arguments[1] as WebDavArchiveGcState; null }
                else -> null
            }
        }

        init {
            try { requireCollectionResponse("MKCOL", setOf(201)) }
            catch (failure: Throwable) { shutdownClient(); throw failure }
        }

        fun objectUrl(path: String): String = WebDavApiClient.buildSiblingFileUrl(remoteUrl, path)
        fun remoteProtocol(): Int = SyncArchiveRepository.protocolVersion(api.getFileContentStrict(manifestUrl).getOrThrow().content)

        fun newBackend(): BackendFixture {
            // 重开归档仓库，避免缓存掩盖真实远端闭包读取失败
            val archive = SyncArchiveRepository(temporary.newFolder())
            val backend = WebDavSyncBackend(storage, api, remoteUrl, SyncRemoteSnapshotDecoder { it },
                { IOException("Invalid compatibility fixture") }, {}, archive,
                authorizeLegacyMigration = {
                    assertEquals(3, SyncArchiveRepository.protocolVersion(it))
                    migrationAuthorizations++
                }, uptimeMs = { System.nanoTime() / 1_000_000 }, metadataGuard = { write -> write(); true })
            return BackendFixture(archive, backend)
        }

        override fun close() {
            try { requireCollectionResponse("DELETE", setOf(200, 204)) }
            finally { shutdownClient() }
        }

        private fun requireCollectionResponse(method: String, accepted: Set<Int>) {
            val request = Request.Builder().url(collection).header("Authorization", Credentials.basic("compatibility-test", "local-fixture-only"))
                .method(method, null).build()
            client.newCall(request).execute().use {
                if (it.code !in accepted) throw IOException("$providerName isolated collection $method failed with HTTP ${it.code}")
            }
        }

        private fun shutdownClient() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private data class BackendFixture(val archive: SyncArchiveRepository, val backend: WebDavSyncBackend)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun providers(): Collection<Array<String>> {
            val urls = System.getenv("NERIPLAYER_WEBDAV_COMPAT_URLS").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            return if (urls.isEmpty()) listOf(arrayOf("not configured", ""))
            else urls.mapIndexed { index, url -> arrayOf("provider-${index + 1}", url) }
        }

        // 固定 V3 fixture 包含歌单、歌词和两种统计，ZSTD 使用协议允许的 windowLog 20
        private const val V3_OBJECT_PATH = "neriplayer-sync-v3-b97d55528d3d908171d4f4beafefe87ca849c1ec92332bf726a6c51ed40b6b42.zst"
        private const val V3_MANIFEST = "TlBTWU5DMDMAAACoYiiX5kyIXcbc64it14G3AEKc1VOIwYgwatn4Q8wk9DUotS/9BFBdBACCyiEesLkBLEiSBFam0UMsU+QZ0EWK7O7N44RjKoch2zYKi61qCZRFKatC6rc/1pxC+3a62HxrbckO2eE3i+zqY97akw8j+3900tvfqGzLLmwOfjVW9q43aCONLL+yc9zWm+03qZXT3596ff9uk22yvu5OhhBe2bUh1pQIleIkJvLrSE8EFYdxAEMASaASMQ=="
        private const val V3_OBJECT = "KLUv/QRQTQYAJAsBAAAACggBEgZsZWdhY3kCAAAAFQgqEgsgc29uZ/ABZPgBAQgAAAAhCgpuZXRlYXNlOjQyKMDEBzAHYCoJAAAAFgjoBxIw0j04A2gqDwAAAHkIKlItWzAwOjAxLjAwXeaXp+atjOivjeWFqOaWhwoy56ys5LqM6KGMWhRjb21wbGV0ZSB0cmFuc2xhdGlvbqoBEW9yaWdpbmFsIGJhc2VsaW5lggIVcm9tYW5pemF0aW9uBwBUyxiVGQDB1PPqTBmAo05GhokHtu1jLw=="
    }
}
