package moe.ouom.neriplayer.ui.viewmodel.playlist

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlaylistDetailPreviewAuthorityTest {
    @Test
    fun `late legacy preview cannot replace an authoritative resolved missing playlist`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mock(LocalPlaylistRepository::class.java)
        val preview = CompletableDeferred<LocalPlaylist?>()
        val previewStarted = CompletableDeferred<Unit>()
        `when`(repository.playlists).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.awaitInitialized()).thenReturn(true)
        doAnswer { call ->
            previewStarted.complete(Unit)
            // suspend 方法的 JVM 最后一个参数就是其返回值的 continuation
            @Suppress("UNCHECKED_CAST")
            val continuation = call.rawArguments.last() as Continuation<LocalPlaylist?>
            val action: suspend () -> LocalPlaylist? = { preview.await() }
            action.startCoroutineUninterceptedOrReturn(continuation)
        }.`when`(repository).readFastPlaylist(2)
        val singleton = LocalPlaylistRepository::class.java.getDeclaredField("INSTANCE")
            .also { it.isAccessible = true }
        val previousRepository = singleton.get(null)
        var model: LocalPlaylistDetailViewModel? = null
        try {
            singleton.set(null, repository)
            val viewModel = LocalPlaylistDetailViewModel(mock(Application::class.java))
            model = viewModel
            viewModel.start(2)
            testScheduler.runCurrent()

            assertTrue(previewStarted.isCompleted)
            assertTrue(viewModel.uiState.value.isResolved)
            assertNull(viewModel.uiState.value.playlist)
            preview.complete(LocalPlaylist(id = 2, name = "stale preview"))
            testScheduler.runCurrent()

            assertTrue(viewModel.uiState.value.isResolved)
            assertEquals(2L, viewModel.uiState.value.requestedPlaylistId)
            assertNull(viewModel.uiState.value.playlist)
        } finally {
            model?.viewModelScope?.cancel()
            singleton.set(null, previousRepository)
            Dispatchers.resetMain()
        }
    }
}
