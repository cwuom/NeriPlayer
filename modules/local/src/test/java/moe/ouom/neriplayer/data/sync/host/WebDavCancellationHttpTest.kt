package moe.ouom.neriplayer.data.sync.host

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncBackend
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebDavCancellationHttpTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `cancellation while uploading an object cannot PUT the manifest`() = runBlocking {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.method == "LOCK") return MockResponse(code = 405)
                    if (request.method == "PUT") {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting for cancellation test" }
                        return MockResponse(code = 201)
                    }
                    if (request.method == "PROPFIND") return MockResponse(code = 207, body = directoryResponse)
                    return MockResponse(code = 404)
                }
            }
            server.start()
            val backend = WebDavSyncBackend(mock(WebDavStorage::class.java),
                WebDavApiClient("test-user", "test-password", client, "auth"), server.url("/backup").toString(),
                SyncRemoteSnapshotDecoder { it }, { IOException("invalid") }, { }, SyncArchiveRepository(temporary.newFolder()),
                metadataGuard = { write -> write(); true })
            val data = SyncData(deviceId = "cancel", playlists = listOf(
                SyncPlaylist(id = 1L, name = "playlist", songs = listOf(SyncSong(id = 2L, name = "song")))
            ))
            val dataset = FileSyncPlaybackDatasetStore(temporary.newFolder()).fromLegacy(data)
            val job = async(Dispatchers.Default) { backend.upload(dataset, WebDavSyncBackend.Version(null, createOnly = true)) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                job.cancel()
                release.countDown()
                job.join()
                assertTrue(job.isCancelled)
                val writes = requests.filter { it.method == "PUT" }
                assertEquals(1, writes.size)
                assertTrue(writes.none { it.url.pathSegments.last() == SyncArchiveRepository.MANIFEST_FILE_NAME })
            } finally {
                release.countDown()
                job.cancel()
                job.join()
                dataset.close()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private val directoryResponse = """
        <d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/</d:href><d:propstat>
                <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat></d:response>
        </d:multistatus>
    """.trimIndent()
}
