package moe.ouom.neriplayer.ui.viewmodel.playlist

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.netease.cache.CachedNeteasePlaylistDetail
import moe.ouom.neriplayer.data.model.netease.cache.CachedNeteasePlaylistHeader
import moe.ouom.neriplayer.data.model.netease.cache.CachedNeteasePlaylistTrack
import moe.ouom.neriplayer.platform.netease.NeteasePlaylistCacheRepository
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class NeteasePlaylistCachedFailureTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val client = mock(NeteaseClient::class.java)
    private val cacheRepo = mock(NeteasePlaylistCacheRepository::class.java)
    private val cookieFlow = MutableStateFlow(emptyMap<String, String>())
    private val cookieRepo = mock(NeteaseCookieRepository::class.java) { invocation ->
        when (invocation.method.name) {
            "getCookiesOnce" -> emptyMap<String, String>()
            "getCookieFlow" -> cookieFlow
            "withCurrentCookies" -> invocation.getArgument<(Map<String, String>) -> Any?>(0)(emptyMap())
            else -> Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    }
    private val viewModels = mutableListOf<NeteaseCollectionDetailViewModel>()
    private val gates = mutableListOf<CountDownLatch>()

    @Before
    fun prepareMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        `when`(cacheRepo.read(41L, null)).thenReturn(cached())
    }

    @After
    fun closeOwnedResources() {
        gates.forEach(CountDownLatch::countDown)
        runBlocking {
            viewModels.forEach { it.viewModelScope.cancel() }
            viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.join() }
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `network failure retains cached tracks with a safe blocking error`() = runBlocking {
        `when`(client.getPlaylistDetailCancellable(41L)).thenAnswer { throw IOException("<html>private diagnostic</html>") }
        val vm = viewModel()

        vm.startPlaylist(playlist())
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertEquals(listOf(7L), state.tracks.map { it.id })
        assertEquals(application.getString(CoreCommonR.string.playlist_error_network), state.error)
        assertFalse(state.error.orEmpty().contains("private diagnostic"))
    }

    @Test
    fun `parse failure retains cached tracks with a safe blocking error`() = runBlocking {
        `when`(client.getPlaylistDetailCancellable(41L)).thenReturn("<html>private diagnostic</html>")
        val vm = viewModel()

        vm.startPlaylist(playlist())
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertEquals(listOf(7L), state.tracks.map { it.id })
        assertEquals(application.getString(CoreCommonR.string.playlist_error_unknown), state.error)
    }

    @Test
    fun `explicit retry replaces the failed cache state with a successful response`() = runBlocking {
        `when`(client.getPlaylistDetailCancellable(41L))
            .thenAnswer { throw IOException("unavailable") }
            .thenReturn("""{"code":200,"playlist":{"id":41,"name":"Remote playlist","tracks":[]}}""")
        val vm = viewModel()
        vm.startPlaylist(playlist())
        withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        vm.retry()
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertNull(state.error)
        assertEquals("Remote playlist", state.header?.name)
    }

    @Test
    fun `explicit offline mode reads compatible cached tracks without a request`(): Unit = runBlocking {
        `when`(client.getPlaylistDetailCancellable(41L)).thenAnswer { throw IOException("should not request") }
        val vm = viewModel()

        vm.startPlaylist(playlist(), offlineMode = true)
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertNull(state.error)
        assertEquals(listOf(7L), state.tracks.map { it.id })
        verify(client, never()).getPlaylistDetailCancellable(41L)
    }

    @Test
    fun `offline playlist without cache reports a safe local error without a request`(): Unit = runBlocking {
        `when`(cacheRepo.read(41L, null)).thenReturn(null)
        `when`(client.getPlaylistDetailCancellable(41L)).thenAnswer { throw IOException("should not request") }
        val vm = viewModel()

        vm.startPlaylist(playlist(), offlineMode = true)
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertEquals(application.getString(CoreCommonR.string.playlist_error_offline_no_cache), state.error)
        assertEquals(emptyList<SongItem>(), state.tracks)
        verify(client, never()).getPlaylistDetailCancellable(41L)
    }

    @Test
    fun `retry retains offline mode and cached content`(): Unit = runBlocking {
        `when`(client.getPlaylistDetailCancellable(41L)).thenAnswer { throw IOException("should not request") }
        val vm = viewModel()
        vm.startPlaylist(playlist(), offlineMode = true)
        withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        vm.retry()
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertNull(state.error)
        assertEquals(listOf(7L), state.tracks.map { it.id })
        verify(client, never()).getPlaylistDetailCancellable(41L)
    }

    @Test
    fun `explicit online retry resumes remote loading after offline entry`(): Unit = runBlocking {
        `when`(client.getPlaylistDetailCancellable(41L))
            .thenReturn("""{"code":200,"playlist":{"id":41,"name":"Remote playlist","tracks":[]}}""")
        val vm = viewModel()
        vm.startPlaylist(playlist(), offlineMode = true)
        val offline = withTimeout(5_000L) { vm.uiState.first { !it.loading } }
        assertEquals(listOf(7L), offline.tracks.map { it.id })

        vm.retry(offlineMode = false)
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertNull(state.error)
        assertEquals("Remote playlist", state.header?.name)
        verify(client, times(1)).getPlaylistDetailCancellable(41L)
    }

    @Test
    fun `offline album does not use the playlist cache or request remote content`(): Unit = runBlocking {
        `when`(client.getAlbumDetail(41L)).thenAnswer { throw IOException("should not request") }
        val vm = viewModel()

        vm.startAlbum(AlbumSummary(41L, "Album", "", 1), offlineMode = true)
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertEquals(application.getString(CoreCommonR.string.playlist_error_offline_no_cache), state.error)
        verify(client, never()).getAlbumDetail(41L)
    }

    @Test
    fun `switching offline during detail loading cancels later track completion`(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also(gates::add)
        `when`(client.getPlaylistDetailCancellable(41L)).thenAnswer {
            entered.countDown()
            check(release.await(5L, TimeUnit.SECONDS))
            """{"code":200,"playlist":{"id":41,"name":"Remote playlist","tracks":[],"trackIds":[{"id":8}]}}"""
        }
        `when`(client.getSongDetailCancellable(listOf(8L))).thenReturn("""{"code":200,"songs":[]}""")
        val vm = viewModel()
        val owner = Any()
        val initialJobs = vm.viewModelScope.coroutineContext[Job]?.children?.toSet().orEmpty()
        vm.startPlaylist(playlist(), owner = owner)
        withContext(Dispatchers.IO) { check(entered.await(5L, TimeUnit.SECONDS)) }
        val requestJobs = vm.viewModelScope.coroutineContext[Job]?.children?.filter { it !in initialJobs }?.toList().orEmpty()

        vm.updateOfflineMode(owner, true)
        release.countDown()
        withTimeout(5_000L) { requestJobs.forEach { it.join() } }
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertNull(state.error)
        assertEquals(listOf(7L), state.tracks.map { it.id })
        verify(client, never()).getSongDetailCancellable(listOf(8L))
    }

    @Test
    fun `restoring online mode waits for explicit retry`(): Unit = runBlocking {
        `when`(client.getPlaylistDetailCancellable(41L))
            .thenReturn("""{"code":200,"playlist":{"id":41,"name":"Remote playlist","tracks":[]}}""")
        val vm = viewModel()
        val owner = Any()
        vm.startPlaylist(playlist(), offlineMode = true, owner = owner)
        withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        vm.updateOfflineMode(owner, false)
        verify(client, never()).getPlaylistDetailCancellable(41L)
        vm.retry(owner = owner)
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertNull(state.error)
        assertEquals("Remote playlist", state.header?.name)
        verify(client, times(1)).getPlaylistDetailCancellable(41L)
    }

    @Test
    fun `old owner cannot change or stop a new owner of the same playlist`(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also(gates::add)
        `when`(client.getPlaylistDetailCancellable(41L)).thenAnswer {
            entered.countDown()
            check(release.await(5L, TimeUnit.SECONDS))
            """{"code":200,"playlist":{"id":41,"name":"Remote playlist","tracks":[]}}"""
        }
        val vm = viewModel()
        val oldOwner = Any()
        val newOwner = Any()
        vm.startPlaylist(playlist(), offlineMode = true, owner = oldOwner)
        withTimeout(5_000L) { vm.uiState.first { !it.loading } }
        vm.startPlaylist(playlist(), owner = newOwner)
        withContext(Dispatchers.IO) { check(entered.await(5L, TimeUnit.SECONDS)) }

        vm.updateOfflineMode(oldOwner, true)
        vm.stop(oldOwner)
        release.countDown()
        val state = withTimeout(5_000L) { vm.uiState.first { !it.loading } }

        assertNull(state.error)
        assertEquals("Remote playlist", state.header?.name)
    }

    private fun viewModel() = NeteaseCollectionDetailViewModel(application, client, cookieRepo, cacheRepo)
        .also(viewModels::add)

    private fun playlist() = PlaylistSummary(41L, "Playlist", "", 0L, 1)

    private fun cached() = CachedNeteasePlaylistDetail(
        playlistId = 41L,
        header = CachedNeteasePlaylistHeader(41L, "Cached playlist", "", 0L, 1),
        recentTrackSignature = "cached",
        tracks = listOf(CachedNeteasePlaylistTrack(7L, "Cached song", "Artist", "Album", 0L, 1000L, null, "7"))
    )
}
