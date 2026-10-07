package moe.ouom.neriplayer.ui.viewmodel.artist

import android.app.Application
import androidx.lifecycle.viewModelScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeMusicCreatorDetailStartTest {

    private val application = mock(Application::class.java)
    private val creatorA = creator("UC_a")
    private val creatorB = creator("UC_b")
    private val loads = CopyOnWriteArrayList<String>()
    private val failures = ConcurrentHashMap<String, Exception>()
    private val viewModels = mutableListOf<YouTubeMusicCreatorDetailViewModel>()

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `a loaded creator is only reloaded when a refresh is forced`() = runTest {
        val viewModel = viewModel()

        viewModel.start(creatorA)
        val loaded = viewModel.awaitLoaded()
        assertEquals(detail(creatorA), loaded.detail)

        viewModel.start(creatorA)
        runCurrent()
        assertSame(loaded, viewModel.uiState.value)

        viewModel.start(creatorA, forceRefresh = true)
        assertEquals(
            YouTubeMusicCreatorDetailUiState(loading = true, detail = detail(creatorA)),
            viewModel.uiState.value
        )
        assertEquals(detail(creatorA), viewModel.awaitLoaded().detail)
        assertEquals(listOf("UC_a", "UC_a"), loads)
    }

    @Test
    fun `switching creators drops the previous detail while the next one loads`() = runTest {
        val viewModel = viewModel()
        viewModel.start(creatorA)
        viewModel.awaitLoaded()

        viewModel.start(creatorB)

        assertEquals(YouTubeMusicCreatorDetailUiState(loading = true, detail = null), viewModel.uiState.value)
        assertEquals(detail(creatorB), viewModel.awaitLoaded().detail)
    }

    @Test
    fun `starting the same creator before it loads replaces the pending request`() = runTest {
        val viewModel = viewModel()

        viewModel.start(creatorA)
        viewModel.start(creatorA)

        assertEquals(detail(creatorA), viewModel.awaitLoaded().detail)
        assertEquals(listOf("UC_a"), loads)
    }

    @Test
    fun `playback requests wait for a creator and a restart cancels the pending queue`() = runTest {
        val viewModel = viewModel()
        val section = YouTubeMusicCreatorSection(title = "Songs", items = listOf(song))

        viewModel.playSectionSong(section, song)
        runCurrent()
        assertEquals(YouTubeMusicCreatorDetailUiState(), viewModel.uiState.value)

        viewModel.start(creatorA)
        viewModel.playSectionSong(section, song)
        viewModel.playSectionSong(section, song)
        viewModel.start(creatorB)

        val loaded = viewModel.awaitLoaded()
        assertEquals(YouTubeMusicCreatorDetailUiState(loading = false, detail = detail(creatorB)), loaded)
        assertEquals(listOf("UC_b"), loads)
    }

    @Test
    fun `a failed refresh keeps the shown detail and reports the failure`() = runTest {
        `when`(application.getString(CoreCommonR.string.youtube_creator_load_failed, "offline"))
            .thenReturn("Load failed: offline")
        `when`(application.getString(CoreCommonR.string.youtube_creator_load_failed, "IllegalStateException"))
            .thenReturn("Load failed: IllegalStateException")
        val viewModel = viewModel()
        viewModel.start(creatorA)
        viewModel.awaitLoaded()

        failures["UC_a"] = IllegalStateException("offline")
        viewModel.start(creatorA, forceRefresh = true)
        assertEquals(
            YouTubeMusicCreatorDetailUiState(loading = false, error = "Load failed: offline", detail = detail(creatorA)),
            viewModel.awaitLoaded()
        )

        failures["UC_a"] = IllegalStateException()
        viewModel.retry()
        assertEquals("Load failed: IllegalStateException", viewModel.awaitLoaded().error)
    }

    private fun TestScope.viewModel(): YouTubeMusicCreatorDetailViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return YouTubeMusicCreatorDetailViewModel(application) { creator ->
            loads += creator.browseId
            failures[creator.browseId]?.let { throw it }
            detail(creator)
        }.also(viewModels::add)
    }

    private suspend fun YouTubeMusicCreatorDetailViewModel.awaitLoaded(): YouTubeMusicCreatorDetailUiState =
        uiState.first { !it.loading }

    private companion object {
        val song = YouTubeMusicCreatorItem(
            type = YouTubeMusicCreatorItemType.Song,
            title = "Song",
            subtitle = "Artist",
            coverUrl = "",
            videoId = "video-1"
        )

        fun creator(browseId: String) = YouTubeMusicCreatorSummary(
            browseId = browseId,
            title = "Creator $browseId",
            subtitle = "",
            coverUrl = ""
        )

        fun detail(creator: YouTubeMusicCreatorSummary) = YouTubeMusicCreatorDetail(
            header = YouTubeMusicCreatorHeader(
                browseId = creator.browseId,
                title = creator.title,
                subtitle = creator.subtitle,
                coverUrl = creator.coverUrl
            ),
            sections = emptyList()
        )
    }
}
