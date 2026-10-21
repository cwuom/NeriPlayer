package moe.ouom.neriplayer.ui.viewmodel

import java.io.IOException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaBasicInfo
import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia
import moe.ouom.neriplayer.data.model.encyclopedia.encyclopediaSongKey
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.netease.encyclopedia.SongEncyclopediaRepository
import moe.ouom.neriplayer.platform.netease.encyclopedia.SongEncyclopediaResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NowPlayingViewModel 百科状态机单元测试。
 *
 * 覆盖: 加载→成功 / 本地歌曲→NotSupported / 数据源无内容→Empty / 网络失败→Failed /
 * 取消→Idle / 重复打开不重复请求 / 快速切歌旧结果丢弃 / 重试绕过成功缓存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NowPlayingViewModelEncyclopediaTest {

    @Test
    fun `loadEncyclopedia transitions loading to success with fetched data`() = encyclopediaTest {
        val repository = FakeEncyclopediaRepository(
            resultFor = { song ->
                SongEncyclopediaResult.Success(
                    SongEncyclopedia(
                        songKey = song.encyclopediaSongKey(),
                        source = MusicPlatform.CLOUD_MUSIC,
                        basicInfo = EncyclopediaBasicInfo(song.name),
                        fetchedAtMs = 1000L
                    )
                )
            }
        )
        val vm = newViewModel(repository)
        vm.loadEncyclopedia(neteaseSong(1L))
        advanceUntilIdle()

        val state = vm.encyclopediaState.value
        assertTrue(state is SongEncyclopediaUiState.Success)
        assertEquals("Song1", (state as SongEncyclopediaUiState.Success).data.basicInfo.name)
    }

    @Test
    fun `local song yields NotSupported state`() = encyclopediaTest {
        val vm = newViewModel(FakeEncyclopediaRepository(resultFor = { SongEncyclopediaResult.NotSupported }))
        vm.loadEncyclopedia(localSong())
        advanceUntilIdle()

        assertEquals(SongEncyclopediaUiState.NotSupported, vm.encyclopediaState.value)
    }

    @Test
    fun `unavailable encyclopedia yields Empty state`() = encyclopediaTest {
        val vm = newViewModel(FakeEncyclopediaRepository(resultFor = { SongEncyclopediaResult.NotAvailable }))
        vm.loadEncyclopedia(neteaseSong(7L))
        advanceUntilIdle()

        assertEquals(SongEncyclopediaUiState.Empty, vm.encyclopediaState.value)
    }

    @Test
    fun `network failure yields Failed state with message`() = encyclopediaTest {
        val vm = newViewModel(FakeEncyclopediaRepository(resultFor = { SongEncyclopediaResult.Failed(IOException("boom")) }))
        vm.loadEncyclopedia(neteaseSong(9L))
        advanceUntilIdle()

        val state = vm.encyclopediaState.value
        assertTrue(state is SongEncyclopediaUiState.Failed)
        assertTrue((state as SongEncyclopediaUiState.Failed).message?.contains("boom") == true)
    }

    @Test
    fun `cancelEncyclopediaLoad resets to Idle`() = encyclopediaTest {
        val vm = newViewModel(FakeEncyclopediaRepository(resultFor = { SongEncyclopediaResult.NotAvailable }))
        vm.loadEncyclopedia(neteaseSong(3L))
        vm.cancelEncyclopediaLoad()
        advanceUntilIdle()

        assertEquals(SongEncyclopediaUiState.Idle, vm.encyclopediaState.value)
    }

    @Test
    fun `opening encyclopedia twice for the same song does not refetch`() = encyclopediaTest {
        val repository = FakeEncyclopediaRepository(
            resultFor = { SongEncyclopediaResult.Success(fakeEncyclopedia(it)) }
        )
        val vm = newViewModel(repository)
        val song = neteaseSong(5L)

        vm.loadEncyclopedia(song)
        advanceUntilIdle()
        val firstRequestCount = repository.requestCount
        assertTrue(firstRequestCount == 1)

        // 第二次打开同一首歌，不应再请求
        vm.loadEncyclopedia(song)
        advanceUntilIdle()
        assertEquals(1, repository.requestCount)

        // 强制重试则必须重新请求
        vm.retryEncyclopediaLoad(song)
        advanceUntilIdle()
        assertEquals(2, repository.requestCount)
    }

    @Test
    fun `switching songs before the first response returns never shows stale data`() = encyclopediaTest {
        val repository = FakeEncyclopediaRepository(resultFor = { song ->
            SongEncyclopediaResult.Success(fakeEncyclopedia(song))
        })
        val vm = newViewModel(repository)
        val first = neteaseSong(1L)
        val second = neteaseSong(2L)

        // 发起第一个请求但不让它完成
        vm.loadEncyclopedia(first)
        // 立即切歌：第二个请求会抢占代际票据
        vm.loadEncyclopedia(second)
        advanceUntilIdle()

        val state = vm.encyclopediaState.value
        assertTrue(state is SongEncyclopediaUiState.Success)
        assertEquals(second.encyclopediaSongKey(), (state as SongEncyclopediaUiState.Success).data.songKey)
    }

    @Test
    fun `cancellationException is not converted to Failed`() = encyclopediaTest {
        val vm = newViewModel(FakeEncyclopediaRepository(resultFor = { throw CancellationException("cancelled") }))
        vm.loadEncyclopedia(neteaseSong(4L))
        advanceUntilIdle()

        // 取消异常应向上传播，状态保持 Loading，不会变成 Failed
        assertEquals(SongEncyclopediaUiState.Loading, vm.encyclopediaState.value)
    }

    @Test
    fun `forSongKey downgrades stale Success to Loading`() {
        val stale = SongEncyclopediaUiState.Success(fakeEncyclopedia(neteaseSong(1L)))

        val forAnother = stale.forSongKey(neteaseSong(2L).encyclopediaSongKey())
        assertEquals(SongEncyclopediaUiState.Loading, forAnother)

        val forSame = stale.forSongKey(neteaseSong(1L).encyclopediaSongKey())
        assertTrue(forSame is SongEncyclopediaUiState.Success)
    }

    private fun fakeEncyclopedia(song: SongItem): SongEncyclopedia = SongEncyclopedia(
        songKey = song.encyclopediaSongKey(),
        source = MusicPlatform.CLOUD_MUSIC,
        basicInfo = EncyclopediaBasicInfo(song.name),
        fetchedAtMs = 0L
    )

    private class FakeEncyclopediaRepository(
        private val resultFor: (SongItem) -> SongEncyclopediaResult
    ) : SongEncyclopediaRepository {
        var requestCount = 0
            private set

        override suspend fun getEncyclopedia(song: SongItem): SongEncyclopediaResult {
            requestCount += 1
            return resultFor(song)
        }
    }

    private fun newViewModel(repository: SongEncyclopediaRepository): NowPlayingViewModel =
        NowPlayingViewModel().apply { encyclopediaRepository = repository }

    private fun neteaseSong(id: Long): SongItem = SongItem(
        id = id,
        name = "Song$id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 200_000L,
        coverUrl = null,
        channelId = "netease",
        audioId = id.toString()
    )

    private fun localSong(): SongItem = SongItem(
        id = 42L,
        name = "Local",
        artist = "Local Artist",
        album = "Local Album",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        mediaUri = "content://media/42"
    )

    private fun encyclopediaTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Before
    fun setUp() {
        if (!AppContainer.isInitialized()) {
            AppContainer.initialize(ApplicationProvider.getApplicationContext())
        }
    }
}
