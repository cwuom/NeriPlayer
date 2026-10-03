package moe.ouom.neriplayer.core.player.metadata

import android.app.Application
import android.util.LruCache
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer

class PreferredLyricRomanizedFallbackTest {
    @Test
    fun `all external preferred sources retain their tracks while netease fills missing romanization`() = runTest {
        for ((index, preference) in listOf(LyricSourcePreference.Kugou, LyricSourcePreference.QqMusic,
            LyricSourcePreference.LrcLib, LyricSourcePreference.AmllTtml).withIndex()) {
            val id = 30_000L + index
            val fixture = Fixture(mapOf(id to "fallback romaji"), preference = preference)
            val song = remoteSong(id).copy(album = SongSourceTags.NETEASE)

            val result = fixture.preferred(song)

            assertEquals(preference, result.source)
            assertEquals(listOf("Kugou original"), result.lyrics.map { it.text })
            assertEquals(listOf("Kugou translation"), result.translatedLyrics.map { it.text })
            assertEquals(listOf("fallback romaji"), result.romanizedLyrics.map { it.text })
            assertEquals(listOf(preference.matchSource), fixture.searchedSources)
        }
    }

    @Test
    fun `netease text preference takes romanization from the selected id without searching twice`() = runTest {
        val fixture = Fixture(mapOf(30_100L to "selected romaji"), listOf(cloudMatch("30100")),
            preference = LyricSourcePreference.CloudMusic)

        val result = fixture.preferred(remoteSong(30_101))

        assertEquals(LyricSourcePreference.CloudMusic, result.source)
        assertEquals(listOf("NetEase original"), result.lyrics.map { it.text })
        assertEquals(listOf("selected romaji"), result.romanizedLyrics.map { it.text })
        assertEquals(listOf(30_100L), fixture.cacheReads)
        assertEquals(listOf(EditableLyricMatchSource.CLOUD_MUSIC), fixture.searchedSources)
    }

    @Test
    fun `netease direct preference retains available romanization without text matching`() = runTest {
        val fixture = Fixture(mapOf(30_110L to "native romaji"), preference = LyricSourcePreference.CloudMusic)
        val song = remoteSong(30_110).copy(album = SongSourceTags.NETEASE,
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC)

        val result = fixture.preferred(song)

        assertEquals(listOf("native romaji"), result.romanizedLyrics.map { it.text })
        assertTrue(fixture.searchedSources.isEmpty())
    }

    @Test
    fun `amll embedded romanization takes precedence without looking up netease`() = runTest {
        val fixture = Fixture(preference = LyricSourcePreference.AmllTtml, preferredRawLyric = ttmlWithRomanization)

        val result = fixture.preferred(remoteSong(30_120))

        assertEquals(listOf("Halo Waludo"), result.romanizedLyrics.map { it.text })
        assertEquals(1_000L, result.romanizedLyrics.single().startTimeMs)
        assertEquals(listOf(EditableLyricMatchSource.AMLL_TTML), fixture.searchedSources)
        assertTrue(fixture.cacheReads.isEmpty())
    }

    @Test
    fun `automatic sources fill missing romanization for bili qq and youtube songs`() = runTest {
        val songs = listOf(
            remoteSong(30_130).copy(album = SongSourceTags.BILIBILI),
            remoteSong(30_131).copy(matchedLyricSource = MusicPlatform.QQ_MUSIC, matchedSongId = "999"),
            remoteSong(30_132).copy(album = "YouTube Music", mediaUri = "ytmusic://video/abcdefghijk")
        )
        for (song in songs) {
            val fixture = Fixture(mapOf(30_133L to "automatic romaji"), listOf(cloudMatch("30133")),
                preference = LyricSourcePreference.Automatic)

            val result = fixture.romanizedWithDownloads(song)

            assertEquals(listOf("automatic romaji"), result.map { it.text })
            assertEquals(listOf(30_133L), fixture.cacheReads)
            assertEquals(listOf(EditableLyricMatchSource.CLOUD_MUSIC), fixture.searchedSources)
        }
    }

    @Test
    fun `automatic source preserves stored romanization and explicit clear without remote lookup`() = runTest {
        for (text in listOf("[00:01.00]stored romaji", "")) {
            val fixture = Fixture(preference = LyricSourcePreference.Automatic)

            val result = fixture.romanizedWithDownloads(remoteSong(30_140).copy(matchedRomanizedLyric = text))

            assertEquals(if (text.isEmpty()) emptyList<String>() else listOf("stored romaji"), result.map { it.text })
            assertTrue(fixture.searchedSources.isEmpty())
            assertTrue(fixture.cacheReads.isEmpty())
        }
    }

