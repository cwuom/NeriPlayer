package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
    private val viewModels = mutableListOf<BackupRestoreViewModel>()

    @After
    fun resetMainDispatcher() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `playlist count observation waits for slow source without blocking ui state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val countFlow = MutableStateFlow(0)
        val sourceFactory = BlockingPlaylistCountSourceFactory(countFlow)
        val viewModel = createViewModel(sourceFactory)

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

    @Test
    fun `repeated observation keeps the pending application source`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val countFlow = MutableStateFlow(7)
        val sourceFactory = BlockingPlaylistCountSourceFactory(countFlow)
        val viewModel = createViewModel(sourceFactory)
        val applicationContext = mockContext()

        viewModel.observePlaylistCount(mockContext(applicationContext))
        testScheduler.runCurrent()
        viewModel.observePlaylistCount(mockContext(applicationContext))
        testScheduler.runCurrent()

        assertEquals(listOf(applicationContext), sourceFactory.contexts)

        sourceFactory.allowCreate.complete(Unit)
        testScheduler.runCurrent()
        viewModel.observePlaylistCount(mockContext(applicationContext))
        testScheduler.runCurrent()

        assertEquals(7, viewModel.uiState.value.currentPlaylistCount)
        assertEquals(1, countFlow.subscriptionCount.value)
        assertEquals(listOf(applicationContext), sourceFactory.contexts)
    }

    @Test
    fun `observation retries after source initialization is not ready`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val countFlow = MutableStateFlow(9)
        var ready = false
        var createCount = 0
        val viewModel = createViewModel {
            createCount += 1
            object : PlaylistCountSource {
                override val playlistCount: StateFlow<Int> = countFlow

                override suspend fun awaitInitialized(): Boolean = ready
            }
        }
        val context = mockContext()

        viewModel.observePlaylistCount(context)
        testScheduler.runCurrent()
        assertEquals(0, viewModel.uiState.value.currentPlaylistCount)
        assertEquals(0, countFlow.subscriptionCount.value)

        ready = true
        viewModel.observePlaylistCount(context)
        testScheduler.runCurrent()

        assertEquals(2, createCount)
        assertEquals(9, viewModel.uiState.value.currentPlaylistCount)
        assertEquals(1, countFlow.subscriptionCount.value)
    }

    private fun createViewModel(factory: PlaylistCountSourceFactory): BackupRestoreViewModel {
        return BackupRestoreViewModel(factory).also(viewModels::add)
    }

    private fun mockContext(applicationContext: Context? = null): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(applicationContext ?: context)
        return context
    }

    private class BlockingPlaylistCountSourceFactory(
        private val countFlow: MutableStateFlow<Int>
    ) : PlaylistCountSourceFactory {
        val createStarted = CompletableDeferred<Unit>()
        val allowCreate = CompletableDeferred<Unit>()
        val contexts = mutableListOf<Context>()

        override suspend fun create(context: Context): PlaylistCountSource {
            contexts += context
            createStarted.complete(Unit)
            allowCreate.await()
            return object : PlaylistCountSource {
                override val playlistCount: StateFlow<Int> = countFlow

                override suspend fun awaitInitialized(): Boolean = true
            }
        }
    }
}
