package moe.ouom.neriplayer.data.sync.host

import java.io.IOException
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.api.sync.webdav.WebDavArchiveLeaseLostException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.sync.change.SyncDataChangeDetector
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncBackend
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataStore
import moe.ouom.neriplayer.data.sync.runtime.SyncSession
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
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
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class WebDavArchiveGcHttpTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = SyncData(deviceId = "lease-test", playlists = listOf(
        SyncPlaylist(id = 1L, name = "playlist", songs = listOf(SyncSong(id = 2L, name = "song")))
    ))
    private val noChangePayload = payload.copy(playlists = listOf(SyncPlaylist(
        id = 1L, name = "playlist", songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION,
        songs = listOf(SyncSong(id = 2L, name = "song", syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION))
    )))

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
    fun `a fresh lease rejects stale updates even when the server ignores ETag write conditions`() = runTest {
        for (constantETag in listOf(false, true)) {
            val fixture = Fixture()
            fixture.ignoreWriteConditions = true
            fixture.constantETag = constantETag
            fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
                fixture.backend.upload(data, first).getOrThrow()
                val current = fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME).copyOf()
                fixture.requests.clear()
                val stale = fixture.backend.upload(data, first)
                assertTrue(stale.exceptionOrNull() is moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException)
                assertTrue(current.contentEquals(fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)))
                assertTrue(fixture.requests.none { it.method == "PUT" })
                assertFalse(fixture.locked)
            }
        }
    }

    @Test
    fun `a fresh lease rejects duplicate first publication even when the server ignores create only`() = runTest {
        val fixture = Fixture()
        fixture.ignoreWriteConditions = true
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            val current = fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME).copyOf()
            fixture.requests.clear()
            val stale = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true))
            assertTrue(stale.exceptionOrNull() is moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException)
            assertTrue(current.contentEquals(fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)))
            assertTrue(fixture.requests.none { it.method == "PUT" })
            assertFalse(fixture.locked)
        }
    }

    @Test
    fun `finite leases use observed fingerprints when publication ETags are absent or weak`() = runTest {
        for (etag in listOf(null, "W/\"weak\"", "unquoted-etag")) {
            val fixture = Fixture()
            fixture.manifestWriteETag = { etag }
            fixture.manifestReadETag = { etag }
            fixture.ignoreWriteConditions = true
            fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
                val second = fixture.backend.upload(data, first).getOrThrow()
                assertTrue(first.lastKnownFingerprint != second.lastKnownFingerprint)
                assertTrue(fixture.backend.upload(data, first).exceptionOrNull() is moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException)
                assertTrue(fixture.requests.none { it.method == "DELETE" })
                assertFalse(fixture.locked)
            }
        }
    }

    @Test
    fun `a fingerprint without a finite lease cannot replace strong ETag concurrency protection`() = runTest {
        val fixture = Fixture()
        fixture.lockStatus = 405
        fixture.manifestWriteETag = { null }
        fixture.manifestReadETag = { null }
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            fixture.requests.clear()
            val unsafe = fixture.backend.upload(data, first)
            assertTrue(unsafe.exceptionOrNull() is moe.ouom.neriplayer.api.sync.webdav.WebDavMissingConcurrencyTokenException)
            assertTrue(fixture.requests.none { it.method == "PUT" })
        }
    }

    @Test
    fun `a fresh lease refuses to recreate a deleted archive from a stale version`() = runTest {
        val fixture = Fixture()
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            fixture.files.remove(SyncArchiveRepository.MANIFEST_FILE_NAME)
            fixture.requests.clear()
            assertTrue(fixture.backend.upload(data, first).exceptionOrNull() is moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException)
            assertFalse(fixture.files.containsKey(SyncArchiveRepository.MANIFEST_FILE_NAME))
            assertTrue(fixture.requests.none { it.method == "PUT" })
        }
    }

    @Test
    fun `a locked ETag only version detects another publication without a saved fingerprint`() = runTest {
        val fixture = Fixture()
        fixture.ignoreWriteConditions = true
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            val etagOnly = WebDavSyncBackend.Version(first.token, false)
            fixture.backend.upload(data, etagOnly).getOrThrow()
            assertTrue(fixture.backend.upload(data, etagOnly).exceptionOrNull() is moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException)
        }
    }

    @Test
    fun `an unreadable manifest preserves the verification error and prevents uploads`() = runTest {
        val fixture = Fixture()
        fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            val first = fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            val failure = IOException("manifest verification failed")
            fixture.beforeRequest = { if (it.method == "GET" && it.url.toString() == fixture.manifestUrl) throw failure }
            fixture.requests.clear()
            assertSame(failure, fixture.backend.upload(data, first).exceptionOrNull())
            assertTrue(fixture.requests.none { it.method == "PUT" })
            assertFalse(fixture.locked)
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
        val next = payload.copy(playlists = listOf(payload.playlists.single().copy(name = "changed")))
        val nextPaths = fixture.archive.playbackDatasets.fromLegacy(next).use { data ->
            fixture.archive.prepareCancellable(data).use { it.paths }
        }
        val removed = first.knownPaths - nextPaths
        assertTrue(removed.isNotEmpty())
        val path = removed.first()
        fixture.gcState = fixture.gcState.copy(candidates = listOf(WebDavArchiveGcCandidate(
            path, fixture.objectETag(path), fixture.wallMs - WebDavArchiveGcJournal.GRACE_MS, WebDavArchiveGcJournal.GRACE_MS)))
        fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
        fixture.archive.playbackDatasets.fromLegacy(next).use { data ->
            val published = fixture.backend.upload(data, first).getOrThrow()
            assertFalse(path in published.knownPaths)
        }
        assertTrue(fixture.files.containsKey(path))
        val candidate = fixture.gcState.candidates.single { it.path == path }
        assertEquals(fixture.wallMs, candidate.firstSeenMs)
        assertEquals(0L, candidate.observedAgeMs)
    }

    @Test
    fun `an unchanged sync collects the replaced closure after grace without uploading data again`() = runTest {
        val fixture = Fixture()
        val next = noChangePayload.copy(playlists = listOf(noChangePayload.playlists.single().copy(name = "changed")))
        fixture.files["backup.json.gz"] = byteArrayOf(7)
        fixture.files["notes.txt"] = byteArrayOf(8)
        val first = fixture.archive.playbackDatasets.fromLegacy(noChangePayload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
        }
        val second = fixture.archive.playbackDatasets.fromLegacy(next).use { data ->
            fixture.backend.upload(data, first).getOrThrow()
        }
        val replaced = first.knownPaths - second.knownPaths
        assertTrue(replaced.isNotEmpty())
        assertTrue(replaced.all(fixture.files::containsKey))
        fixture.backend.saveRemoteVersion(second)
        fixture.requests.clear()

        runUnchangedSession(fixture, next)

        assertTrue(replaced.all(fixture.files::containsKey))
        assertTrue(fixture.requests.none { it.method == "PUT" || it.method == "DELETE" })
        fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
        fixture.requests.clear()

        runUnchangedSession(fixture, next)

        assertTrue(replaced.none(fixture.files::containsKey))
        assertTrue(second.knownPaths.all(fixture.files::containsKey))
        assertTrue(fixture.files.containsKey("backup.json.gz"))
        assertTrue(fixture.files.containsKey("notes.txt"))
        assertTrue(fixture.requests.none { it.method == "PUT" && it.url.pathSegments.last().endsWith(".zst") })
        val publication = fixture.requests.single { it.method == "PUT" }
        assertEquals(SyncArchiveRepository.MANIFEST_FILE_NAME, publication.url.pathSegments.last())
        assertEquals(second.token!!.etag, publication.header("If-Match"))
        assertTrue(fixture.requests.filter { it.method == "DELETE" }.all {
            it.header("If-Match") != null && it.header("If")?.contains(TOKEN) == true
        })
        assertEquals("LOCK", fixture.requests.first().method)
        assertEquals("UNLOCK", fixture.requests.last().method)
        assertEquals(WebDavApiClient.calculateFingerprint(fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)),
            fixture.savedRemoteFingerprint)
        fixture.backend.fetch().getOrThrow().dataset!!.use { assertEquals(next, it.data) }
    }

    @Test
    fun `unchanged maintenance never deletes through a root without a changed strong ETag`() = runTest {
        for (etag in listOf(null, "W/\"weak\"", "invalid", "\"constant\"")) {
            val fixture = Fixture()
            if (etag == "\"constant\"") fixture.constantETag = true
            val orphan = fixture.addOrphan()
            fixture.archive.playbackDatasets.fromLegacy(noChangePayload).use { data ->
                fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            }
            fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
            fixture.manifestReadETag = { etag }
            fixture.requests.clear()

            runUnchangedSession(fixture, noChangePayload)

            assertTrue(fixture.files.containsKey(orphan))
            assertTrue(fixture.requests.none { it.method == "DELETE" })
            assertTrue(fixture.requests.none { it.method == "PUT" && it.url.pathSegments.last().endsWith(".zst") })
            if (etag != "\"constant\"") assertTrue(fixture.requests.none { it.method == "PROPFIND" || it.method == "PUT" })
            assertFalse(fixture.locked)
        }
    }

    @Test
    fun `unchanged maintenance stops before deletion when its root CAS loses the condition`() = runTest {
        for (status in listOf(412, 423)) {
            val fixture = Fixture()
            val orphan = fixture.addOrphan()
            val first = fixture.archive.playbackDatasets.fromLegacy(noChangePayload).use { data ->
                fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            }
            fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
            fixture.manifestWriteStatus = status
            fixture.requests.clear()

            val error = assertThrows(moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException::class.java) {
                kotlinx.coroutines.runBlocking { runUnchangedSession(fixture, noChangePayload) }
            }

            assertEquals(status, error.statusCode)
            assertTrue(fixture.files.containsKey(orphan))
            assertTrue(first.knownPaths.all(fixture.files::containsKey))
            assertTrue(fixture.requests.none { it.method == "DELETE" })
            assertEquals("UNLOCK", fixture.requests.last().method)
        }
    }

    @Test
    fun `unchanged maintenance preserves cancellation and a lost lease before its root barrier`() = runTest {
        for (error in listOf(CancellationException("cancelled maintenance"), WebDavArchiveLeaseLostException("lost maintenance lease"))) {
            val fixture = Fixture()
            val orphan = fixture.addOrphan()
            val first = fixture.archive.playbackDatasets.fromLegacy(noChangePayload).use { data ->
                fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            }
            fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
            fixture.beforeRequest = {
                if (it.method == "PUT" && it.url.pathSegments.last() == SyncArchiveRepository.MANIFEST_FILE_NAME) throw error
            }
            fixture.requests.clear()

            assertSame(error, assertThrows(error.javaClass) {
                kotlinx.coroutines.runBlocking { runUnchangedSession(fixture, noChangePayload) }
            })

            assertTrue(fixture.files.containsKey(orphan))
            assertTrue(first.knownPaths.all(fixture.files::containsKey))
            assertTrue(fixture.requests.none { it.method == "DELETE" })
            assertEquals("UNLOCK", fixture.requests.last().method)
        }
    }

    @Test
    fun `unchanged maintenance retains objects when the root barrier response is not a new strong ETag`() = runTest {
        for (reply in listOf(null, "W/\"weak\"", "invalid", "unchanged")) {
            val fixture = Fixture()
            val orphan = fixture.addOrphan()
            val first = fixture.archive.playbackDatasets.fromLegacy(noChangePayload).use { data ->
                fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            }
            fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
            fixture.manifestWriteETag = { if (reply == "unchanged") first.token!!.etag else reply }
            fixture.requests.clear()

            runUnchangedSession(fixture, noChangePayload)

            assertTrue(fixture.files.containsKey(orphan))
            assertTrue(first.knownPaths.all(fixture.files::containsKey))
            assertEquals(1, fixture.requests.count { it.method == "PUT" })
            assertTrue(fixture.requests.none { it.method == "DELETE" })
            assertEquals(WebDavApiClient.calculateFingerprint(fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)),
                fixture.savedRemoteFingerprint)
            assertFalse(fixture.locked)
        }
    }

    @Test
    fun `unchanged maintenance defers an invalid listing and retries the same objects later`() = runTest {
        val fixture = Fixture()
        val orphan = fixture.addOrphan()
        val first = fixture.archive.playbackDatasets.fromLegacy(noChangePayload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
        }
        fixture.backend.saveRemoteVersion(first)
        fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
        fixture.badListing = true
        fixture.requests.clear()

        runUnchangedSession(fixture, noChangePayload)

        assertTrue(fixture.files.containsKey(orphan))
        assertTrue(fixture.requests.none { it.method == "PUT" || it.method == "DELETE" })
        fixture.badListing = false
        fixture.requests.clear()

        runUnchangedSession(fixture, noChangePayload)

        assertFalse(fixture.files.containsKey(orphan))
        assertTrue(first.knownPaths.all(fixture.files::containsKey))
        assertEquals(1, fixture.requests.count { it.method == "PUT" })
        assertEquals(WebDavApiClient.calculateFingerprint(fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)),
            fixture.savedRemoteFingerprint)
    }

    @Test
    fun `a deferred unchanged deletion still saves its new root version before retrying`() = runTest {
        val fixture = Fixture()
        val orphan = fixture.addOrphan()
        val first = fixture.archive.playbackDatasets.fromLegacy(noChangePayload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
        }
        fixture.backend.saveRemoteVersion(first)
        fixture.advance(WebDavArchiveGcJournal.GRACE_MS)
        fixture.deleteStatus = 500
        fixture.requests.clear()

        runUnchangedSession(fixture, noChangePayload)

        assertTrue(fixture.files.containsKey(orphan))
        assertTrue(first.knownPaths.all(fixture.files::containsKey))
        assertFalse(first.lastKnownFingerprint == fixture.savedRemoteFingerprint)
        assertEquals(WebDavApiClient.calculateFingerprint(fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)),
            fixture.savedRemoteFingerprint)
        fixture.deleteStatus = 204
        fixture.requests.clear()

        runUnchangedSession(fixture, noChangePayload)

        assertFalse(fixture.files.containsKey(orphan))
        assertEquals(1, fixture.requests.count { it.method == "PUT" })
        assertTrue(fixture.requests.none { it.method == "PUT" && it.url.pathSegments.last().endsWith(".zst") })
    }

    private suspend fun runUnchangedSession(fixture: Fixture, data: SyncData) {
        val local = object : SyncLocalDataStore {
            override suspend fun awaitInitialized() = true
            override fun mutationVersion() = 1L
            override suspend fun snapshot() = fixture.archive.playbackDatasets.fromLegacy(data)
            override suspend fun apply(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
                assertEquals(1L, expectedMutationVersion)
                assertEquals(data.playlists, dataset.data.playlists)
                return true
            }
        }
        val host = mock(SyncMergeHost::class.java) { call ->
            when (call.method.name) {
                "getMergeSuccessMessage" -> "merged"
                "getInitialUploadMessage" -> "uploaded"
                else -> null
            }
        }
        val merger = SyncDataMerger(host) { 10L }
        assertFalse(SyncDataChangeDetector.hasDataChanged(data, merger.merge(data, data, 0L).mergedData))
        val session = SyncSession(local, merger, fixture.archive.playbackDatasets,
            "unchanged", "uploaded", { IOException("busy") }, nowMs = { fixture.wallMs }, deferredMessage = "pending")
        assertTrue(session.execute { fixture.backend }.getOrThrow().success)
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
        assertEquals(listOf("UNLOCK", "LOCK"), fixture.requests.takeLast(2).map { it.method })
        assertEquals(500, (result.exceptionOrNull() as moe.ouom.neriplayer.api.sync.webdav.WebDavApiException).statusCode)
        assertEquals(423, (result.exceptionOrNull()!!.suppressed.single() as moe.ouom.neriplayer.api.sync.webdav.WebDavApiException).statusCode)
    }

    @Test
    fun `an uncertain released lease reconciles the publication under a fresh finite lock`() = runTest {
        RecoveryHttpFixture().use { fixture ->
            val published = fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            }
            val requests = fixture.requests.toList()
            assertEquals(2, requests.count { it.method == "LOCK" })
            assertEquals(2, requests.count { it.method == "UNLOCK" })
            assertEquals(1, requests.count { it.method == "PUT" && it.url.encodedPath.endsWith(SyncArchiveRepository.MANIFEST_FILE_NAME) })
            val recovery = requests.drop(requests.indexOfFirst { it.method == "UNLOCK" } + 1)
            assertEquals("LOCK", recovery.first().method)
            assertEquals("UNLOCK", recovery.last().method)
            assertTrue(recovery.any { it.method == "GET" && it.url.encodedPath.endsWith(SyncArchiveRepository.MANIFEST_FILE_NAME) })
            assertTrue(recovery.any { it.method == "GET" && it.url.encodedPath.endsWith(".zst") })
            assertTrue(recovery.filter { it.method == "GET" }.all { it.headers["If"]?.contains("recovery-2") == true })
            assertTrue(recovery.none { it.method in setOf("PUT", "DELETE", "PROPFIND") })
            assertFalse(fixture.locked)
            assertEquals(fixture.manifestFingerprint(), published.lastKnownFingerprint)
        }
    }

    @Test
    fun `reconciled publication applies the local snapshot and saves the remote version`() = runTest {
        RecoveryHttpFixture().use { fixture ->
            var applied: SyncData? = null
            val local = object : SyncLocalDataStore {
                override suspend fun awaitInitialized() = true
                override fun mutationVersion() = 1L
                override suspend fun snapshot() = fixture.archive.playbackDatasets.fromLegacy(payload)
                override suspend fun apply(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
                    assertTrue(remoteChanged)
                    assertEquals(1L, expectedMutationVersion)
                    assertNull(applied)
                    applied = dataset.data
                    return true
                }
            }
            val host = mock(SyncMergeHost::class.java) { call ->
                when (call.method.name) {
                    "getInitialUploadMessage" -> "uploaded"
                    else -> null
                }
            }
            val session = SyncSession(local, SyncDataMerger(host) { 10L }, fixture.archive.playbackDatasets,
                "unchanged", "uploaded", { IOException("busy") }, nowMs = { 20L }, deferredMessage = "pending")

            assertTrue(session.execute { fixture.backend }.getOrThrow().success)

            assertEquals(payload.playlists, applied!!.playlists)
            assertEquals(fixture.manifestFingerprint(), fixture.savedFingerprint.get())
            assertEquals(20L, fixture.savedSyncTime.get())
            assertFalse(fixture.locked)
            assertEquals(1, fixture.requests.count { it.method == "PUT" && it.url.encodedPath.endsWith(SyncArchiveRepository.MANIFEST_FILE_NAME) })
        }
    }

    @Test
    fun `a lost unlock response is reconciled without uploading the publication twice`() = runTest {
        RecoveryHttpFixture().use { fixture ->
            fixture.firstUnlockStatus = 204
            fixture.afterResponse = { response ->
                if (response.request.method == "UNLOCK" && response.request.header("Lock-Token")?.contains("recovery-1") == true) {
                    throw IOException("lost release response")
                }
            }
            fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)).getOrThrow()
            }
            assertEquals(2, fixture.requests.count { it.method == "LOCK" })
            assertEquals(2, fixture.requests.count { it.method == "UNLOCK" })
            assertEquals(1, fixture.requests.count { it.method == "PUT" && it.url.encodedPath.endsWith(SyncArchiveRepository.MANIFEST_FILE_NAME) })
            assertFalse(fixture.locked)
        }
    }

    @Test
    fun `publication recovery requires the original strong ETag`() = runTest {
        for (etag in listOf(null, "W/\"weak\"", "invalid")) {
            RecoveryHttpFixture().use { fixture ->
                fixture.manifestWriteETag = { etag }
                assertReleaseFailure(fixture)
                assertEquals(2, fixture.requests.count { it.method == "LOCK" })
                val recovery = fixture.requests.toList().dropWhile { it.method != "UNLOCK" }.drop(1)
                assertEquals(listOf("LOCK", "UNLOCK"), recovery.map { it.method })
                assertFalse(fixture.locked)
            }
        }
    }

    @Test
    fun `recovery rejects a changed manifest even if the server repeats its ETag`() = runTest {
        for (repeatETag in listOf(false, true)) {
            RecoveryHttpFixture().use { fixture ->
                fixture.afterFirstRelease = {
                    val original = fixture.files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME)
                    if (repeatETag) fixture.manifestReadETag = { "\"${WebDavApiClient.calculateFingerprint(original)}\"" }
                    fixture.files[SyncArchiveRepository.MANIFEST_FILE_NAME] = original.copyOf().also {
                        it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                    }
                }
                assertReleaseFailure(fixture)
                assertEquals(2, fixture.requests.count { it.method == "LOCK" })
                assertEquals("UNLOCK", fixture.requests.last().method)
                assertFalse(fixture.locked)
            }
        }
    }

    @Test
    fun `recovery reads remote objects again and rejects missing or corrupt warm cached closure`() = runTest {
        for (remove in listOf(false, true)) {
            RecoveryHttpFixture().use { fixture ->
                var path: String? = null
                fixture.afterFirstRelease = {
                    val objectPath = fixture.files.keys.first { it.endsWith(".zst") }
                    path = objectPath
                    if (remove) fixture.files.remove(objectPath) else fixture.files[objectPath] = byteArrayOf(1, 2, 3)
                }
                assertReleaseFailure(fixture)
                val recovery = fixture.requests.toList().dropWhile { it.method != "UNLOCK" }.drop(1)
                assertTrue(recovery.any { it.method == "GET" && it.url.pathSegments.last() == path })
                assertEquals("UNLOCK", fixture.requests.last().method)
                assertFalse(fixture.locked)
            }
        }
    }

    @Test
    fun `recovery requires a fresh finite supported lock and its successful release`() = runTest {
        for (lockStatus in listOf(405, 423)) {
            RecoveryHttpFixture().use { fixture ->
                fixture.recoveryLockStatus = lockStatus
                assertReleaseFailure(fixture)
                assertEquals("LOCK", fixture.requests.last().method)
            }
        }
        RecoveryHttpFixture().use { fixture ->
            fixture.recoveryLockTimeout = "Infinite"
            assertReleaseFailure(fixture)
            assertEquals("UNLOCK", fixture.requests.last().method)
            assertFalse(fixture.locked)
        }
        RecoveryHttpFixture().use { fixture ->
            fixture.recoveryUnlockStatus = 500
            assertReleaseFailure(fixture)
            assertEquals(2, fixture.requests.count { it.method == "UNLOCK" })
        }
    }

    @Test
    fun `release authentication and condition errors never trigger publication recovery`() = runTest {
        for (status in listOf(401, 403, 412, 423)) {
            RecoveryHttpFixture().use { fixture ->
                fixture.firstUnlockStatus = status
                val result = fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                    fixture.backend.upload(data, WebDavSyncBackend.Version(null, true))
                }
                assertEquals(status, (result.exceptionOrNull() as moe.ouom.neriplayer.api.sync.webdav.WebDavApiException).statusCode)
                assertEquals(1, fixture.requests.count { it.method == "LOCK" })
                assertEquals("UNLOCK", fixture.requests.last().method)
            }
        }
    }

    @Test
    fun `nontransport release errors and lost leases cannot trigger publication recovery`() = runTest {
        for (error in listOf(WebDavAuthException("auth"), WebDavArchiveLeaseLostException("lost"), IllegalStateException("invalid"))) {
            RecoveryHttpFixture().use { fixture ->
                fixture.beforeRequest = { if (it.method == "UNLOCK") throw error }
                val result = fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                    fixture.backend.upload(data, WebDavSyncBackend.Version(null, true))
                }
                assertSame(error, result.exceptionOrNull())
                assertEquals(1, fixture.requests.count { it.method == "LOCK" })
                assertNotNull(fixture.files[SyncArchiveRepository.MANIFEST_FILE_NAME])
            }
        }
    }

    @Test
    fun `a failed publication operation cannot be recovered through its unlock error`() = runTest {
        RecoveryHttpFixture().use { fixture ->
            fixture.onObserved = { throw IOException("observation failed") }
            val result = fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                fixture.backend.upload(data, WebDavSyncBackend.Version(null, true))
            }
            assertEquals("observation failed", result.exceptionOrNull()!!.message)
            assertEquals(1, fixture.requests.count { it.method == "LOCK" })
            assertEquals(500, (result.exceptionOrNull()!!.suppressed.single() as moe.ouom.neriplayer.api.sync.webdav.WebDavApiException).statusCode)
            assertNotNull(fixture.files[SyncArchiveRepository.MANIFEST_FILE_NAME])
        }
    }

    @Test
    fun `cancellation after publication and during recovery preserves the same error without acknowledgement`() = runTest {
        for (duringRecovery in listOf(false, true)) {
            RecoveryHttpFixture().use { fixture ->
                val cancelled = CancellationException("cancelled")
                if (duringRecovery) fixture.beforeRequest = { request ->
                    if (request.method == "GET" && request.header("If")?.contains("recovery-2") == true) throw cancelled
                } else fixture.onObserved = { throw cancelled }
                fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
                    assertSame(cancelled, assertThrows(CancellationException::class.java) {
                        kotlinx.coroutines.runBlocking { fixture.backend.upload(data, WebDavSyncBackend.Version(null, true)) }
                    })
                }
                assertNotNull(fixture.files[SyncArchiveRepository.MANIFEST_FILE_NAME])
                assertEquals(if (duringRecovery) 2 else 1, fixture.requests.count { it.method == "LOCK" })
                assertEquals("UNLOCK", fixture.requests.last().method)
                assertFalse(fixture.locked)
                assertNull(fixture.savedFingerprint.get())
            }
        }
    }

    private suspend fun assertReleaseFailure(fixture: RecoveryHttpFixture) {
        val result = fixture.archive.playbackDatasets.fromLegacy(payload).use { data ->
            fixture.backend.upload(data, WebDavSyncBackend.Version(null, true))
        }
        assertEquals(500, (result.exceptionOrNull() as moe.ouom.neriplayer.api.sync.webdav.WebDavApiException).statusCode)
        assertTrue(result.exceptionOrNull()!!.suppressed.isNotEmpty())
        assertNull(fixture.savedFingerprint.get())
        assertEquals(1, fixture.requests.count { it.method == "PUT" && it.url.encodedPath.endsWith(SyncArchiveRepository.MANIFEST_FILE_NAME) })
        assertTrue(fixture.requests.none { it.method == "DELETE" })
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

    private inner class RecoveryHttpFixture : Closeable {
        val server = MockWebServer()
        val files = ConcurrentHashMap<String, ByteArray>()
        val requests = ConcurrentLinkedQueue<RecordedRequest>()
        private val lockToken = AtomicReference<String?>(null)
        private val grants = AtomicInteger()
        private val publicationUnlocks = AtomicInteger()
        private val supported = AtomicBoolean()
        private val gcState = AtomicReference(WebDavArchiveGcState())
        val savedFingerprint = AtomicReference<String?>(null)
        val savedSyncTime = java.util.concurrent.atomic.AtomicLong()
        @Volatile var firstUnlockStatus = 500
        @Volatile var recoveryUnlockStatus = 204
        @Volatile var recoveryLockStatus = 200
        @Volatile var recoveryLockTimeout = "Second-300"
        @Volatile var afterFirstRelease: () -> Unit = {}
        @Volatile var beforeRequest: (Request) -> Unit = {}
        @Volatile var afterResponse: (Response) -> Unit = {}
        @Volatile var onObserved: suspend (Int) -> Unit = {}
        @Volatile var manifestReadETag: (String) -> String? = { it }
        @Volatile var manifestWriteETag: (String) -> String? = { it }
        val locked: Boolean get() = lockToken.get() != null
        val archive = SyncArchiveRepository(temporary.newFolder())
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            beforeRequest(chain.request())
            val response = chain.proceed(chain.request())
            try { afterResponse(response); response }
            catch (error: Exception) { response.close(); throw error }
        }.build()
        val backend: WebDavSyncBackend

        init {
            server.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return dispatchRequest(request)
                }
            }
            val storage = mock(WebDavStorage::class.java) { call ->
                when (call.method.name) {
                    "archiveLockSupported" -> supported.get()
                    "rememberArchiveLock" -> { supported.set(true); null }
                    "archiveGcState" -> gcState.get()
                    "saveArchiveGcState" -> { gcState.set(call.arguments[1] as WebDavArchiveGcState); null }
                    "getLastRemoteFingerprint" -> savedFingerprint.get()
                    "saveLastRemoteFingerprint" -> { savedFingerprint.set(call.arguments[0] as String); null }
                    "getLastSyncTime" -> savedSyncTime.get()
                    "saveLastSyncTime" -> { savedSyncTime.set(call.arguments[0] as Long); null }
                    else -> null
                }
            }
            val api = WebDavApiClient("fixture-user", "fixture-password", client, "auth")
            backend = WebDavSyncBackend(storage, api, server.url("/dav/backup").toString(),
                SyncRemoteSnapshotDecoder { it }, { IOException("invalid") }, {}, archive,
                currentProtocolObserved = { onObserved(it) },
                wallMs = { 1_800_000_000_000L }, uptimeMs = { 1_000L }, metadataGuard = { write -> write(); true })
        }

        private fun dispatchRequest(request: RecordedRequest): MockResponse {
            if (request.method == "LOCK") return grantLock()
            if (request.method == "UNLOCK") return releaseLock(request)
            val token = lockToken.get()
            if (token != null && request.headers["If"] != "<${server.url("/dav/")}> (<$token>)") {
                return MockResponse(code = 423)
            }
            val name = request.url.pathSegments.last()
            return when (request.method) {
                "PUT" -> {
                    val previous = files[name]
                    if (request.headers["If-None-Match"] == "*" && previous != null) MockResponse(code = 412)
                    else {
                        val bytes = request.body!!.toByteArray()
                        files[name] = bytes
                        val response = MockResponse.Builder().code(201)
                        val tag = if (name == SyncArchiveRepository.MANIFEST_FILE_NAME) manifestWriteETag(etag(bytes)) else etag(bytes)
                        if (tag != null) response.addHeader("ETag", tag)
                        response.build()
                    }
                }
                "GET" -> files[name]?.let {
                    val response = MockResponse.Builder().body(Buffer().write(it))
                    val tag = if (name == SyncArchiveRepository.MANIFEST_FILE_NAME) manifestReadETag(etag(it)) else etag(it)
                    if (tag != null) response.addHeader("ETag", tag)
                    response.build()
                }
                    ?: MockResponse(code = 404)
                "PROPFIND" -> MockResponse.Builder().code(207).body("""
                    <d:multistatus xmlns:d="DAV:"><d:response><d:href>${server.url("/dav/")}</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
                """.trimIndent()).build()
                else -> MockResponse(code = 405)
            }
        }

        private fun grantLock(): MockResponse {
            if (publicationUnlocks.get() > 0 && recoveryLockStatus != 200) return MockResponse(code = recoveryLockStatus)
            val token = "opaquelocktoken:recovery-${grants.incrementAndGet()}"
            if (!lockToken.compareAndSet(null, token)) return MockResponse(code = 423)
            return MockResponse.Builder().addHeader("Lock-Token", "<$token>").body("""
                <d:prop xmlns:d="DAV:"><d:lockdiscovery><d:activelock>
                <d:lockscope><d:exclusive/></d:lockscope><d:locktype><d:write/></d:locktype>
                <d:depth>infinity</d:depth><d:timeout>${if (publicationUnlocks.get() > 0) recoveryLockTimeout else "Second-300"}</d:timeout>
                <d:locktoken><d:href>$token</d:href></d:locktoken>
                <d:lockroot><d:href>${server.url("/dav/")}</d:href></d:lockroot>
                </d:activelock></d:lockdiscovery></d:prop>
            """.trimIndent()).build()
        }

        private fun releaseLock(request: RecordedRequest): MockResponse {
            val token = lockToken.get() ?: return MockResponse(code = 409)
            if (request.headers["Lock-Token"] != "<$token>") return MockResponse(code = 409)
            lockToken.set(null)
            if (!files.containsKey(SyncArchiveRepository.MANIFEST_FILE_NAME)) return MockResponse(code = 204)
            if (publicationUnlocks.getAndIncrement() == 0) {
                afterFirstRelease()
                return MockResponse(code = firstUnlockStatus)
            }
            return MockResponse(code = recoveryUnlockStatus)
        }

        private fun etag(bytes: ByteArray) = "\"${WebDavApiClient.calculateFingerprint(bytes)}\""
        fun manifestFingerprint(): String = WebDavApiClient.calculateFingerprint(files.getValue(SyncArchiveRepository.MANIFEST_FILE_NAME))
        override fun close() {
            server.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
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
        var savedRemoteFingerprint: String? = null
        val maintenanceScopes = arrayListOf<String>()
        var constantETag = false
        var ignoreWriteConditions = false
        var manifestWriteETag: (String) -> String? = { it }
        var manifestReadETag: (String) -> String? = { it }
        var manifestWriteStatus: Int? = null
        var badListing = false
        var beforeRequest: (Request) -> Unit = {}
        private val storage = mock(WebDavStorage::class.java) { call ->
            if (call.method.name in setOf("archiveLockSupported", "archiveGcState", "rememberArchiveLock", "saveArchiveGcState")) {
                maintenanceScopes += call.arguments[0] as String
            }
            when (call.method.name) {
                "getUsername" -> currentUsername
                "getLastRemoteFingerprint" -> savedRemoteFingerprint
                "saveLastRemoteFingerprint" -> { savedRemoteFingerprint = call.arguments[0] as String; null }
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
                "PROPFIND" -> response(request, 207, (if (request.header("Depth") == "0") directoryResponse
                    else if (badListing) "<bad/>" else directoryListing()).toByteArray())
                else -> files[name]?.let {
                    val read = response(request, 200, it).newBuilder()
                    val tag = if (name == SyncArchiveRepository.MANIFEST_FILE_NAME) manifestReadETag(etag(it)) else etag(it)
                    if (tag != null) read.header("ETag", tag)
                    read.build()
                }
                    ?: response(request, 404)
            }
        }.build(), "auth")
        val backend = WebDavSyncBackend(storage, api,
            remoteUrl, SyncRemoteSnapshotDecoder { it }, { IOException("invalid") }, {}, archive,
            wallMs = { wallMs }, uptimeMs = { uptimeMs }, metadataGuard = { write -> write(); true })

        private fun write(request: Request, name: String): Response {
            if (locked && request.header("If")?.contains(TOKEN) != true) return response(request, 423)
            if (name == SyncArchiveRepository.MANIFEST_FILE_NAME) manifestWriteStatus?.let { return response(request, it) }
            val previous = files[name]
            if (!ignoreWriteConditions && request.header("If-None-Match") == "*" && previous != null) return response(request, 412)
            if (!ignoreWriteConditions && request.header("If-Match") != null && request.header("If-Match") != previous?.let(::etag)) return response(request, 412)
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
