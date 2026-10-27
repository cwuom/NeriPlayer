package moe.ouom.neriplayer.ui.viewmodel.playlist

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.youtube.cache.CachedYouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.data.model.youtube.cache.CachedYouTubeMusicPlaylistTrack
import moe.ouom.neriplayer.platform.youtube.playlist.YouTubeMusicPlaylistCacheRepository
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicLibraryGateway
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.annotation.Config
import moe.ouom.neriplayer.data.model.playlist.PlaylistLoadFailure

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class YouTubeMusicPlaylistCachedFailureTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val localRepo = mock(LocalPlaylistRepository::class.java)
    private val cacheRepo = mock(YouTubeMusicPlaylistCacheRepository::class.java)
    private val prefetches = CopyOnWriteArrayList<List<SongItem>>()
    private val viewModels = mutableListOf<YouTubeMusicPlaylistDetailViewModel>()

    @Before
    fun prepareMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking { `when`(localRepo.awaitInitialized()).thenReturn(true) }
        `when`(localRepo.playlists).thenReturn(MutableStateFlow(emptyList<LocalPlaylist>()))
        `when`(cacheRepo.read("VL41")).thenReturn(cached(savedAtMs = 1L))
    }

    @After
    fun closeOwnedResources() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `stale cache preview failure blocks cached content without prefetch or full loading`() = runBlocking {
        val gateway = FakeGateway(previewFailure = IOException("<html>private diagnostic</html>"))
        val vm = viewModel(gateway)

        vm.start(playlist())
        awaitJobs(vm)
        val state = vm.uiState.value

        assertEquals(listOf("Cached song"), state.tracks.map { it.name })
        assertEquals(PlaylistLoadFailure.Network, state.error)
        assertEquals(0, prefetches.size)
        assertEquals(0, gateway.detailRequests)
    }

    @Test
    fun `full request failure preserves cache as an error without prefetch`() = runBlocking {
        val gateway = FakeGateway(detailFailure = IOException("<html>private diagnostic</html>"))
        val vm = viewModel(gateway)

        vm.start(playlist(), forceRefresh = true)
        awaitJobs(vm)
        val state = vm.uiState.value

        assertEquals(listOf("Cached song"), state.tracks.map { it.name })
        assertEquals(PlaylistLoadFailure.Network, state.error)
        assertEquals(0, prefetches.size)
    }

    @Test
    fun `fresh cache remains a successful local read without a gateway request`() = runBlocking {
        `when`(cacheRepo.read("VL41")).thenReturn(cached(System.currentTimeMillis()))
        val gateway = FakeGateway(previewFailure = IOException("should not request"))
        val vm = viewModel(gateway)

        vm.start(playlist())
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf("Cached song"), vm.uiState.value.tracks.map { it.name })
        assertEquals(0, gateway.previewRequests)
        assertEquals(0, gateway.detailRequests)
    }

    @Test
    fun `explicit retry restores remote content and allows successful prefetch`() = runBlocking {
        val gateway = FakeGateway(
            detailFailure = IOException("unavailable"),
            detail = YouTubeMusicPlaylistDetail(
                "41", "Remote playlist", coverUrl = "", trackCount = 1,
                tracks = listOf(YouTubeMusicTrack("remote", "Remote song", "Artist"))
            )
        )
        val vm = viewModel(gateway)
        vm.start(playlist(), forceRefresh = true)
        awaitJobs(vm)
        assertEquals(0, prefetches.size)

        gateway.detailFailure = null
        vm.retry()
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf("Remote song"), vm.uiState.value.tracks.map { it.name })
        assertEquals(1, prefetches.size)
    }

    @Test
    fun `explicit offline mode uses an expired cache without gateway loading or prefetch`() = runBlocking {
        val gateway = FakeGateway(previewFailure = IOException("should not request"))
        val vm = viewModel(gateway)

        vm.start(playlist(), offlineMode = true)
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf("Cached song"), vm.uiState.value.tracks.map { it.name })
        assertEquals(0, gateway.previewRequests)
        assertEquals(0, gateway.detailRequests)
        assertEquals(0, prefetches.size)
    }

    @Test
    fun `offline playlist without cache reports a local error without gateway loading`() = runBlocking {
        `when`(cacheRepo.read("VL41")).thenReturn(null)
        val gateway = FakeGateway()
        val vm = viewModel(gateway)

        vm.start(playlist(), offlineMode = true)
        awaitJobs(vm)

        assertEquals(PlaylistLoadFailure.Offline, vm.uiState.value.error)
        assertEquals(0, gateway.previewRequests)
        assertEquals(0, gateway.detailRequests)
        assertEquals(0, prefetches.size)
    }

    @Test
    fun `offline retry preserves the expired cache without starting remote work`() = runBlocking {
        val gateway = FakeGateway(previewFailure = IOException("should not request"))
        val vm = viewModel(gateway)
        vm.start(playlist(), offlineMode = true)
        awaitJobs(vm)

        vm.retry()
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf("Cached song"), vm.uiState.value.tracks.map { it.name })
        assertEquals(0, gateway.previewRequests)
        assertEquals(0, gateway.detailRequests)
        assertEquals(0, prefetches.size)
    }

    @Test
    fun `explicit online retry resumes loading and prefetch after offline entry`() = runBlocking {
        val gateway = FakeGateway(
            detail = YouTubeMusicPlaylistDetail(
                "41", "Remote playlist", coverUrl = "", trackCount = 1,
                tracks = listOf(YouTubeMusicTrack("remote", "Remote song", "Artist"))
            )
        )
        val vm = viewModel(gateway)
        vm.start(playlist(), offlineMode = true)
        awaitJobs(vm)
        assertEquals(0, prefetches.size)

        vm.retry(offlineMode = false)
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf("Remote song"), vm.uiState.value.tracks.map { it.name })
        assertEquals(1, gateway.detailRequests)
        assertEquals(1, prefetches.size)
    }

    @Test
    fun `offline cache does not require an initialized gateway`() = runBlocking {
        val vm = viewModel(null)

        vm.start(playlist(), offlineMode = true)
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf("Cached song"), vm.uiState.value.tracks.map { it.name })
        assertEquals(0, prefetches.size)
    }

    @Test
    fun `switching offline during preview cancels full loading and prefetch`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<YouTubeMusicPlaylistDetail>()
        val gateway = FakeGateway(previewEntered = entered, previewResult = result)
        val vm = viewModel(gateway)
        val owner = Any()
        vm.start(playlist(), owner = owner)
        withTimeout(5_000L) { entered.await() }

        vm.updateOfflineMode(owner, true)
        result.complete(remoteDetail(fullyLoaded = false))
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf("Cached song"), vm.uiState.value.tracks.map { it.name })
        assertEquals(0, gateway.detailRequests)
        assertEquals(0, prefetches.size)
    }

    @Test
    fun `restoring online mode waits for explicit retry`() = runBlocking {
        val gateway = FakeGateway(detail = remoteDetail())
        val vm = viewModel(gateway)
        val owner = Any()
        vm.start(playlist(), offlineMode = true, owner = owner)
        awaitJobs(vm)

        vm.updateOfflineMode(owner, false)
        awaitJobs(vm)
        assertEquals(0, gateway.previewRequests)
        assertEquals(0, gateway.detailRequests)
        vm.retry(owner = owner)
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf("Remote song"), vm.uiState.value.tracks.map { it.name })
        assertEquals(1, gateway.detailRequests)
        assertEquals(1, prefetches.size)
    }

    @Test
    fun `old owner cannot change or stop a new owner of the same playlist`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<YouTubeMusicPlaylistDetail>()
        val gateway = FakeGateway(previewEntered = entered, previewResult = result)
        val vm = viewModel(gateway)
        val oldOwner = Any()
        val newOwner = Any()
        vm.start(playlist(), offlineMode = true, owner = oldOwner)
        awaitJobs(vm)
        vm.start(playlist(), owner = newOwner)
        withTimeout(5_000L) { entered.await() }

        vm.updateOfflineMode(oldOwner, true)
        vm.stop(oldOwner)
        result.complete(remoteDetail())
        awaitJobs(vm)

        assertNull(vm.uiState.value.error)
        assertEquals(listOf("Remote song"), vm.uiState.value.tracks.map { it.name })
        assertEquals(1, prefetches.size)
    }

    private fun remoteDetail(fullyLoaded: Boolean = true) = YouTubeMusicPlaylistDetail(
        "41", "Remote playlist", coverUrl = "", trackCount = 1,
        tracks = listOf(YouTubeMusicTrack("remote", "Remote song", "Artist")), fullyLoaded = fullyLoaded
    )

    private fun viewModel(gateway: YouTubeMusicLibraryGateway?) = YouTubeMusicPlaylistDetailViewModel(
        application, localRepo, cacheRepo, { gateway },
        { tracks, _, _ -> prefetches.add(tracks) }
    ).also(viewModels::add)

    private suspend fun awaitJobs(vm: YouTubeMusicPlaylistDetailViewModel) {
        withTimeout(5_000L) {
            vm.viewModelScope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
        }
    }

    private class FakeGateway(
        private val previewFailure: Exception? = null,
        var detailFailure: Exception? = null,
        private val detail: YouTubeMusicPlaylistDetail? = null,
        private val previewEntered: CompletableDeferred<Unit>? = null,
        private val previewResult: CompletableDeferred<YouTubeMusicPlaylistDetail>? = null
    ) : YouTubeMusicLibraryGateway {
        var previewRequests = 0
        var detailRequests = 0
        override suspend fun getLibraryPlaylists(): List<YouTubeMusicPlaylist> = emptyList()
        override suspend fun getPlaylistDetailPreview(browseId: String): YouTubeMusicPlaylistDetail {
            previewRequests++
            previewFailure?.let { throw it }
            previewEntered?.complete(Unit)
            return previewResult?.await() ?: emptyDetail()
        }
        override suspend fun getPlaylistDetail(browseId: String): YouTubeMusicPlaylistDetail {
            detailRequests++
            detailFailure?.let { throw it }
            return detail ?: emptyDetail()
        }
        private fun emptyDetail() = YouTubeMusicPlaylistDetail("41", "Remote playlist", coverUrl = "", trackCount = 0, tracks = emptyList())
    }

    private fun playlist() = YouTubeMusicPlaylist("VL41", "41", "Playlist", "", "", 1)

    private fun cached(savedAtMs: Long) = CachedYouTubeMusicPlaylistDetail(
        browseId = "VL41", playlistId = "41", title = "Cached playlist", subtitle = "", coverUrl = "",
        trackCount = 1, firstPageSignature = "41#cached|", savedAtMs = savedAtMs,
        tracks = listOf(CachedYouTubeMusicPlaylistTrack("cached", "Cached song", "Artist", "Album", 1000L, ""))
    )
}
