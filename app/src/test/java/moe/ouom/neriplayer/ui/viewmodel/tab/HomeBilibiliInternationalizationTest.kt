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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.data.model.settings.home.HomeMajorSectionIds
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeLayout
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRecommendedSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankPeriod
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankSong
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankType
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeCursor
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomePage
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelfContinuation
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicRequestLocale
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.platform.youtube.api.client.YouTubeMusicClient
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentSettings
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
    private val youtubeEnabled = MutableStateFlow(false)
    private val youtubeAuth = MutableStateFlow(YouTubeAuthBundle())
    private val internationalLayout = MutableStateFlow(InternationalHomeLayout())
    private val youtubeClient = mock(YouTubeMusicClient::class.java)
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
        `when`(settingsRepo.internationalHomeLayoutFlow).thenReturn(internationalLayout)
        `when`(settingsRepo.internationalizationEnabledFlow).thenReturn(internationalEnabled)
        `when`(settingsRepo.youtubeEnabledFlow).thenReturn(youtubeEnabled)
        // 首页模块顺序/隐藏集合：默认按 HomeLayoutState 的默认值给出
        `when`(settingsRepo.homeMajorOrderFlow).thenReturn(
            MutableStateFlow(HomeMajorSectionIds.DEFAULT_ORDER)
        )
        `when`(settingsRepo.homeTrendingOrderFlow).thenReturn(
            MutableStateFlow(HomeTrendingSubSectionIds.DEFAULT_ORDER)
        )
        `when`(settingsRepo.homeTrendingHiddenFlow).thenReturn(MutableStateFlow(emptySet<String>()))
        `when`(settingsRepo.homeRadarOrderFlow).thenReturn(
            MutableStateFlow(HomeRadarSubSectionIds.DEFAULT_ORDER)
        )
        `when`(settingsRepo.homeRadarHiddenFlow).thenReturn(MutableStateFlow(emptySet<String>()))
        `when`(settingsRepo.homeRecommendedOrderFlow).thenReturn(
            MutableStateFlow(HomeRecommendedSubSectionIds.DEFAULT_ORDER)
        )
        `when`(settingsRepo.homeRecommendedHiddenFlow).thenReturn(MutableStateFlow(emptySet<String>()))
        val youtubeAuthRepo = mock(YouTubeAuthRepository::class.java)
        `when`(youtubeAuthRepo.getAuthOnce()).thenAnswer { youtubeAuth.value }
        `when`(youtubeAuthRepo.authFlow).thenReturn(youtubeAuth)
        replaceLazy("neteaseCookieRepo", cookieRepo)
        replaceLazy("neteaseClient", mock(NeteaseClient::class.java))
        replaceLazy("settingsRepo", settingsRepo)
        replaceLazy("youtubeAuthRepo", youtubeAuthRepo)
        replaceLazy("youtubeMusicClient", youtubeClient, delegateField = "youtubeMusicClientDelegate")
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
    fun `the startup timer waits for the actual home mode before any provider requests`() = homeTest {
        `when`(AppContainer.settingsRepo.internationalizationEnabledFlow).thenReturn(MutableSharedFlow())
        val vm = createViewModel()
        assertFalse(vm.uiState.value.homeSourceReady)
        Mockito.clearInvocations(AppContainer.neteaseClient)
        advanceTimeBy(1_000)
        runCurrent()
        verifyNoInteractions(AppContainer.neteaseClient, biliClient)
    }

    @Test
    fun `international startup blocks Bilibili requests even when YouTube is disabled`() = homeTest {
        internationalEnabled.value = true
        val vm = createViewModel()
        Mockito.clearInvocations(AppContainer.neteaseClient)
        advanceTimeBy(1_000)
        vm.refreshNeteaseHome()
        vm.loadBiliMusicHome(force = true)
        vm.selectBiliMusicRankType(BiliMusicRankType.ORIGINAL)
        runCurrent()

        verifyNoInteractions(biliClient, AppContainer.neteaseClient)
        assertHidden(vm)
        assertTrue(savedBilibiliEnabled.value)
        assertTrue(vm.uiState.value.homeSourceReady)
        assertTrue(vm.uiState.value.internationalizationEnabled)
        assertFalse(vm.uiState.value.youtubeHomeEnabled)
    }

    @Test
    fun `guest startup coalesces refreshes and a replacement feed rejects the late continuation`() = homeTest {
        internationalEnabled.value = true
        youtubeEnabled.value = true
        val cursor = YouTubeMusicHomeCursor("page-two", YouTubeMusicRequestLocale("en", "US"), setOf("page-two"))
        lateinit var pending: Continuation<YouTubeMusicHomePage>
        `when`(youtubeClient.getHomeFeedPage(null)).thenAnswer { invocation ->
            pending = continuation(invocation.rawArguments.last())
            COROUTINE_SUSPENDED
        }
        `when`(youtubeClient.getHomeFeedPage(cursor)).thenAnswer { invocation ->
            pending = continuation(invocation.rawArguments.last())
            COROUTINE_SUSPENDED
        }
        val vm = createViewModel()
        Mockito.clearInvocations(AppContainer.neteaseClient)
        vm.refreshYtMusicHome()
        advanceTimeBy(1_000)
        runCurrent()
        Mockito.verify(youtubeClient).getHomeFeedPage(null)
        verifyNoInteractions(AppContainer.neteaseClient, biliClient)
        pending.resume(youTubePage("first").copy(next = cursor))
        runCurrent()
        Mockito.verify(youtubeClient).getHomeFeedPage(cursor)
        assertEquals("first", vm.uiState.value.ytMusicHomeShelves.items.single().title)
        assertFalse(vm.uiState.value.ytMusicHomeShelves.loading)
        assertTrue(vm.uiState.value.loadingMoreYouTubeHome)
        val stale = pending
        vm.refreshYtMusicHome()
        runCurrent()
        pending.resume(youTubePage("fresh"))
        runCurrent()
        stale.resume(youTubePage("stale"))
        runCurrent()
        assertEquals("fresh", vm.uiState.value.ytMusicHomeShelves.items.single().title)
        assertFalse(vm.uiState.value.loadingMoreYouTubeHome)
    }

    @Test
    fun `automatic paging advances past empty and duplicate pages and stops at a repeated token`() = homeTest {
        internationalEnabled.value = true
        youtubeEnabled.value = true
        val locale = YouTubeMusicRequestLocale("en", "US")
        val second = YouTubeMusicHomeCursor("two", locale, setOf("two"))
        val third = YouTubeMusicHomeCursor("three", locale, second.visitedTokens + "three")
        val fourth = YouTubeMusicHomeCursor("four", locale, third.visitedTokens + "four")
        `when`(youtubeClient.getHomeFeedPage(null)).thenReturn(YouTubeMusicHomePage(emptyList(), second))
        `when`(youtubeClient.getHomeFeedPage(second)).thenReturn(youTubePage("first visible").copy(next = third))
        `when`(youtubeClient.getHomeFeedPage(third)).thenReturn(youTubePage("first visible").copy(next = fourth))
        `when`(youtubeClient.getHomeFeedPage(fourth)).thenReturn(youTubePage("last visible").copy(next = second))

        val vm = createViewModel()
        val state = vm.uiState.value
        assertEquals(listOf("first visible", "last visible"), state.ytMusicHomeShelves.items.map { it.title })
        assertFalse(state.ytMusicHomeShelves.loading)
        assertFalse(state.loadingMoreYouTubeHome)
        assertEquals(null, state.youTubeHomeLoadMoreError)
        val order = Mockito.inOrder(youtubeClient)
        order.verify(youtubeClient).getHomeFeedPage(null)
        order.verify(youtubeClient).getHomeFeedPage(second)
        order.verify(youtubeClient).getHomeFeedPage(third)
        order.verify(youtubeClient).getHomeFeedPage(fourth)
        order.verifyNoMoreInteractions()
    }

    @Test
    fun `shelf continuations fill placeholders in place and keep equally titled shelves independent`() = homeTest {
        internationalEnabled.value = true
        youtubeEnabled.value = true
        val song = YouTubeMusicHomeItem("Song", "Artist", "", videoId = "song")
        val extra = song.copy(title = "Another", videoId = "another")
        val left = YouTubeMusicHomeShelf("For you", emptyList(), continuationKey = "left")
        val right = YouTubeMusicHomeShelf("For you", listOf(song), continuationKey = "right")
        val first = YouTubeMusicHomeCursor("more", YouTubeMusicRequestLocale("en", "US"), setOf("more"))
        val next = first.copy(activeShelf = YouTubeMusicHomeShelfContinuation("left", left.title, first.token))
        val late = YouTubeMusicHomeShelf("Late", emptyList(), continuationKey = "late")
        `when`(youtubeClient.getHomeFeedPage(null)).thenReturn(YouTubeMusicHomePage(listOf(left, right), first))
        `when`(youtubeClient.getHomeFeedPage(first)).thenReturn(YouTubeMusicHomePage(
            listOf(right.copy(items = listOf(song, extra)), late), next
        ))
        `when`(youtubeClient.getHomeFeedPage(next)).thenReturn(YouTubeMusicHomePage(
            listOf(late.copy(items = listOf(extra)), left.copy(items = listOf(song)))
        ))

        val vm = createViewModel()
        assertEquals(
            listOf(left.copy(items = listOf(song)), right.copy(items = listOf(song, extra)), late.copy(items = listOf(extra))),
            vm.uiState.value.ytMusicHomeShelves.items
        )
    }

    @Test
    fun `offline guest resumes once and changing account clears its shelves and paging cursor`() = homeTest {
        internationalEnabled.value = true
        youtubeEnabled.value = true
        `when`(youtubeClient.getHomeFeedPage(null)).thenReturn(youTubePage("guest"), youTubePage("signed in"))
        val vm = createViewModel(initialOfflineMode = true)
        advanceTimeBy(1_000)
        runCurrent()
        verifyNoInteractions(youtubeClient)
        vm.setOfflineMode(false)
        runCurrent()
        assertEquals("guest", vm.uiState.value.ytMusicHomeShelves.items.single().title)
        youtubeAuth.value = YouTubeAuthBundle(xGoogAuthUser = "1")
        runCurrent()
        assertEquals("signed in", vm.uiState.value.ytMusicHomeShelves.items.single().title)
        youtubeEnabled.value = false
        runCurrent()
        assertTrue(vm.uiState.value.ytMusicHomeShelves.items.isEmpty())
        assertTrue(vm.uiState.value.internationalizationEnabled)
        assertFalse(vm.uiState.value.youtubeHomeEnabled)
        Mockito.verify(youtubeClient, Mockito.times(2)).getHomeFeedPage(null)
    }

    private fun youTubePage(title: String) = YouTubeMusicHomePage(listOf(
        YouTubeMusicHomeShelf(title, listOf(YouTubeMusicHomeItem(title, "artist", "", videoId = title)))
    ))

    @Test
    fun `local only international home skips providers and reordering visible sections reuses the feed`() = homeTest {
        internationalEnabled.value = true
        youtubeEnabled.value = true
        internationalLayout.value = InternationalHomeLayout(
            hidden = setOf(InternationalHomeSectionIds.SPOTLIGHT, InternationalHomeSectionIds.FEED)
        )
        `when`(youtubeClient.getHomeFeedPage(null)).thenReturn(youTubePage("regional feed"))
        val vm = createViewModel()
        Mockito.clearInvocations(AppContainer.neteaseClient)
        vm.refreshYtMusicHome()
        advanceTimeBy(1_000)
        runCurrent()
        verifyNoInteractions(youtubeClient, AppContainer.neteaseClient, biliClient)
        internationalLayout.value = InternationalHomeLayout()
        runCurrent()
        assertEquals("regional feed", vm.uiState.value.ytMusicHomeShelves.items.single().title)
        internationalLayout.value = InternationalHomeLayout(order = InternationalHomeSectionIds.DEFAULT_ORDER.reversed())
        runCurrent()
        assertEquals(internationalLayout.value, vm.uiState.value.internationalHomeLayout)
        Mockito.verify(youtubeClient).getHomeFeedPage(null)
    }

    @Test
    fun `a failed continuation retries the same cursor and a catalog change replaces both pages`() = homeTest {
        YouTubeContentSettings.initialize(ApplicationProvider.getApplicationContext())
        val preferences = YouTubeContentSettings.preferences.value
        restoredDependencies += { YouTubeContentSettings.publish(preferences) }
        internationalEnabled.value = true
        youtubeEnabled.value = true
        val cursor = YouTubeMusicHomeCursor("more", YouTubeMusicRequestLocale("en", "US"), setOf("more"))
        `when`(youtubeClient.getHomeFeedPage(null))
            .thenReturn(youTubePage("first").copy(next = cursor), youTubePage("new catalog"))
        val last = cursor.copy(token = "last", visitedTokens = cursor.visitedTokens + "last")
        var attempts = 0
        `when`(youtubeClient.getHomeFeedPage(cursor)).thenAnswer {
            if (++attempts == 1) throw IOException("network unavailable")
            youTubePage("second").copy(next = last)
        }
        `when`(youtubeClient.getHomeFeedPage(last)).thenReturn(youTubePage("third"))
        val vm = createViewModel()
        assertTrue(vm.uiState.value.youTubeHomeLoadMoreError != null)
        assertEquals("first", vm.uiState.value.ytMusicHomeShelves.items.single().title)
        vm.retryYouTubeHomeContinuation()
        vm.retryYouTubeHomeContinuation()
        runCurrent()
        assertEquals(listOf("first", "second", "third"), vm.uiState.value.ytMusicHomeShelves.items.map { it.title })
        assertEquals(null, vm.uiState.value.youTubeHomeLoadMoreError)
        assertFalse(vm.uiState.value.loadingMoreYouTubeHome)
        Mockito.verify(youtubeClient, Mockito.times(2)).getHomeFeedPage(cursor)
        Mockito.verify(youtubeClient).getHomeFeedPage(last)
        YouTubeContentSettings.publish(preferences.copy(hideExplicit = !preferences.hideExplicit))
        runCurrent()
        assertEquals("new catalog", vm.uiState.value.ytMusicHomeShelves.items.single().title)
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

    private fun TestScope.createViewModel(initialOfflineMode: Boolean = false): HomeViewModel = HomeViewModel(
        ApplicationProvider.getApplicationContext(), initialOfflineMode
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

    private fun replaceLazy(name: String, replacement: Any, delegateField: String = "$name\$delegate") {
        val delegate = AppContainer::class.java.getDeclaredField(delegateField)
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