    @Test
    fun `kugou keeps original and translation while matched netease id supplies romanization`() = runTest {
        val song = remoteSong(10_001).copy(
            album = SongSourceTags.NETEASE,
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "20001"
        )
        val fixture = Fixture(mapOf(20_001L to "matched romaji", 10_001L to "native romaji"))

        val result = fixture.preferred(song)

        assertKugouTracks(result)
        assertEquals(listOf("matched romaji"), result.romanizedLyrics.map { it.text })
        assertEquals(listOf(20_001L), fixture.cacheReads)
        assertEquals(listOf(EditableLyricMatchSource.KUGOU), fixture.searchedSources)
    }

    @Test
    fun `native netease song uses its own id when matched id is absent or invalid`() = runTest {
        for ((index, matchedId) in listOf(null, "0", "-2", "invalid").withIndex()) {
            val id = 10_010L + index
            val song = remoteSong(id).copy(
                album = SongSourceTags.NETEASE,
                matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
                matchedSongId = matchedId
            )
            val fixture = Fixture(mapOf(id to "native romaji"))

            val result = fixture.preferred(song)

            assertKugouTracks(result)
            assertEquals(listOf("native romaji"), result.romanizedLyrics.map { it.text })
            assertEquals(listOf(id), fixture.cacheReads)
            assertEquals(listOf(EditableLyricMatchSource.KUGOU), fixture.searchedSources)
        }
    }

    @Test
    fun `cross platform song searches netease and reads the selected candidate id`() = runTest {
        val song = remoteSong(10_020).copy(album = SongSourceTags.BILIBILI)
        val fixture = Fixture(mapOf(20_020L to "searched romaji"), listOf(cloudMatch("20020")))

        val result = fixture.preferred(song)

        assertKugouTracks(result)
        assertEquals(listOf("searched romaji"), result.romanizedLyrics.map { it.text })
        assertEquals(listOf(20_020L), fixture.cacheReads)
        assertEquals(listOf(EditableLyricMatchSource.KUGOU, EditableLyricMatchSource.CLOUD_MUSIC),
            fixture.searchedSources)
    }

    @Test
    fun `numeric qq matched id cannot become a netease lyric id`() = runTest {
        val song = remoteSong(10_030).copy(
            matchedLyricSource = MusicPlatform.QQ_MUSIC,
            matchedSongId = "90030"
        )
        val fixture = Fixture(mapOf(20_030L to "searched romaji"), listOf(cloudMatch("20030")))

        val result = fixture.preferred(song)

        assertKugouTracks(result)
        assertEquals(listOf("searched romaji"), result.romanizedLyrics.map { it.text })
        assertEquals(listOf(20_030L), fixture.cacheReads)
    }

    @Test
    fun `cross platform cloud association without matched id cannot use the media id`() = runTest {
        val song = remoteSong(10_040).copy(
            album = SongSourceTags.BILIBILI,
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC
        )
        val fixture = Fixture(mapOf(20_040L to "searched romaji"), listOf(cloudMatch("20040")))

        val result = fixture.preferred(song)

        assertEquals(listOf("searched romaji"), result.romanizedLyrics.map { it.text })
        assertEquals(listOf(20_040L), fixture.cacheReads)
    }

    @Test
    fun `netease fallback skips unreliable candidates before a compatible song`() = runTest {
        val candidates = listOf(
            cloudMatch("21001", confidence = EditableLyricMatchConfidence.LOW),
            cloudMatch("21002", title = "Different Song"),
            cloudMatch("21003", artist = "Different Artist"),
            cloudMatch("21004", durationMs = 0),
            cloudMatch("21005", durationMs = 300_000),
            cloudMatch("-1"),
            cloudMatch("invalid"),
            cloudMatch("20050", durationMs = 241_000)
        )
        val fixture = Fixture(mapOf(20_050L to "compatible romaji"), candidates)

        val result = fixture.preferred(remoteSong(10_050))

        assertKugouTracks(result)
        assertEquals(listOf("compatible romaji"), result.romanizedLyrics.map { it.text })
        assertEquals(listOf(20_050L), fixture.cacheReads)
    }

    @Test
    fun `netease fallback continues when the first reliable candidate has no romanization`() = runTest {
        val fixture = Fixture(
            mapOf(20_060L to "", 20_061L to "available romaji"),
            listOf(cloudMatch("20060"), cloudMatch("20061"))
        )

        val result = fixture.preferred(remoteSong(10_060))

        assertKugouTracks(result)
        assertEquals(listOf("available romaji"), result.romanizedLyrics.map { it.text })
        assertEquals(listOf(20_060L, 20_061L), fixture.cacheReads)
    }

