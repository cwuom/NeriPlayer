package moe.ouom.neriplayer.core.player.metadata

import android.app.Application
import android.util.LruCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlLyrics
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlSearchResult
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.platform.lyrics.repository.AmllLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher
import moe.ouom.neriplayer.platform.lyrics.repository.LrcLibLyricsRepository
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.youtube.api.client.YouTubeMusicClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer

class AutomaticLyricRomanizedFallbackTest {
    @Before
    @After
    fun clearAmllCache() {
        PlayerLyricsProvider.clearAmllLyricsCache()
    }

    @Test
    fun `automatic online amll retains its romanization before netease fallback`() = runTest {
        val fixture = Fixture(40_001L)
        fixture.withDownloads {
            val original = fixture.original()
            assertEquals(listOf("HelloWorld"), original.map { it.text })
            assertTrue(original.single().words?.isNotEmpty() == true)
            val previousReads = fixture.neteaseCacheReads

            val romanized = fixture.romanized()

            assertEquals(listOf(LyricEntry("Halo Waludo", 1_000L, 240_000L)), romanized)
            assertEquals(previousReads, fixture.neteaseCacheReads)
        }
    }

    @Test
    fun `automatic online amll without romanization falls back to netease`() = runTest {
        val fixture = Fixture(40_002L, embeddedRomanization = null)
        fixture.withDownloads {
            assertEquals(listOf("HelloWorld"), fixture.original().map { it.text })

            assertEquals(listOf("NetEase romaji"), fixture.romanized().map { it.text })
        }
    }

    @Test
    fun `confirmed romanization and clear override cached online amll romanization`() = runTest {
        val fixture = Fixture(40_003L)
        fixture.withDownloads {
            fixture.original()
            val previousReads = fixture.neteaseCacheReads
            for (text in listOf("[00:01.00]user romaji", "")) {
                val edited = fixture.song.copy(lyricSyncEdited = true, matchedRomanizedLyric = text)

                val romanized = fixture.romanized(song = edited)

                assertEquals(if (text.isEmpty()) emptyList<String>() else listOf("user romaji"), romanized.map { it.text })
            }
            assertEquals(previousReads, fixture.neteaseCacheReads)
        }
    }

    @Test
    fun `disabling word timing cannot reuse romanization from an older online amll selection`() = runTest {
        val fixture = Fixture(40_004L)
        fixture.withDownloads {
            assertEquals(listOf("HelloWorld"), fixture.original().map { it.text })
            assertEquals(listOf("Halo Waludo"), fixture.romanized().map { it.text })

            val original = fixture.original(preferWordTimed = false)
            val romanized = fixture.romanized(preferWordTimed = false)

            assertEquals(listOf("NetEase original"), original.map { it.text })
            assertEquals(listOf("NetEase romaji"), romanized.map { it.text })
        }
    }

    @Test
    fun `disabling amll cannot reuse romanization from an older online amll selection`() = runTest {
        val fixture = Fixture(40_005L)
        fixture.withDownloads {
            assertEquals(listOf("HelloWorld"), fixture.original().map { it.text })
            assertEquals(listOf("Halo Waludo"), fixture.romanized().map { it.text })

            val original = fixture.original(amllEnabled = false)
            val romanized = fixture.romanized()

            assertEquals(listOf("NetEase original"), original.map { it.text })
            assertEquals(listOf("NetEase romaji"), romanized.map { it.text })
        }
    }

