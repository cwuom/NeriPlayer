package moe.ouom.neriplayer.ui.viewmodel.tab

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankPeriod
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankSong
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankType
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.ui.viewmodel.tab.bili.BiliMusicHomeRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeBilibiliInternationalizationTest {
    private val dispatcher = StandardTestDispatcher()
    private val internationalEnabled = MutableStateFlow(false)
    private val savedBilibiliEnabled = MutableStateFlow(true)
    private val biliClient = mock(BiliClient::class.java)
    private val restoredDependencies = mutableListOf<() -> Unit>()
    private val period = BiliMusicRankPeriod(101L, 1, 1000L)
    private val song = BiliMusicRankSong(
        musicId = "fixture-song", rank = 1, title = "Fixture song", singer = "Singer", album = "Album",
        coverUrl = "", videoCoverUrl = "", creationAid = 201L, creationBvid = "BV-fixture",
        creationCid = 202L, creationDurationSec = 30, uploader = "Uploader", uploaderMid = 203L,
        heat = 10L, canListen = true
    )
    private val hotVideo = BiliMusicHotVideo(
        aid = 301L, bvid = "BV-hot-fixture", cid = 302L, title = "Fixture video", coverUrl = "",
        durationSec = 30, uploader = "Uploader", uploaderMid = 303L, viewCount = 10L,
        danmakuCount = 0L, pubdateSec = 1000L, tname = "Music"
    )

    @Before
    fun prepareDependencies() {
        Dispatchers.setMain(dispatcher)
        val cookieRepo = mock(NeteaseCookieRepository::class.java) { invocation ->
            if (invocation.method.name == "withCurrentCookies") {
                invocation.getArgument<(Map<String, String>) -> Any?>(0)(emptyMap())
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        `when`(cookieRepo.getCookiesOnce()).thenReturn(emptyMap())
        `when`(cookieRepo.cookieFlow).thenReturn(MutableStateFlow(emptyMap()))
        val settingsRepo = mock(SettingsRepository::class.java)
        `when`(settingsRepo.homeCardBilibiliFlow).thenReturn(savedBilibiliEnabled)
        `when`(settingsRepo.internationalizationEnabledFlow).thenReturn(internationalEnabled)
        `when`(settingsRepo.youtubeEnabledFlow).thenReturn(MutableStateFlow(false))
        val youtubeAuthRepo = mock(YouTubeAuthRepository::class.java)
        `when`(youtubeAuthRepo.getAuthOnce()).thenReturn(YouTubeAuthBundle())
        `when`(youtubeAuthRepo.authFlow).thenReturn(MutableStateFlow(YouTubeAuthBundle()))
        replaceLazy("neteaseCookieRepo", cookieRepo)
        replaceLazy("neteaseClient", mock(NeteaseClient::class.java))
        replaceLazy("settingsRepo", settingsRepo)
        replaceLazy("youtubeAuthRepo", youtubeAuthRepo)
        replaceLazy("biliClient", biliClient)
        preserveBilibiliCache()
        runBlocking {
            `when`(biliClient.getCopyrightMusicRankPeriods(anyInt())).thenReturn(listOf(period))
            `when`(biliClient.getCopyrightMusicRankSongs(anyLong())).thenReturn(listOf(song))
            `when`(biliClient.getMusicHotRankVideos()).thenReturn(listOf(hotVideo))
        }
    }

    @After
    fun restoreDependencies() {
        restoredDependencies.asReversed().forEach { it() }
        Dispatchers.resetMain()
    }

    @Test
    fun `international startup blocks Bilibili requests even when YouTube is disabled`() = homeTest {
        internationalEnabled.value = true
        val vm = createViewModel()
        vm.loadBiliMusicHome(force = true)
        vm.selectBiliMusicRankType(BiliMusicRankType.ORIGINAL)
        runCurrent()

        verifyNoInteractions(biliClient)
        assertHidden(vm)
        assertTrue(savedBilibiliEnabled.value)
        assertFalse(vm.uiState.value.internationalizationEnabled)
    }

    @Test
    fun `international mode clears both sections and leaving it restores the saved enabled switch`() = homeTest {
        val vm = createViewModel()
        vm.loadBiliMusicHome(force = true)
        runCurrent()
        assertTrue(vm.uiState.value.biliMusicHomeEnabled)
        assertEquals(listOf(song), vm.uiState.value.biliMusicRank.songs)
        assertEquals(listOf(hotVideo), vm.uiState.value.biliMusicHot.items)

        internationalEnabled.value = true
        runCurrent()
        assertHidden(vm)
        vm.setOfflineMode(true)
        vm.loadBiliMusicHome(force = true)
        vm.selectBiliMusicRankType(BiliMusicRankType.ORIGINAL)
        runCurrent()
        assertHidden(vm)

        internationalEnabled.value = false
        runCurrent()
        vm.loadBiliMusicHome()
        assertTrue(vm.uiState.value.biliMusicHomeEnabled)
        assertEquals(listOf(song), vm.uiState.value.biliMusicRank.songs)
        assertEquals(listOf(hotVideo), vm.uiState.value.biliMusicHot.items)
        assertTrue(savedBilibiliEnabled.value)
    }

    @Test
    fun `leaving international mode keeps a saved disabled switch disabled`() = homeTest {
        internationalEnabled.value = true
        savedBilibiliEnabled.value = false
        val vm = createViewModel()
        internationalEnabled.value = false
        runCurrent()
        vm.loadBiliMusicHome(force = true)
        runCurrent()

        verifyNoInteractions(biliClient)
        assertHidden(vm)
        assertFalse(savedBilibiliEnabled.value)
    }

    @Test
    fun `international switch cancels running loads and rejects their late success and failure`() = homeTest {
        lateinit var rankResponse: Continuation<List<BiliMusicRankPeriod>>
        lateinit var hotResponse: Continuation<List<BiliMusicHotVideo>>
        `when`(biliClient.getCopyrightMusicRankPeriods(anyInt())).thenAnswer { invocation ->
            rankResponse = continuation(invocation.rawArguments.last())
            COROUTINE_SUSPENDED
        }
        `when`(biliClient.getMusicHotRankVideos()).thenAnswer { invocation ->
            hotResponse = continuation(invocation.rawArguments.last())
            COROUTINE_SUSPENDED
        }
        val vm = createViewModel()
        vm.loadBiliMusicHome(force = true)
        runCurrent()
        val rankJob = loadJob(vm, "biliMusicRankJob")
        val hotJob = loadJob(vm, "biliMusicHotJob")
        assertTrue(vm.uiState.value.biliMusicRank.loading)
        assertTrue(vm.uiState.value.biliMusicHot.loading)

        internationalEnabled.value = true
        runCurrent()
        assertTrue(rankJob.isCancelled)
        assertTrue(hotJob.isCancelled)
        assertHidden(vm)

        rankResponse.resume(listOf(period))
        hotResponse.resumeWithException(IOException("late fixture failure"))
        runCurrent()
        assertHidden(vm)
    }

    private var viewModel: HomeViewModel? = null

    private fun homeTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            viewModel?.viewModelScope?.cancel()
            runCurrent()
        }
    }

    private fun TestScope.createViewModel(): HomeViewModel = HomeViewModel(
        ApplicationProvider.getApplicationContext()
    ).also {
        viewModel = it
        runCurrent()
    }

    private fun assertHidden(vm: HomeViewModel) {
        assertFalse(vm.uiState.value.biliMusicHomeEnabled)
        assertTrue(vm.uiState.value.biliMusicRank.songs.isEmpty())
        assertTrue(vm.uiState.value.biliMusicHot.items.isEmpty())
        assertFalse(vm.uiState.value.biliMusicRank.loading)
        assertFalse(vm.uiState.value.biliMusicHot.loading)
        assertEquals(null, vm.uiState.value.biliMusicRank.error)
        assertEquals(null, vm.uiState.value.biliMusicHot.error)
    }

    private fun loadJob(vm: HomeViewModel, name: String): Job = HomeViewModel::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(vm) as Job

    private fun replaceLazy(name: String, replacement: Any) {
        val delegate = AppContainer::class.java.getDeclaredField("$name\$delegate")
            .apply { isAccessible = true }.get(null)
        val field = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val previous = field.get(delegate)
        restoredDependencies += { field.set(delegate, previous) }
        field.set(delegate, replacement)
    }

    private fun preserveBilibiliCache() {
        val rankField = BiliMusicHomeRepository::class.java.getDeclaredField("rankCache")
            .apply { isAccessible = true }
        val rankCache = rankField.get(BiliMusicHomeRepository) as MutableMap<*, *>
        val previousRanks = rankCache.toMap()
        val hotField = BiliMusicHomeRepository::class.java.getDeclaredField("hotCache")
            .apply { isAccessible = true }
        val previousHot = hotField.get(BiliMusicHomeRepository)
        restoredDependencies += {
            rankCache.clear()
            rankCache.javaClass.getMethod("putAll", Map::class.java).invoke(rankCache, previousRanks)
            hotField.set(BiliMusicHomeRepository, previousHot)
        }
        rankCache.clear()
        hotField.set(BiliMusicHomeRepository, null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> continuation(value: Any?): Continuation<T> = value as Continuation<T>
}