    @Test
    fun `unmatched netease search preserves kugou tracks without romanization`() = runTest {
        val fixture = Fixture(cloudMatches = listOf(cloudMatch("20070", title = "Different Song")))

        val result = fixture.preferred(remoteSong(10_070))

        assertKugouTracks(result)
        assertTrue(result.romanizedLyrics.isEmpty())
        assertTrue(fixture.cacheReads.isEmpty())
    }

    @Test
    fun `netease search failure preserves kugou tracks`() = runTest {
        val fixture = Fixture(searchFailure = IOException("search unavailable"))

        val result = fixture.preferred(remoteSong(10_080))

        assertKugouTracks(result)
        assertTrue(result.romanizedLyrics.isEmpty())
        assertTrue(fixture.cacheReads.isEmpty())
    }

    @Test
    fun `netease lyric failure preserves kugou tracks`() = runTest {
        val fixture = Fixture(cacheFailure = IOException("lyric unavailable"))
        val song = remoteSong(10_090).copy(
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "20090"
        )

        val result = fixture.preferred(song)

        assertKugouTracks(result)
        assertTrue(result.romanizedLyrics.isEmpty())
    }

    @Test
    fun `public romanization getter returns netease fallback with kugou selected`() = runTest {
        val fixture = Fixture(mapOf(20_100L to "public romaji"))
        val song = remoteSong(10_100).copy(
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "20100"
        )

        assertEquals(listOf("public romaji"), fixture.romanized(song).map { it.text })
    }

    @Test
    fun `confirmed romanization and explicit clear bypass all fallback dependencies`() = runTest {
        for ((index, text) in listOf("[00:01.00]user romaji", "").withIndex()) {
            val fixture = Fixture(searchFailure = AssertionError("confirmed track must not search"))
            val song = remoteSong(10_110L + index).copy(
                lyricSyncEdited = true,
                matchedRomanizedLyric = text
            )

            val result = fixture.romanized(song)

            assertEquals(if (text.isEmpty()) emptyList<String>() else listOf("user romaji"), result.map { it.text })
            assertTrue(fixture.searchedSources.isEmpty())
            assertTrue(fixture.cacheReads.isEmpty())
        }
    }

    @Test
    fun `confirmed romanization does not trigger fallback while building kugou source cache`() = runTest {
        for ((index, text) in listOf("[00:01.00]user romaji", "").withIndex()) {
            val fixture = Fixture(searchFailure = AssertionError("confirmed track must not search netease"))
            val song = remoteSong(10_120L + index).copy(lyricSyncEdited = true, matchedRomanizedLyric = text)

            val result = fixture.preferred(song)

            assertKugouTracks(result)
            assertTrue(result.romanizedLyrics.isEmpty())
            assertEquals(listOf(EditableLyricMatchSource.KUGOU), fixture.searchedSources)
            assertTrue(fixture.cacheReads.isEmpty())
        }
    }

