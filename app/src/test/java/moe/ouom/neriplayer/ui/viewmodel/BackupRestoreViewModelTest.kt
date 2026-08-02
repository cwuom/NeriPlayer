package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreViewModelTest {
    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `playlist count observation waits for slow source without blocking ui state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val countFlow = MutableStateFlow(0)
        val sourceFactory = BlockingPlaylistCountSourceFactory(countFlow)
        val viewModel = BackupRestoreViewModel(sourceFactory)

        viewModel.observePlaylistCount(mockContext())
        testScheduler.runCurrent()

        assertTrue(sourceFactory.createStarted.isCompleted)
        assertEquals(0, viewModel.uiState.value.currentPlaylistCount)

        countFlow.value = 12
        testScheduler.runCurrent()
        assertEquals(0, viewModel.uiState.value.currentPlaylistCount)

        sourceFactory.allowCreate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(12, viewModel.uiState.value.currentPlaylistCount)

        countFlow.value = 15
        testScheduler.runCurrent()
        assertEquals(15, viewModel.uiState.value.currentPlaylistCount)
    }

    private fun mockContext(): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        return context
    }

    private class BlockingPlaylistCountSourceFactory(
        private val countFlow: MutableStateFlow<Int>
    ) : PlaylistCountSourceFactory {
        val createStarted = CompletableDeferred<Unit>()
        val allowCreate = CompletableDeferred<Unit>()

        override suspend fun create(context: Context): PlaylistCountSource {
            createStarted.complete(Unit)
            allowCreate.await()
            return object : PlaylistCountSource {
                override val playlistCount: StateFlow<Int> = countFlow

                override suspend fun awaitInitialized(): Boolean = true
            }
        }
    }
}
