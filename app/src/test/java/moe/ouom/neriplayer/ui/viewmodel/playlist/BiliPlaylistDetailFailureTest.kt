package moe.ouom.neriplayer.ui.viewmodel.playlist

import android.app.Application
import androidx.lifecycle.viewModelScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.bilibili.cache.archive.BiliArchiveContentCache
import moe.ouom.neriplayer.data.model.bilibili.cache.archive.CachedBiliArchiveVideo
import moe.ouom.neriplayer.data.model.bilibili.cache.favorite.BiliFavoriteFolderContentCache
import moe.ouom.neriplayer.data.model.bilibili.cache.favorite.CachedBiliFavoriteVideo
import moe.ouom.neriplayer.platform.bilibili.api.auth.BiliCookieSource
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.cache.archive.BiliArchiveCacheRepository
import moe.ouom.neriplayer.platform.bilibili.cache.archive.BiliArchiveCacheStore
import moe.ouom.neriplayer.platform.bilibili.cache.favorite.BiliFavoriteFolderCacheRepository
import moe.ouom.neriplayer.platform.bilibili.cache.favorite.BiliFavoriteFolderCacheStore
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import moe.ouom.neriplayer.data.model.playlist.PlaylistLoadFailure

@OptIn(ExperimentalCoroutinesApi::class)
class BiliPlaylistDetailFailureTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val application: Application = mock(Application::class.java) { invocation ->
        if (invocation.method.name == "getString") invocation.arguments.first().toString() else null
    }
    private val viewModels = mutableListOf<BiliPlaylistDetailViewModel>()
    private val clients = mutableListOf<OkHttpClient>()
    private val gates = mutableListOf<CountDownLatch>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val archiveStore = ArchiveStore()
    private val favoriteStore = FavoriteStore()

    @Before
    fun prepareMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun closeOwnedResources() {
        gates.forEach(CountDownLatch::countDown)
        runBlocking {
            viewModels.forEach { it.viewModelScope.cancel() }
            viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.join() }
        }
        clients.forEach {
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `first page failure blocks refresh and loading until an explicit retry`() = runBlocking {
        var fail = true
        val vm = viewModel { if (fail) 412 to HTML else 200 to archivePage(1L) }
        vm.start(playlist())
        val failed = awaitStopped(vm)
        assertNotNull(failed.error)
        assertTrue(failed.videos.isEmpty())
        assertEquals(PlaylistLoadFailure.Http(412), failed.error)
        val count = contentRequests().size

        vm.refresh()
        vm.loadMoreVideos()
        awaitJobs(vm)
        assertEquals(count, contentRequests().size)

        fail = false
        vm.retry()
        val recovered = awaitStopped(vm)
        assertNull(recovered.error)
        assertEquals(listOf(1L), recovered.videos.map { it.id })
    }

    @Test
    fun `refresh failure preserves loaded videos and their saved cache`() = runBlocking {
        var fail = false
        val vm = viewModel { if (fail) 412 to HTML else 200 to archivePage(1L) }
        vm.start(playlist())
        awaitStopped(vm)
        awaitJobs(vm)
        fail = true

        vm.refresh()
        val failed = awaitStopped(vm)

        assertNotNull(failed.error)
        assertEquals(listOf(1L), failed.videos.map { it.id })
        assertEquals(listOf(1L), archiveStore.values.values.single().videos.map { it.id })
    }

    @Test
    fun `failed next page keeps current videos and blocks another page request`() = runBlocking {
        val vm = viewModel { request ->
            if (request.url.queryParameter("page_num") == "2") 412 to HTML else 200 to archivePage(1L)
        }
        vm.start(playlist())
        awaitStopped(vm)
        awaitJobs(vm)
        vm.loadMoreVideos()
        val failed = withTimeout(5_000L) { vm.uiState.first { !it.loadingMore } }

        assertNotNull(failed.error)
        assertEquals(listOf(1L), failed.videos.map { it.id })
        val count = contentRequests().size
        vm.loadMoreVideos()
        awaitJobs(vm)
        assertEquals(count, contentRequests().size)
    }

    @Test
    fun `cached favorites do not hide a failed authoritative request`() = runBlocking {
        favoriteStore.values[41L] = BiliFavoriteFolderContentCache(
            mediaId = 41L, latestPageSignature = "cached", totalCount = 1,
            videos = listOf(CachedBiliFavoriteVideo(90L, "BV90", "Cached", "Uploader", 1L, "", 60))
        )
        val vm = viewModel { 412 to HTML }

        vm.start(playlist().copy(kind = BiliPlaylistKind.CREATED_FAVORITE))
        val failed = awaitStopped(vm)

        assertNotNull(failed.error)
        assertEquals(listOf(90L), failed.videos.map { it.id })
        assertEquals(listOf(90L), favoriteStore.values.getValue(41L).videos.map { it.id })
    }

    @Test
    fun `a superseded response cannot replace the new playlist or its cache`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also(gates::add)
        val vm = viewModel { request ->
            if (request.url.queryParameter("season_id") == "41") {
                entered.countDown()
                check(release.await(5L, TimeUnit.SECONDS))
                200 to archivePage(1L)
            } else 200 to archivePage(2L)
        }
        vm.start(playlist())
        withContext(Dispatchers.IO) { assertTrue(entered.await(5L, TimeUnit.SECONDS)) }

        vm.start(playlist().copy(mediaId = 42L))
        awaitStopped(vm)
        release.countDown()
        vm.viewModelScope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }

        assertEquals(42L, vm.uiState.value.header?.mediaId)
        assertEquals(listOf(2L), vm.uiState.value.videos.map { it.id })
        assertFalse(archiveStore.values.keys.any { it.first == 41L })
    }

    @Test
    fun `leaving during a request does not turn cancellation into a visible failure`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also(gates::add)
        val vm = viewModel {
            entered.countDown()
            check(release.await(5L, TimeUnit.SECONDS))
            200 to archivePage(1L)
        }
        vm.start(playlist())
        withContext(Dispatchers.IO) { assertTrue(entered.await(5L, TimeUnit.SECONDS)) }

        vm.viewModelScope.cancel()
        release.countDown()
        vm.viewModelScope.coroutineContext[Job]?.join()

        assertNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.videos.isEmpty())
        assertTrue(archiveStore.values.isEmpty())
    }

    @Test
    fun `disposing the previous page cannot cancel the next playlist owner`() = runBlocking {
        verifyLateOwnerDisposal(samePlaylist = false)
    }

    @Test
    fun `disposing the previous page cannot cancel a new owner of the same playlist`() = runBlocking {
        verifyLateOwnerDisposal(samePlaylist = true)
    }

    @Test
    fun `known offline favorites publish their complete cache without any request`() = runBlocking {
        favoriteStore.values[41L] = BiliFavoriteFolderContentCache(
            mediaId = 41L, latestPageSignature = "cached", totalCount = 1,
            videos = listOf(CachedBiliFavoriteVideo(90L, "BV90", "Cached", "Uploader", 1L, "", 60))
        )
        val vm = viewModel { 412 to HTML }

        vm.start(playlist().copy(kind = BiliPlaylistKind.CREATED_FAVORITE), offlineMode = true)
        val state = awaitStopped(vm)
        awaitJobs(vm)

        assertNull(state.error)
        assertEquals(listOf(90L), state.videos.map { it.id })
        assertFalse(state.hasMore)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `known offline archives avoid page and uploader requests with a complete cache`() = runBlocking {
        archiveStore.values[41L to BiliPlaylistKind.COLLECTION.name] = archiveCache()
        val vm = viewModel { 412 to HTML }

        vm.start(playlist().copy(subtitle = ""), offlineMode = true)
        val state = awaitStopped(vm)
        vm.loadMoreVideos()
        vm.refresh()
        awaitStopped(vm)
        awaitJobs(vm)

        assertNull(state.error)
        assertEquals(listOf(90L), state.videos.map { it.id })
        assertFalse(state.hasMore)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `known offline without a cache reports a local error without requesting`() = runBlocking {
        val vm = viewModel { 412 to HTML }

        vm.start(playlist(), offlineMode = true)
        val state = awaitStopped(vm)
        awaitJobs(vm)

        assertEquals(PlaylistLoadFailure.Offline, state.error)
        assertTrue(state.videos.isEmpty())
        assertFalse(state.hasMore)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `known offline preserves cached archive items and their remaining page state`() = runBlocking {
        archiveStore.values[41L to BiliPlaylistKind.COLLECTION.name] = archiveCache().copy(hasMore = true)
        val vm = viewModel { 412 to HTML }

        vm.start(playlist(), offlineMode = true)
        val state = awaitStopped(vm)
        vm.loadMoreVideos()
        awaitJobs(vm)

        assertNull(state.error)
        assertEquals(listOf(90L), state.videos.map { it.id })
        assertTrue(state.hasMore)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `offline retry retains its owner and only the current page can restore online loading`() = runBlocking {
        archiveStore.values[41L to BiliPlaylistKind.COLLECTION.name] = archiveCache()
        val vm = viewModel { 200 to archivePage(2L) }
        val owner = Any()
        vm.start(playlist(), owner = owner, offlineMode = true)
        awaitStopped(vm)
        awaitJobs(vm)

        vm.retry()
        awaitStopped(vm)
        awaitJobs(vm)
        vm.updateOfflineMode(Any(), offlineMode = false)
        vm.retry()
        awaitStopped(vm)
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf(90L), vm.uiState.value.videos.map { it.id })
        assertTrue(requests.isEmpty())

        vm.updateOfflineMode(owner, offlineMode = false)
        assertTrue(requests.isEmpty())
        vm.retry()
        val recovered = awaitStopped(vm)
        awaitJobs(vm)

        assertNull(recovered.error)
        assertEquals(listOf(2L), recovered.videos.map { it.id })
        assertTrue(contentRequests().isNotEmpty())
    }

    @Test
    fun `known offline video metadata lookup cannot issue a request`() = runBlocking {
        archiveStore.values[41L to BiliPlaylistKind.COLLECTION.name] = archiveCache()
        val vm = viewModel { 200 to archivePage(2L) }
        vm.start(playlist(), offlineMode = true)
        awaitStopped(vm)
        awaitJobs(vm)
        requests.clear()

        assertTrue(runCatching { vm.getVideoInfo("BV90") }.isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `only the current owner can switch an in flight page to cache only mode`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also(gates::add)
        val vm = viewModel { request ->
            if (request.url.queryParameter("page_num") == "2") {
                entered.countDown()
                check(release.await(5L, TimeUnit.SECONDS))
                200 to archivePage(2L)
            } else 200 to archivePage(1L)
        }
        val owner = Any()
        vm.start(playlist(), owner = owner)
        awaitStopped(vm)
        awaitJobs(vm)
        vm.loadMoreVideos()
        withContext(Dispatchers.IO) { assertTrue(entered.await(5L, TimeUnit.SECONDS)) }

        vm.updateOfflineMode(Any(), offlineMode = true)
        assertTrue(vm.uiState.value.loadingMore)
        vm.updateOfflineMode(owner, offlineMode = true)
        awaitStopped(vm)
        release.countDown()
        awaitJobs(vm)
        val requestCount = requests.size
        vm.loadMoreVideos()
        vm.refresh()
        awaitStopped(vm)
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf(1L), vm.uiState.value.videos.map { it.id })
        assertTrue(vm.uiState.value.hasMore)
        assertEquals(requestCount, requests.size)
    }

    private suspend fun verifyLateOwnerDisposal(samePlaylist: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also(gates::add)
        val ordinal = AtomicInteger()
        val vm = viewModel {
            if (ordinal.incrementAndGet() == 1) {
                200 to archivePage(1L)
            } else {
                entered.countDown()
                check(release.await(5L, TimeUnit.SECONDS))
                200 to archivePage(2L)
            }
        }
        val first = playlist()
        val second = if (samePlaylist) first else first.copy(mediaId = 42L)
        val firstOwner = Any()
        val secondOwner = Any()
        vm.start(first, owner = firstOwner)
        awaitStopped(vm)
        awaitJobs(vm)
        vm.start(second, owner = secondOwner)
        withContext(Dispatchers.IO) { assertTrue(entered.await(5L, TimeUnit.SECONDS)) }

        // 旧页面的退出动画结束后才释放，此时新页面已经开始加载
        vm.stop(firstOwner)
        release.countDown()
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(second.mediaId, vm.uiState.value.header?.mediaId)
        assertEquals(listOf(2L), vm.uiState.value.videos.map { it.id })
    }

    private suspend fun awaitStopped(vm: BiliPlaylistDetailViewModel) =
        withTimeout(5_000L) { vm.uiState.first { !it.loading } }

    private suspend fun awaitJobs(vm: BiliPlaylistDetailViewModel) {
        withTimeout(5_000L) { vm.viewModelScope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() } }
    }

    private fun contentRequests() = requests.filter { it.url.encodedPath != NAV_PATH }

    private fun viewModel(route: (Request) -> Pair<Int, String>): BiliPlaylistDetailViewModel {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val reply = if (request.url.encodedPath == NAV_PATH) 200 to NAV_JSON else route(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(reply.first).message("Fixture")
                .body(reply.second.toResponseBody("application/json".toMediaType())).build()
        }.build().also(clients::add)
        return BiliPlaylistDetailViewModel(
            application,
            BiliClient(BiliCookieSource { mapOf("SESSDATA" to "fixture") }, http),
            BiliFavoriteFolderCacheRepository(favoriteStore, temporaryFolder.newFolder()),
            BiliArchiveCacheRepository(archiveStore, temporaryFolder.newFolder())
        ).also(viewModels::add)
    }

    private class ArchiveStore : BiliArchiveCacheStore {
        val values = ConcurrentHashMap<Pair<Long, String>, BiliArchiveContentCache>()
        override suspend fun read(mediaId: Long, kind: String) = values[mediaId to kind]
        override suspend fun replace(cache: BiliArchiveContentCache) { values[cache.mediaId to cache.kind] = cache }
        override suspend fun replaceIfNewer(cache: BiliArchiveContentCache) = replace(cache)
        override suspend fun clear(mediaId: Long, kind: String) { values.remove(mediaId to kind) }
    }

    private class FavoriteStore : BiliFavoriteFolderCacheStore {
        val values = ConcurrentHashMap<Long, BiliFavoriteFolderContentCache>()
        override suspend fun read(mediaId: Long) = values[mediaId]
        override suspend fun replace(cache: BiliFavoriteFolderContentCache) { values[cache.mediaId] = cache }
        override suspend fun replaceIfNewer(cache: BiliFavoriteFolderContentCache) = replace(cache)
        override suspend fun clear(mediaId: Long) { values.remove(mediaId) }
    }

    private companion object {
        const val HTML = "<html><script>response diagnostics</script>Access denied</html>"
        const val NAV_PATH = "/x/web-interface/nav"
        const val NAV_JSON = """{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""

        fun playlist() = BiliPlaylist(41L, 41L, 7L, "Playlist", 30, "", BiliPlaylistKind.COLLECTION, "Uploader")
        fun archiveCache() = BiliArchiveContentCache(
            mediaId = 41L, kind = BiliPlaylistKind.COLLECTION.name, totalCount = 1, hasMore = false,
            videos = listOf(CachedBiliArchiveVideo(90L, "BV90", "Cached", "Uploader", 7L, "", 60))
        )
        fun archivePage(aid: Long) = """{"code":0,"data":{"meta":{"name":"Playlist","total":30},"page":{"total":30},"archives":[{"aid":$aid,"bvid":"BV$aid","title":"Video $aid","pic":"","duration":60}]}}"""
    }
}