    @Test
    fun `netease search cancellation reaches the caller`() = runTest {
        val cancellation = CancellationException("search superseded")
        val fixture = Fixture(searchFailure = cancellation)

        val failure = runCatching { fixture.preferred(remoteSong(10_130)) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(cancellation.message, failure?.message)
    }

    @Test
    fun `netease lyric cancellation reaches the caller`() = runTest {
        val cancellation = CancellationException("lyric superseded")
        val fixture = Fixture(cacheFailure = cancellation)
        val song = remoteSong(10_140).copy(
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "20140"
        )

        val failure = runCatching { fixture.preferred(song) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(cancellation.message, failure?.message)
    }

    private class Fixture(
        romanizedById: Map<Long, String> = emptyMap(),
        cloudMatches: List<RankedEditableLyricMatch> = emptyList(),
        searchFailure: Throwable? = null,
        cacheFailure: Throwable? = null,
        private val preference: LyricSourcePreference = LyricSourcePreference.Kugou,
        preferredRawLyric: String? = null
    ) {
        val searchedSources = mutableListOf<EditableLyricMatchSource>()
        val cacheReads = mutableListOf<Long>()
        private val payloads = romanizedById.mapValues { (_, text) -> neteasePayload(text) }
        private val entries = payloads.mapValues { (_, payload) -> PlayerLyricsProvider.buildNeteaseLyricsCacheEntry(payload) }
        private val matcher = Mockito.mock(EditableLyricsMatcher::class.java, Answer { invocation ->
            if (invocation.method.name != "matchHighConfidenceLyricsForSource") {
                return@Answer Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            val request = invocation.getArgument<EditableLyricMatchRequest>(0)
            val source = invocation.getArgument<EditableLyricMatchSource>(1)
            searchedSources += source
            assertEquals(setOf(source), request.sources)
            assertEquals("Signal", request.trackName)
            assertEquals("Artist One", request.artistName)
            assertEquals(240_000L, request.durationMs)
            when (source) {
                EditableLyricMatchSource.CLOUD_MUSIC -> {
                    searchFailure?.let { throw it }
                    cloudMatches
                }
                else -> {
                    if (source != preference.matchSource) throw AssertionError("unexpected lyric source: $source")
                    val selected = kugouMatch()
                    listOf(selected.copy(candidate = selected.candidate.copy(source = source,
                        lyrics = preferredRawLyric ?: selected.candidate.lyrics)))
                }
            }
        })
        private val netease = Mockito.mock(NeteaseClient::class.java, Answer { invocation ->
            if (invocation.method.name != "getLyricNew") return@Answer Answers.RETURNS_DEFAULTS.answer(invocation)
            val id = invocation.getArgument<Long>(0)
            payloads[id] ?: throw AssertionError("unexpected netease network lookup: $id")
        })
        private val cache = mock<LruCache<Long, NeteaseLyricsCacheEntry>>(Answer { invocation ->
            if (invocation.method.name != "get") return@Answer Answers.RETURNS_DEFAULTS.answer(invocation)
            val id = invocation.getArgument<Long>(0)
            cacheReads += id
            cacheFailure?.let { throw it }
            entries[id] ?: throw AssertionError("unexpected netease lyric id: $id")
        })

        suspend fun preferred(song: SongItem): PreferredLyricSourceResult = checkNotNull(
            PlayerLyricsProvider.tryGetPreferredLyricSourceResult(song, preference, true,
                matcher, netease, cache)
        )

        suspend fun romanized(song: SongItem) = PlayerLyricsProvider.getRomanizedLyrics(
            song, Application(), netease, cache, matcher, true, preference
        )

        suspend fun romanizedWithDownloads(song: SongItem): List<LyricEntry> {
            val registry = PlayerDependencies::class.java.getDeclaredField("registry")
                .apply { isAccessible = true }.get(null)
            val environment = registry.javaClass.getDeclaredField("environment").apply { isAccessible = true }
            val previous = environment.get(registry)
            environment.set(registry, null)
            return try {
                PlayerDependencies.install(PlayerEnvironment(Application(),
                    Mockito.mock(PlayerRepositoryDependencies::class.java), Mockito.mock(PlayerDownloadAccess::class.java),
                    Mockito.mock(PlayerListenTogetherAccess::class.java), Mockito.mock(PlayerPresentationHost::class.java),
                    { false }, { Job().apply { complete() } }))
                romanized(song)
            } finally {
                environment.set(registry, previous)
            }
        }
    }

    private companion object {
        val ttmlWithRomanization = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div><p begin="00:01.000" end="00:02.000">
                <span begin="00:01.000" end="00:01.500">Hello</span>
                <span begin="00:01.500" end="00:02.000">World</span>
                <span ttm:role="x-roman">Halo Waludo</span>
              </p></div></body>
            </tt>
        """.trimIndent()

        fun remoteSong(id: Long) = SongItem(id, "Signal", "Artist One", "Album", 1, 240_000, null)

        fun assertKugouTracks(result: PreferredLyricSourceResult) {
            assertEquals(LyricSourcePreference.Kugou, result.source)
            assertEquals(listOf("Kugou original"), result.lyrics.map { it.text })
            assertEquals(listOf("Kugou translation"), result.translatedLyrics.map { it.text })
        }

        fun kugouMatch() = RankedEditableLyricMatch(
            EditableLyricMatchCandidate("kugou-song", EditableLyricMatchSource.KUGOU, "Signal", "Artist One",
                durationMs = 240_000, lyrics = "[00:01.00]Kugou original", translatedLyrics = "[00:01.00]Kugou translation"),
            score = 120, durationDeltaMs = 0, confidence = EditableLyricMatchConfidence.HIGH
        )

        fun cloudMatch(
            id: String,
            title: String = "Signal",
            artist: String = "Artist One",
            durationMs: Long = 240_000,
            confidence: EditableLyricMatchConfidence = EditableLyricMatchConfidence.HIGH
        ) = RankedEditableLyricMatch(
            EditableLyricMatchCandidate(id, EditableLyricMatchSource.CLOUD_MUSIC, title, artist,
                durationMs = durationMs, lyrics = "[00:01.00]NetEase original"),
            score = 120, durationDeltaMs = 0, confidence = confidence
        )

        fun neteasePayload(romanized: String): String = """
            {"lrc":{"lyric":"[00:01.00]NetEase original"},
             "tlyric":{"lyric":"[00:01.00]NetEase translation"},
             "romalrc":{"lyric":"${if (romanized.isEmpty()) "" else "[00:01.00]$romanized"}"}}
        """.trimIndent()

        inline fun <reified T : Any> mock(answer: Answer<Any?>): T = Mockito.mock(T::class.java, answer)
    }
}
