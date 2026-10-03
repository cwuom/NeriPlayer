package moe.ouom.neriplayer.ui.viewmodel.tab

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoriteArtist
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FollowedArtistImportViewModelTest {
    private val viewModels = mutableListOf<FollowedArtistImportViewModel>()

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `a failed remote load never imports a partial list`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var writes = 0
        val vm = create(
            load = { throw IOException("network") },
            merge = { _, _, _ -> writes++; 0 },
            dispatcher = dispatcher
        )
        vm.importArtists(FAVORITE_SOURCE_NETEASE_ARTIST)
        testScheduler.runCurrent()
        assertEquals(0, writes)
        assertFalse(vm.uiState.value.loading)
        assertNotNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.importedCount)
    }

    @Test
    fun `repeated taps share one load and import the complete result once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val pending = CompletableDeferred<List<FavoriteArtist>>()
        var loads = 0
        var saved = emptyList<FavoriteArtist>()
        var startedAt = 0L
        val vm = create(
            load = { loads++; pending.await() },
            merge = { _, artists, started -> saved = artists; startedAt = started; artists.size },
            dispatcher = dispatcher
        )
        vm.importArtists(FAVORITE_SOURCE_NETEASE_ARTIST)
        vm.importArtists(FAVORITE_SOURCE_NETEASE_ARTIST)
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.loading)
        assertTrue(saved.isEmpty())
        pending.complete(listOf(FavoriteArtist(1, "A"), FavoriteArtist(2, "B")))
        testScheduler.runCurrent()
        assertEquals(1, loads)
        assertEquals(listOf(1L, 2L), saved.map { it.id })
        assertEquals(100L, startedAt)
        assertEquals(2, vm.uiState.value.importedCount)
    }

    @Test
    fun `account changes during loading discard the response`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val pending = CompletableDeferred<List<FavoriteArtist>>()
        var account: Any? = "first"
        var writes = 0
        val vm = create(
            load = { pending.await() },
            merge = { _, _, _ -> writes++; 1 },
            account = { account },
            dispatcher = dispatcher
        )
        vm.importArtists(FAVORITE_SOURCE_NETEASE_ARTIST)
        testScheduler.runCurrent()
        account = "second"
        pending.complete(listOf(FavoriteArtist(1, "A")))
        testScheduler.runCurrent()
        assertEquals(0, writes)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `missing authentication does not send a remote request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var loads = 0
        val vm = create(
            load = { loads++; emptyList() },
            merge = { _, _, _ -> 0 },
            account = { null },
            dispatcher = dispatcher
        )
        vm.importArtists(FAVORITE_SOURCE_NETEASE_ARTIST)
        testScheduler.runCurrent()
        assertEquals(0, loads)
        assertFalse(vm.uiState.value.loading)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `same account cookie refresh can still import artists`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val pending = CompletableDeferred<List<FavoriteArtist>>()
        var bundle = YouTubeAuthBundle(
            cookies = mapOf("SAPISID" to "synthetic-account", "PREF" to "first"),
            xGoogAuthUser = "0", savedAt = 1
        )
        var writes = 0
        val vm = create(
            load = { pending.await() },
            merge = { _, _, _ -> writes++; 1 },
            account = { youtubeFollowedArtistAccount(bundle) },
            dispatcher = dispatcher
        )
        vm.importArtists(FAVORITE_SOURCE_YOUTUBE_ARTIST)
        testScheduler.runCurrent()
        bundle = bundle.copy(cookies = bundle.cookies + ("PREF" to "second"), savedAt = 2)
        pending.complete(listOf(FavoriteArtist(1, "A")))
        testScheduler.runCurrent()
        assertEquals(1, writes)
        assertEquals(1, vm.uiState.value.importedCount)
    }

    @Test
    fun `youtube account selector changes its identity`() {
        val bundle = YouTubeAuthBundle(
            cookies = mapOf("SAPISID" to "synthetic-account"), xGoogAuthUser = "0"
        )
        org.junit.Assert.assertNotEquals(
            youtubeFollowedArtistAccount(bundle),
            youtubeFollowedArtistAccount(bundle.copy(xGoogAuthUser = "1"))
        )
    }

    @Test
    fun `consumed feedback is cleared and the same error can be reported again`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val vm = create(
            load = { emptyList() }, merge = { _, _, _ -> 0 },
            account = { null }, dispatcher = dispatcher
        )
        vm.importArtists(FAVORITE_SOURCE_NETEASE_ARTIST)
        vm.consumeFeedback(vm.uiState.value)
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.importedCount)
        vm.importArtists(FAVORITE_SOURCE_NETEASE_ARTIST)
        assertEquals(FollowedArtistImportError.LOGIN_REQUIRED, vm.uiState.value.error)
    }

    @Test
    fun `acknowledging old feedback cannot clear a newer request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var account: Any? = null
        val pending = CompletableDeferred<List<FavoriteArtist>>()
        val vm = create(
            load = { pending.await() }, merge = { _, _, _ -> 0 },
            account = { account }, dispatcher = dispatcher
        )
        vm.importArtists(FAVORITE_SOURCE_NETEASE_ARTIST)
        val oldFeedback = vm.uiState.value
        account = "account"
        vm.importArtists(FAVORITE_SOURCE_NETEASE_ARTIST)
        vm.consumeFeedback(oldFeedback)
        assertTrue(vm.uiState.value.loading)
        assertNull(vm.uiState.value.error)
    }

    private fun create(
        load: suspend (String) -> List<FavoriteArtist>,
        merge: suspend (String, List<FavoriteArtist>, Long) -> Int,
        account: (String) -> Any? = { "account" },
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): FollowedArtistImportViewModel = FollowedArtistImportViewModel(
        mock(Application::class.java), load, merge, account, { 100L }, dispatcher
    ).also(viewModels::add)
}