    private class Fixture(
        id: Long,
        embeddedRomanization: String? = "Halo Waludo"
    ) {
        val song = SongItem(id, "Signal", "Artist One", SongSourceTags.NETEASE, 1, 240_000L, null)
        var neteaseCacheReads = 0
            private set
        private val application = Application()
        private val payload = """{"lrc":{"lyric":"[00:01.00]NetEase original"},
            "romalrc":{"lyric":"[00:01.00]NetEase romaji"}}"""
        private val entry = PlayerLyricsProvider.buildNeteaseLyricsCacheEntry(payload)
        private val netease = mock<NeteaseClient>(Answer { invocation ->
            if (invocation.method.name != "getLyricNew") return@Answer Answers.RETURNS_DEFAULTS.answer(invocation)
            assertEquals(id, invocation.getArgument<Long>(0))
            payload
        })
        private val cache = mock<LruCache<Long, NeteaseLyricsCacheEntry>>(Answer { invocation ->
            if (invocation.method.name != "get") return@Answer Answers.RETURNS_DEFAULTS.answer(invocation)
            assertEquals(id, invocation.getArgument<Long>(0))
            neteaseCacheReads++
            entry
        })
        private val matcher = mock<EditableLyricsMatcher>(Answer { invocation ->
            if (invocation.method.name == "matchHighConfidenceLyricsForSource") {
                throw AssertionError("known NetEase identity must not trigger lyric text search")
            }
            Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        private val amllResult = AmllTtmlSearchResult(
            file = "automatic-$id.ttml", title = "Signal", titles = listOf("Signal"),
            artist = "Artist One", artists = listOf("Artist One"), albums = listOf("Album"),
            ncmIds = listOf(id.toString()), qqIds = emptyList(), score = 120
        )
        private val amll = mock<AmllLyricsRepository>(Answer { invocation ->
            when (invocation.method.name) {
                "searchLyrics" -> {
                    assertEquals("Signal", invocation.getArgument<String>(0))
                    assertEquals("Artist One", invocation.getArgument<String>(1))
                    listOf(amllResult)
                }
                "getLyrics" -> {
                    assertEquals(amllResult, invocation.getArgument<AmllTtmlSearchResult>(0))
                    AmllTtmlLyrics(ttml(embeddedRomanization), amllResult.file, "Signal", listOf("Artist One"), "Album")
                }
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        private val youtube = Mockito.mock(YouTubeMusicClient::class.java)
        private val lrcLib = Mockito.mock(LrcLibLyricsRepository::class.java)
        private val youtubeCache = mock<LruCache<String, YouTubeMusicLyricsCacheEntry>>(Answers.RETURNS_DEFAULTS)

        suspend fun original(amllEnabled: Boolean = true, preferWordTimed: Boolean = true): List<LyricEntry> =
            PlayerLyricsProvider.getLyrics(song, application, netease, cache, youtube, lrcLib, matcher, amll,
                amllEnabled, preferWordTimed, LyricSourcePreference.Automatic, youtubeCache, SongSourceTags.BILIBILI)

        suspend fun romanized(song: SongItem = this.song, preferWordTimed: Boolean = true): List<LyricEntry> =
            PlayerLyricsProvider.getRomanizedLyrics(song, application, netease, cache, matcher, preferWordTimed,
                LyricSourcePreference.Automatic)

        suspend fun withDownloads(block: suspend () -> Unit) {
            val registry = PlayerDependencies::class.java.getDeclaredField("registry")
                .apply { isAccessible = true }.get(null)
            val environment = registry.javaClass.getDeclaredField("environment").apply { isAccessible = true }
            val previous = environment.get(registry)
            environment.set(registry, null)
            try {
                PlayerDependencies.install(PlayerEnvironment(application,
                    Mockito.mock(PlayerRepositoryDependencies::class.java), Mockito.mock(PlayerDownloadAccess::class.java),
                    Mockito.mock(PlayerListenTogetherAccess::class.java), Mockito.mock(PlayerPresentationHost::class.java),
                    { false }, { Job().apply { complete() } }))
                block()
            } finally {
                environment.set(registry, previous)
            }
        }
    }

    private companion object {
        fun ttml(romanization: String?): String {
            val phoneticSpan = romanization?.let { "<span ttm:role=\"x-roman\">$it</span>" }.orEmpty()
            return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                <body><div><p begin="00:01.000" end="04:00.000">
                    <span begin="00:01.000" end="00:01.500">Hello</span>
                    <span begin="00:01.500" end="04:00.000">World</span>
                    $phoneticSpan
                </p></div></body></tt>"""
        }

        inline fun <reified T : Any> mock(answer: Answer<Any?>): T = Mockito.mock(T::class.java, answer)
    }
}
