package moe.ouom.neriplayer.data.sync.host

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncBackend
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import moe.ouom.neriplayer.data.sync.remote.WebDavArchiveGcCandidate
import moe.ouom.neriplayer.data.sync.remote.WebDavArchiveGcJournal
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import moe.ouom.neriplayer.data.sync.remote.WebDavArchiveGcState

class WebDavArchiveGcHttpTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = SyncData(deviceId = "lease-test", playlists = listOf(
        SyncPlaylist(id = 1L, name = "playlist", songs = listOf(SyncSong(id = 2L, name = "song")))
    ))

    @Test
    fun `identical uploads invalidate a writer that verified objects before publication`() = runTest {
        val fixture = Fixture()
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            val previous = fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME).copyOf()
            val second = fixture.backend.upload(data, first).getOrThrow()

            assertFalse(previous.contentEquals(fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)))
            assertFalse(first.token!!.etag == second.token!!.etag)
            val stale = fixture.api.updateFileContent(fixture.manifestUrl, previous, expectedVersion = first.token)
            assertEquals(412, (stale.exceptionOrNull() as moe.ouom.neriplayer.api.sync.webdav.WebDavApiException).statusCode)
        }
    }

    @Test
    fun `object upload and manifest publication share one exclusive collection lease`() = runTest {
        val fixture = Fixture()
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
        }

        assertEquals("LOCK", fixture.requests.first().method)
        assertEquals("UNLOCK", fixture.requests.last().method)
        assertEquals("infinity", fixture.requests.first().header("Depth"))
        assertTrue(fixture.requests.filter { it.method == "PUT" }.all { it.header("If")?.contains(TOKEN) == true })
        assertFalse(fixture.locked)
    }

    @Test
    fun `an active backend keeps its captured maintenance account when preferences change`() = runTest {
        val fixture = Fixture()
        val captured = fixture.api.archiveMaintenanceScope(fixture.manifestUrl)
        fixture.beforeRequest = { if (it.method == "LOCK") fixture.currentUsername = "new-account" }
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
        }
        assertEquals("new-account", fixture.currentUsername)
        assertTrue(fixture.maintenanceScopes.isNotEmpty())
        assertTrue(fixture.maintenanceScopes.all { it == captured })
    }

    @Test
    fun `manifest and full closure reads remain inside the collection lease`() = runTest {
        val fixture = Fixture()
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
        }
        fixture.requests.clear()

        fixture.backend.fetch().getOrThrow().dataset!!.use { assertEquals(payload, it.data) }

        assertEquals("LOCK", fixture.requests.first().method)
        assertEquals("UNLOCK", fixture.requests.last().method)
        assertTrue(fixture.requests.filter { it.method == "GET" }.all { it.header("If")?.contains(TOKEN) == true })
        assertFalse(fixture.locked)
    }

    @Test
    fun `query routing is retained through strong ETag sync without optional GC`() = runTest {
        val fixture = Fixture("?route=fixture#display")
        val orphan = fixture.addOrphan()
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
            fixture.backend.upload(data, first).getOrThrow()
        }
        fixture.backend.fetch().getOrThrow().dataset!!.use { assertEquals(payload, it.data) }
        assertTrue(fixture.requests.all { it.url.query == "route=fixture" })
        assertTrue(fixture.requests.none { it.method in setOf("LOCK", "UNLOCK", "PROPFIND", "DELETE") })
        assertTrue(fixture.files.containsKey(orphan))
        assertFalse(fixture.supported)
        val manifestWrites = fixture.requests.filter { it.method == "PUT" && it.url.pathSegments.last() == SyncArchiveRepository.MANIFEST_FILE_NAME }
        assertEquals("*", manifestWrites.first().header("If-None-Match"))
        assertNotNull(manifestWrites.last().header("If-Match"))
    }

    @Test
    fun `fragment configuration preserves finite locked sync and garbage collection`() = runTest {
        val fixture = Fixture("#display")
        val orphan = fixture.addOrphan()
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
            fixture.backend.upload(data, first).getOrThrow()
        }
        fixture.backend.fetch().getOrThrow().dataset!!.use { assertEquals(payload, it.data) }
        assertTrue(fixture.supported)
        assertFalse(fixture.files.containsKey(orphan))
        assertTrue(fixture.requests.filter { it.method == "LOCK" || it.method == "UNLOCK" }.all { it.url.fragment == null })
        assertTrue(fixture.requests.filter { it.method in setOf("GET", "PUT", "DELETE", "PROPFIND") }
            .all { it.header("If") == "<https://sync.test/dav/> (<$TOKEN>)" })
    }

    @Test
    fun `first observation retains an orphan then a later fenced publication removes it after grace`() = runTest {
        val fixture = Fixture()
        val orphan = fixture.addOrphan()
        fixture.files["notes.txt"] = byteArrayOf(9)
        fixture.files["neriplayer-sync-v4-not-a-hash.zst"] = byteArrayOf(8)
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            val previous = fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME).copyOf()
            fixture.api.getFileContentStrict("https://sync.test/dav/$orphan").getOrThrow()
            assertTrue(fixture.files.containsKey(orphan))
            fixture.advance(WebDavArchiveGcJournal.GRACE_MS)

            val second = fixture.backend.upload(data, first).getOrThrow()

            assertFalse(fixture.files.containsKey(orphan))
            assertTrue(second.knownPaths.all(fixture.files::containsKey))
            assertTrue(fixture.files.containsKey("notes.txt"))
            assertTrue(fixture.files.containsKey("neriplayer-sync-v4-not-a-hash.zst"))
            val deletion = fixture.requests.single { it.method == "DELETE" }
            assertNotNull(deletion.header("If-Match"))
            assertTrue(deletion.header("If")!!.contains(TOKEN))
            val stale = fixture.api.updateFileContent(fixture.manifestUrl, previous, expectedVersion = first.token)
            assertEquals(412, (stale.exceptionOrNull() as moe.ouom.neriplayer.api.sync.webdav.WebDavApiException).statusCode)
        }
    }

    @Test
    fun `a constant response ETag cannot authorize garbage collection`() = runTest {
        val fixture = Fixture()
        fixture.constantETag = true
        val orphan = fixture.addOrphan()
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
            fixture.backend.upload(data, first).getOrThrow()
        }
        assertTrue(fixture.files.containsKey(orphan))
        assertTrue(fixture.requests.none { it.method == "DELETE" })
    }

    @Test
    fun `missing weak or malformed publication ETags cannot authorize garbage collection`() = runTest {
        for (etag in listOf(null, "W/\"weak\"", "unquoted-etag")) {
            val fixture = Fixture()
            val orphan = fixture.addOrphan()
            fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
                fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
                fixture.manifestWriteETag = { etag }
                fixture.requests.clear()

                fixture.backend.upload(data, first).getOrThrow()
            }
            assertTrue(fixture.files.containsKey(orphan))
            assertNotNull(fixture.files[SyncArchiveRepository.MANIFEST_FILE_NAME])
            assertTrue(fixture.requests.none { it.method == "PROPFIND" || it.method == "DELETE" })
            assertEquals("UNLOCK", fixture.requests.last().method)
        }
    }

    @Test
    fun `reading a referenced object resets its previous orphan age`() = runTest {
        val fixture = Fixture()
        val first = fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
        }
        val path = first.knownPaths.first()
        fixture.gcState = fixture.gcState.copy(candidates = listOf(WebDavArchiveGcCandidate(path, "\"old\"", 1)))
        fixture.backend.fetch().getOrThrow().dataset!!.close()
        assertTrue(fixture.gcState.candidates.none { it.path == path })
    }

    @Test
    fun `objects removed from the previous closure do not inherit an old candidate age`() = runTest {
        val fixture = Fixture()
        val first = fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
        }
        val path = first.knownPaths.first()
        fixture.gcState = fixture.gcState.copy(candidates = listOf(WebDavArchiveGcCandidate(path, fixture.objectETag(path), 1)))
        fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
        fixture.archive.playbackDatasets.fromLegacy(payload.copy(deviceId = "changed")).use { data ->
            fixture.backend.upload(data, first).getOrThrow()
        }
        assertTrue(fixture.files.containsKey(path))
        assertTrue(fixture.gcState.candidates.none { it.path == path })
    }

    @Test
    fun `only explicit unsupported locking permits unlocked sync`() = runTest {
        for (status in listOf(405, 501)) {
            val fixture = Fixture()
            fixture.lockStatus = status
            fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            }
            assertFalse(fixture.supported)
            assertTrue(fixture.requests.none { it.method == "DELETE" || it.method == "UNLOCK" })
        }
        for (status in listOf(401, 403, 423, 500)) {
            val fixture = Fixture()
            fixture.lockStatus = status
            fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                assertTrue(fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).isFailure)
            }
            assertEquals(listOf("LOCK"), fixture.requests.map { it.method })
        }
    }

    @Test
    fun `a first nonfinite lock is released before compatible sync without GC`() = runTest {
        for (timeout in listOf("Infinite", "Second-301")) {
            val fixture = Fixture()
            fixture.lockTimeout = timeout
            val orphan = fixture.addOrphan()
            fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            }
            assertEquals(listOf("LOCK", "UNLOCK"), fixture.requests.take(2).map { it.method })
            assertFalse(fixture.supported)
            assertFalse(fixture.locked)
            assertTrue(fixture.requests.filter { it.method == "PUT" }.all { it.header("If") == null })
            assertTrue(fixture.requests.none { it.method == "DELETE" })
            assertTrue(fixture.files.containsKey(orphan))
        }
    }

    @Test
    fun `a target that supported locking cannot silently downgrade`() = runTest {
        val fixture = Fixture()
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
        }
        assertTrue(fixture.supported)
        fixture.lockStatus = 405
        fixture.requests.clear()
        assertTrue(fixture.backend.fetch().isFailure)
        assertEquals(listOf("LOCK"), fixture.requests.map { it.method })
    }

    @Test
    fun `invalid listing defers maintenance without losing the published version`() = runTest {
        val fixture = Fixture()
        val orphan = fixture.addOrphan()
        fixture.badListing = true
        val published = fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
        }
        assertEquals(fixture.objectETag(SyncArchiveRepository.MANIFEST_FILE_NAME), published.token!!.etag)
        assertTrue(fixture.files.containsKey(orphan))
        assertTrue(fixture.requests.none { it.method == "DELETE" })
        assertFalse(fixture.locked)
    }

    @Test
    fun `failure to unlock does not acknowledge an already published remote version`() = runTest {
        val fixture = Fixture()
        fixture.unlockStatus = 500
        val result = fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true))
        }
        assertTrue(result.isFailure)
        assertNotNull(fixture.files[SyncArchiveRepository.MANIFEST_FILE_NAME])
        assertEquals("UNLOCK", fixture.requests.last().method)
        assertEquals(500, (result.exceptionOrNull() as moe.ouom.neriplayer.api.sync.webdav.WebDavApiException).statusCode)
    }

    @Test
    fun `a lost lock condition during collection does not acknowledge publication`() = runTest {
        for (status in listOf(412, 423)) {
            val fixture = Fixture()
            val orphan = fixture.addOrphan()
            fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
                fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
                fixture.deleteStatus = status
                assertTrue(fixture.backend.upload(data, first).isFailure)
            }
            assertTrue(fixture.files.containsKey(orphan))
            assertNotNull(fixture.files[SyncArchiveRepository.MANIFEST_FILE_NAME])
            assertEquals("UNLOCK", fixture.requests.last().method)
        }
    }

    @Test
    fun `cancellation releases the owned lease and preserves the same error`() = runTest {
        val fixture = Fixture()
        val cancellation = CancellationException("cancelled")
        fixture.beforeRequest = { if (it.method == "PUT") throw cancellation }
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            assertSame(cancellation, assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking { fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)) }
            })
        }
        assertEquals("UNLOCK", fixture.requests.last().method)
        assertFalse(fixture.locked)
        assertNull(fixture.files[SyncArchiveRepository.MANIFEST_FILE_NAME])
    }

    private inner class Fixture(remoteSuffix: String = "") {
        val files = linkedMapOf<String, ByteArray>()
        val requests = arrayListOf<Request>()
        var locked = false
        var wallMs = 1_800_000_000_000L
        var uptimeMs = 1_000L
        var gcState = WebDavArchiveGcState()
        var supported = false
        var lockStatus = 200
        var lockTimeout = "Second-300"
        var unlockStatus = 204
        var deleteStatus = 204
        var currentUsername = "fixture-user"
        val maintenanceScopes = arrayListOf<String>()
        var constantETag = false
        var manifestWriteETag: (String) -> String? = { it }
        var badListing = false
        var beforeRequest: (Request) -> Unit = {}
        private val storage = mock(WebDavStorage::class.java) { call ->
            if (call.method.name in setOf("archiveLockSupported", "archiveGcState", "rememberArchiveLock", "saveArchiveGcState")) {
                maintenanceScopes += call.arguments[0] as String
            }
            when (call.method.name) {
                "getUsername" -> currentUsername
                "archiveLockSupported" -> supported
                "archiveGcState" -> gcState
                "rememberArchiveLock" -> { supported = true; null }
                "saveArchiveGcState" -> { gcState = call.arguments[1] as WebDavArchiveGcState; null }
                else -> null
            }
        }
        val archive = SyncArchiveRepository(temporary.newFolder())
        private val remoteUrl = "https://sync.test/dav/backup" + remoteSuffix
        val manifestUrl = WebDavApiClient.buildSiblingFileUrl(remoteUrl, SyncArchiveRepository.MANIFEST_FILE_NAME)
        val api = WebDavApiClient("fixture-user", "fixture-password", OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            beforeRequest(request)
            val name = request.url.pathSegments.last()
            when (request.method) {
                "LOCK" -> {
                    if (lockStatus != 200) response(request, lockStatus)
                    else if (locked && request.header("If")?.contains(TOKEN) != true) response(request, 423)
                    else {
                        locked = true
                        response(request, 200, lockResponse.replace("Second-300", lockTimeout).toByteArray()).newBuilder()
                            .header("Lock-Token", "<$TOKEN>").build()
                    }
                }
                "UNLOCK" -> {
                    if (unlockStatus != 204) response(request, unlockStatus)
                    else if (!locked || request.header("Lock-Token") != "<$TOKEN>") response(request, 409)
                    else { locked = false; response(request, 204) }
                }
                "PUT" -> write(request, name)
                "DELETE" -> {
                    val current = files[name]
                    if (deleteStatus != 204) response(request, deleteStatus)
                    else if (!locked || request.header("If")?.contains(TOKEN) != true) response(request, 423)
                    else if (current == null) response(request, 404)
                    else if (request.header("If-Match") != etag(current)) response(request, 412)
                    else { files.remove(name); response(request, 204) }
                }
                "PROPFIND" -> response(request, 207, (if (badListing) "<bad/>" else directoryListing()).toByteArray())
                else -> files[name]?.let { response(request, 200, it).newBuilder().header("ETag", etag(it)).build() }
                    ?: response(request, 404)
            }
        }.build(), "auth")
        val backend = WebDavSyncBackend(storage, api,
            remoteUrl, SyncRemoteSnapshotDecoder { it }, { IOException("invalid") }, {}, archive,
            wallMs = { wallMs }, uptimeMs = { uptimeMs })

        private fun write(request: Request, name: String): Response {
            if (locked && request.header("If")?.contains(TOKEN) != true) return response(request, 423)
            val previous = files[name]
            if (request.header("If-None-Match") == "*" && previous != null) return response(request, 412)
            if (request.header("If-Match") != null && request.header("If-Match") != previous?.let(::etag)) return response(request, 412)
            val bytes = Buffer().use { request.body!!.writeTo(it); it.readByteArray() }
            files[name] = bytes
            val writtenETag = if (name == SyncArchiveRepository.MANIFEST_FILE_NAME) manifestWriteETag(etag(bytes)) else etag(bytes)
            val written = response(request, 201).newBuilder()
            if (writtenETag != null) written.header("ETag", writtenETag)
            return written.build()
        }

        private fun etag(bytes: ByteArray): String = if (constantETag) "\"constant\"" else "\"" + MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) } + "\""

        fun objectETag(path: String): String = etag(files.getValue(path))
        fun advance(milliseconds: Long) { wallMs += milliseconds; uptimeMs += milliseconds }
        fun addOrphan(): String {
            val path = "neriplayer-sync-v4-${"a".repeat(64)}.zst"
            files[path] = byteArrayOf(4, 5, 6)
            return path
        }

        private fun response(request: Request, code: Int, body: ByteArray = byteArrayOf()): Response = Response.Builder()
            .request(request).code(code).message("fixture").protocol(Protocol.HTTP_1_1).body(body.toResponseBody()).build()

        private val lockResponse = """
            <d:prop xmlns:d="DAV:"><d:lockdiscovery><d:activelock>
                <d:locktype><d:write/></d:locktype><d:lockscope><d:exclusive/></d:lockscope>
                <d:depth>infinity</d:depth><d:timeout>Second-300</d:timeout>
                <d:locktoken><d:href>$TOKEN</d:href></d:locktoken>
                <d:lockroot><d:href>https://sync.test/dav/</d:href></d:lockroot>
            </d:activelock></d:lockdiscovery></d:prop>
        """.trimIndent()
        private val directoryResponse = """
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
        """.trimIndent()

        private fun directoryListing(): String = directoryResponse.replace("</d:multistatus>", files.entries.joinToString("") { (name, bytes) ->
            "<d:response><d:href>/dav/$name</d:href><d:propstat><d:prop><d:resourcetype/><d:getetag>${etag(bytes)}</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        } + "</d:multistatus>")
    }

    private companion object {
        const val TOKEN = "urn:uuid:7d65fd31-1e73-49a3-9dd1-30aa22c2394c"
    }
}
