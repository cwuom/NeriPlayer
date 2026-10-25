package moe.ouom.neriplayer.ui.viewmodel.tab

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
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
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExploreHighQualityLoadCoalescingTest {
    private val cookies = MutableStateFlow(mapOf("MUSIC_U" to "synthetic-account-a"))
    private val client = mock(NeteaseClient::class.java)
    private val startedRequests = LinkedBlockingQueue<PendingRequest>()
    private val ownedRequests = CopyOnWriteArrayList<PendingRequest>()
    private val restoredDelegates = mutableListOf<() -> Unit>()
    private var viewModel: ExploreViewModel? = null

    @Before
    fun prepareDependencies() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val cookieRepo = mock(NeteaseCookieRepository::class.java)
        `when`(cookieRepo.cookieFlow).thenReturn(cookies)
        `when`(cookieRepo.authHealthFlow).thenReturn(MutableStateFlow(SavedCookieAuthHealth()))
        `when`(cookieRepo.getCookiesOnce()).thenAnswer { cookies.value }
        val settingsRepo = mock(SettingsRepository::class.java)
        `when`(settingsRepo.youtubeEnabledFlow).thenReturn(MutableStateFlow(false))
        replaceLazy(AppContainer::class.java, "neteaseCookieRepo", cookieRepo)
        replaceLazy(AppContainer::class.java, "settingsRepo", settingsRepo)
        replaceLazy(PlayerManager::class.java, "neteaseClient", client)
        `when`(client.getHighQualityPlaylists(anyString(), anyInt(), anyLong())).thenAnswer { invocation ->
            val request = PendingRequest(invocation.getArgument(0))
            ownedRequests += request
            startedRequests.put(request)
            request.response()
        }
    }

    @After
    fun releaseOwnedResources() {
        viewModel?.viewModelScope?.cancel()
        ownedRequests.forEach { it.release.countDown() }
        runBlocking { viewModel?.viewModelScope?.coroutineContext?.get(Job)?.join() }
        restoredDelegates.asReversed().forEach { it() }
        Dispatchers.resetMain()
    }

    @Test
    fun `same category entry requests retain the running response without cancelling or restarting it`(): Unit = runBlocking {
        val vm = createViewModel()
        val original = nextRequest()
        val originalJob = highQualityJob(vm)
        assertEquals("全部", original.category)

        vm.loadHighQuality()
        vm.loadHighQuality("tag_all")

        assertFalse("duplicate entry requests cancelled the running load", originalJob.isCancelled)
        original.complete(101L)
        val state = loadedState(vm, 101L)
        assertEquals("tag_all", state.selectedTag)
        assertNull(state.error)
        verify(client, times(1)).getHighQualityPlaylists("全部", 50, 0L)
        assertTrue(startedRequests.isEmpty())
    }

    @Test
    fun `changing category cancels the old load and only publishes the selected category`(): Unit = runBlocking {
        val vm = createViewModel()
        val original = nextRequest()
        val originalJob = highQualityJob(vm)

        vm.loadHighQuality("tag_pop")
        val replacement = nextRequest()
        assertEquals("流行", replacement.category)
        assertTrue(originalJob.isCancelled)
        replacement.complete(202L)
        assertEquals("tag_pop", loadedState(vm, 202L).selectedTag)

        original.complete(101L)
        originalJob.join()
        assertEquals(listOf(202L), vm.uiState.value.playlists.map { it.id })
        verify(client, times(1)).getHighQualityPlaylists("全部", 50, 0L)
        verify(client, times(1)).getHighQualityPlaylists("流行", 50, 0L)
    }

    @Test
    fun `cookie change reloads the same category and rejects the previous account response`(): Unit = runBlocking {
        val vm = createViewModel()
        val previousAccount = nextRequest()
        val previousJob = highQualityJob(vm)

        cookies.value = mapOf("MUSIC_U" to "synthetic-account-b")
        val currentAccount = nextRequest()
        assertEquals("全部", currentAccount.category)
        assertTrue(previousJob.isCancelled)
        currentAccount.complete(303L)
        loadedState(vm, 303L)

        previousAccount.complete(101L)
        previousJob.join()
        assertEquals(listOf(303L), vm.uiState.value.playlists.map { it.id })
        verify(client, times(2)).getHighQualityPlaylists("全部", 50, 0L)
    }

    @Test
    fun `completed category can still be explicitly reloaded`(): Unit = runBlocking {
        val vm = createViewModel()
        nextRequest().complete(101L)
        loadedState(vm, 101L)

        vm.loadHighQuality()
        nextRequest().complete(404L)

        assertEquals("tag_all", loadedState(vm, 404L).selectedTag)
        verify(client, times(2)).getHighQualityPlaylists("全部", 50, 0L)
    }

    @Test
    fun `failed category can retry and publish a successful response`(): Unit = runBlocking {
        val vm = createViewModel()
        nextRequest().fail(IOException("synthetic network failure"))
        val failed = withTimeout(5_000L) { vm.uiState.first { !it.loading && it.error != null } }
        assertTrue(failed.playlists.isEmpty())

        vm.loadHighQuality()
        nextRequest().complete(505L)

        assertNull(loadedState(vm, 505L).error)
        verify(client, times(2)).getHighQualityPlaylists("全部", 50, 0L)
    }

    private fun createViewModel(): ExploreViewModel = ExploreViewModel(
        ApplicationProvider.getApplicationContext()
    ).also { viewModel = it }

    private fun nextRequest(): PendingRequest {
        val request = startedRequests.poll(5L, TimeUnit.SECONDS)
        assertNotNull("expected the real ViewModel request to reach the client", request)
        return checkNotNull(request)
    }

    private suspend fun loadedState(vm: ExploreViewModel, id: Long): ExploreUiState = withTimeout(5_000L) {
        vm.uiState.first { !it.loading && it.playlists.map { playlist -> playlist.id } == listOf(id) }
    }

    private fun highQualityJob(vm: ExploreViewModel): Job = ExploreViewModel::class.java
        .getDeclaredField("highQualityLoadJob").apply { isAccessible = true }.get(vm) as Job

    private fun replaceLazy(owner: Class<*>, name: String, replacement: Any) {
        val delegate = owner.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(null)
        val field = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val previous = field.get(delegate)
        restoredDelegates += { field.set(delegate, previous) }
        field.set(delegate, replacement)
    }

    private class PendingRequest(val category: String) {
        val release = CountDownLatch(1)
        private var playlistId = 0L
        private var failure: IOException? = null

        fun complete(id: Long) {
            playlistId = id
            release.countDown()
        }

        fun fail(error: IOException) {
            failure = error
            release.countDown()
        }

        fun response(): String {
            check(release.await(10L, TimeUnit.SECONDS)) { "test did not release its owned request" }
            failure?.let { throw it }
            return """{"code":200,"playlists":[{"id":$playlistId,"name":"Fixture $playlistId"}]}"""
        }
    }
}
