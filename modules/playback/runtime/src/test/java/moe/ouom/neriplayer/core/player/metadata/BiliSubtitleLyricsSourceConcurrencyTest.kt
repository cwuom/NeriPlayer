package moe.ouom.neriplayer.core.player.metadata

import android.app.Application
import android.util.LruCache
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.lyrics.repository.AmllLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher
import moe.ouom.neriplayer.platform.lyrics.repository.LrcLibLyricsRepository
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.youtube.api.client.YouTubeMusicClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

class BiliSubtitleLyricsSourceConcurrencyTest {
    @Before
    fun clearCachesBeforeTest() {
        PlayerLyricsProvider.clearLyricsCaches()
    }

    @After
    fun clearCachesAfterTest() {
        PlayerLyricsProvider.clearLyricsCaches()
    }

    @Test
    fun `another surfaces pending original lookup cannot hide published cc translations`() = runBlocking {
        val gate = PreferredLookupGate()
        val fixture = Fixture(gate)
        fixture.withDownloads {
            assertEquals(listOf("你好"), fixture.original(LyricSourcePreference.Automatic).map { it.text })
            val pendingOriginal = async { fixture.original(LyricSourcePreference.Kugou) }
            try {
                withTimeout(5_000L) { gate.entered.await() }

                // 外部输出已确定原文来源, 翻译读取不会再次尝试首选来源
                assertEquals(listOf("hello"), fixture.translated().map { it.text })
            } finally {
                gate.release.complete(Unit)
                assertEquals(listOf("你好"), withTimeout(5_000L) { pendingOriginal.await() }.map { it.text })
            }
            assertEquals(listOf("hello"), fixture.translated().map { it.text })
        }
    }

    @Test
    fun `downloaded original replacing cc clears its translation source`() = runBlocking {
        val fixture = Fixture()
        fixture.withDownloads {
            assertEquals(listOf("你好"), fixture.original(LyricSourcePreference.Automatic).map { it.text })
            assertTrue(fixture.isCcOriginal())
            fixture.downloadedOriginal = "[00:01.00]downloaded original"

            assertEquals(
                listOf("downloaded original"),
                fixture.original(LyricSourcePreference.Automatic).map { it.text }
            )

            assertFalse(fixture.isCcOriginal())
            assertTrue(fixture.translated().isEmpty())
        }
    }

    @Test
    fun `cancelled original lookup preserves the published cc translation source`() = runBlocking {
        val gate = PreferredLookupGate()
        val fixture = Fixture(gate)
        fixture.withDownloads {
            assertEquals(listOf("你好"), fixture.original(LyricSourcePreference.Automatic).map { it.text })
            val pendingOriginal = async { fixture.original(LyricSourcePreference.Kugou) }
            try {
                withTimeout(5_000L) { gate.entered.await() }
                withTimeout(5_000L) { pendingOriginal.cancelAndJoin() }

                assertTrue(fixture.isCcOriginal())
                assertEquals(listOf("hello"), fixture.translated().map { it.text })
            } finally {
                gate.release.complete(Unit)
                withTimeout(5_000L) { pendingOriginal.cancelAndJoin() }
            }
        }
    }

    @Test
    fun `failed original lookup preserves the published cc translation source`() = runBlocking {
        val fixture = Fixture()
        fixture.withDownloads {
            assertEquals(listOf("你好"), fixture.original(LyricSourcePreference.Automatic).map { it.text })
            fixture.originalReadFailure = IOException("sidecar read failed")

            val failure = runCatching { fixture.original(LyricSourcePreference.Automatic) }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals("sidecar read failed", failure?.message)
            assertTrue(fixture.isCcOriginal())
            assertEquals(listOf("hello"), fixture.translated().map { it.text })
        }
    }

    @Test
    fun `cache clearing rejects an older cc load after a sidecar source is published`() = runBlocking {
        val gate = SubtitleLookupGate()
        val fixture = Fixture(subtitleGate = gate)
        fixture.withDownloads {
            val oldOriginal = async { fixture.original(LyricSourcePreference.Automatic) }
            try {
                withTimeout(5_000L) { gate.entered.await() }
                PlayerLyricsProvider.clearLyricsCaches()
                fixture.downloadedOriginal = "[00:01.00]downloaded original"
                assertEquals(
                    listOf("downloaded original"),
                    fixture.original(LyricSourcePreference.Automatic).map { it.text }
                )
                assertFalse(fixture.isCcOriginal())

                gate.release.complete(Unit)
                assertEquals(listOf("你好"), withTimeout(5_000L) { oldOriginal.await() }.map { it.text })
                val sourceAfterOldLoad = fixture.isCcOriginal()
                fixture.subtitleOriginal = "fresh CC original"
                val currentSubtitles = fixture.subtitles()

                assertEquals(
                    false to listOf("fresh CC original"),
                    sourceAfterOldLoad to currentSubtitles.map { it.text }
                )
            } finally {
                gate.release.complete(Unit)
                withTimeout(5_000L) { oldOriginal.cancelAndJoin() }
            }
        }
    }

    private class SubtitleLookupGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        fun answer(invocation: InvocationOnMock, payload: JSONObject): Any? {
            // Mockito 隐藏 suspend 参数, 该方法的 continuation 固定返回字幕 JSON
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<JSONObject>
            val lookup: suspend () -> JSONObject = {
                entered.complete(Unit)
                release.await()
                payload
            }
            return lookup.startCoroutineUninterceptedOrReturn(continuation)
        }
    }

    private class PreferredLookupGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val matcher: EditableLyricsMatcher = Mockito.mock(EditableLyricsMatcher::class.java, Answer { invocation ->
            if (invocation.method.name != "matchHighConfidenceLyricsForSource") {
                return@Answer Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            // Mockito 隐藏 suspend 参数, 该方法的 continuation 固定返回匹配列表
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<List<RankedEditableLyricMatch>>
            val lookup: suspend () -> List<RankedEditableLyricMatch> = {
                entered.complete(Unit)
                release.await()
                emptyList()
            }
            lookup.startCoroutineUninterceptedOrReturn(continuation)
        })
    }

    private class Fixture(
        private val gate: PreferredLookupGate = PreferredLookupGate(),
        private val subtitleGate: SubtitleLookupGate? = null
    ) {
        var downloadedOriginal: String? = null
        var originalReadFailure: IOException? = null
        var subtitleOriginal = "你好"
        private val subtitleGateUsed = AtomicBoolean()
        private val application = Mockito.mock(Application::class.java)
        private val downloads = Mockito.mock(PlayerDownloadAccess::class.java, Answer { invocation ->
            if (invocation.method.name != "getLyricContent") {
                return@Answer Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            originalReadFailure?.let { throw it }
            downloadedOriginal
        })
        private val netease = Mockito.mock(NeteaseClient::class.java)
        private val neteaseCache = mock<LruCache<Long, NeteaseLyricsCacheEntry>>()
        private val youtube = Mockito.mock(YouTubeMusicClient::class.java)
        private val youtubeCache = mock<LruCache<String, YouTubeMusicLyricsCacheEntry>>()
        private val lrcLib = Mockito.mock(LrcLibLyricsRepository::class.java)
        private val amll = Mockito.mock(AmllLyricsRepository::class.java)
        private val song = SongItem(
            id = 170001L,
            name = "Signal",
            artist = "UP",
            album = "Bilibili|101|BV17x411w7KC",
            albumId = 0L,
            durationMs = 240_000L,
            coverUrl = null,
            channelId = "bilibili",
            audioId = "170001",
            subAudioId = "101"
        )
        private val biliClient = Mockito.mock(BiliClient::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "hasLoginSession" -> true
                "getPlayerSubtitlePayload" -> JSONObject(
                    """{"subtitles":[
                        {"id":1,"lan":"zh-CN","lan_doc":"中文","type":0,"subtitle_url":"https://i0.hdslb.com/zh.json"},
                        {"id":2,"lan":"en-US","lan_doc":"English","type":0,"subtitle_url":"https://i0.hdslb.com/en.json"}
                    ]}"""
                )
                "getSubtitlePayload" -> {
                    val isPrimary = invocation.getArgument<String>(0).endsWith("/zh.json")
                    val text = if (isPrimary) subtitleOriginal else "hello"
                    val payload = JSONObject("""{"body":[{"from":1.0,"to":4.0,"content":"$text"}]}""")
                    if (isPrimary && subtitleGate != null && subtitleGateUsed.compareAndSet(false, true)) {
                        subtitleGate.answer(invocation, payload)
                    } else payload
                }
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })

        suspend fun original(preference: LyricSourcePreference): List<LyricEntry> = PlayerLyricsProvider.getLyrics(
            song, application, netease, neteaseCache, youtube, lrcLib, gate.matcher, amll,
            amllLyricsEnabled = false,
            preferWordTimedLyrics = false,
            defaultLyricSource = preference,
            ytMusicLyricsCache = youtubeCache,
            biliSourceTag = SongSourceTags.BILIBILI,
            biliClient = biliClient,
            biliSubtitleOptions = BiliSubtitleLyricsOptions()
        )

        suspend fun translated(): List<LyricEntry> = PlayerLyricsProvider.getTranslatedLyrics(
            song, application, netease, neteaseCache, gate.matcher,
            preferWordTimedLyrics = false,
            defaultLyricSource = LyricSourcePreference.Automatic,
            ytMusicLyricsCache = youtubeCache,
            biliSourceTag = SongSourceTags.BILIBILI,
            biliClient = biliClient,
            biliSubtitleOptions = BiliSubtitleLyricsOptions()
        )

        fun isCcOriginal(): Boolean = PlayerLyricsProvider.isBiliCcOriginal(song)

        suspend fun subtitles(): List<LyricEntry> = PlayerLyricsProvider.loadBiliSubtitleLyrics(
            song = song,
            biliClient = biliClient
        ).lyrics

        suspend fun withDownloads(block: suspend () -> Unit) {
            val registry = PlayerDependencies::class.java.getDeclaredField("registry")
                .apply { isAccessible = true }.get(null)
            val environment = registry.javaClass.getDeclaredField("environment").apply { isAccessible = true }
            val previous = environment.get(registry)
            environment.set(registry, null)
            try {
                PlayerDependencies.install(
                    PlayerEnvironment(
                        application,
                        Mockito.mock(PlayerRepositoryDependencies::class.java),
                        downloads,
                        Mockito.mock(PlayerListenTogetherAccess::class.java),
                        Mockito.mock(PlayerPresentationHost::class.java),
                        { false },
                        { Job().apply { complete() } }
                    )
                )
                block()
            } finally {
                environment.set(registry, previous)
            }
        }

        private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)
    }
}
