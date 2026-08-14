package moe.ouom.neriplayer.core.player.metadata

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchCandidate
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchConfidence
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchRequest
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchSource
import moe.ouom.neriplayer.core.api.lyrics.RankedEditableLyricMatch
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.util.network.isTransientHttp2StreamReset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlayerLyricsProviderTest {
    private class TestLyricsCache : PlayerLyricsProvider.NeteaseLyricsCacheStore {
        private val values = mutableMapOf<Long, NeteaseLyricsCacheEntry>()

        override fun get(songId: Long): NeteaseLyricsCacheEntry? = values[songId]

        override fun put(songId: Long, entry: NeteaseLyricsCacheEntry) {
            values[songId] = entry
        }

        fun clear() {
            values.clear()
        }
    }

    @Test
    fun `resolveLocalLyricOverrideState keeps blank local override as cleared`() {
        assertEquals(LocalLyricOverrideState.ABSENT, resolveLocalLyricOverrideState(null))
        assertEquals(LocalLyricOverrideState.CLEARED, resolveLocalLyricOverrideState(""))
        assertEquals(LocalLyricOverrideState.CLEARED, resolveLocalLyricOverrideState("   "))
        assertEquals(LocalLyricOverrideState.PRESENT, resolveLocalLyricOverrideState("[00:00.00]歌词"))
    }

    @Test
    fun `resolveLocalFirstLyricText keeps local blank override ahead of stored and downloaded text`() {
        assertEquals(
            "[00:01.00]local",
            resolveLocalFirstLyricText(
                localLyric = "[00:01.00]local",
                storedLyric = "[00:01.00]stored",
                downloadedLyric = "[00:01.00]downloaded"
            )
        )
        assertEquals(
            "",
            resolveLocalFirstLyricText(
                localLyric = "",
                storedLyric = "[00:01.00]stored",
                downloadedLyric = "[00:01.00]downloaded"
            )
        )
        assertEquals(
            "[00:01.00]downloaded",
            resolveLocalFirstLyricText(
                localLyric = null,
                storedLyric = null,
                downloadedLyric = "[00:01.00]downloaded"
            )
        )
        assertEquals(
            "[00:01.00]stored",
            resolveLocalFirstLyricText(
                localLyric = null,
                storedLyric = "[00:01.00]stored",
                downloadedLyric = "[00:01.00]downloaded"
            )
        )
    }

    @Test
    fun `local songs never load remote lyrics`() {
        val song = SongItem(
            id = 1L,
            name = "Local",
            artist = "Artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "/tmp/local.mp3"
        )

        assertFalse(shouldLoadRemoteLyrics(song))
    }

    @Test
    fun `remote songs keep remote lyric loading enabled`() {
        val song = SongItem(
            id = 2L,
            name = "Remote",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "https://example.com/audio.mp3"
        )

        assertTrue(shouldLoadRemoteLyrics(song))
    }

    @Test
    fun `isTransientHttp2StreamReset detects cancel and refused stream failures`() {
        assertTrue(IOException("stream was reset: CANCEL").isTransientHttp2StreamReset())

        val wrapped = IOException("lyric request failed").apply {
            addSuppressed(IOException("stream was reset: REFUSED_STREAM"))
        }
        assertTrue(wrapped.isTransientHttp2StreamReset())
    }

    @Test
    fun `isTransientHttp2StreamReset keeps normal network failures visible`() {
        assertFalse(IOException("timeout").isTransientHttp2StreamReset())
        assertFalse(IOException("stream was reset: INTERNAL_ERROR").isTransientHttp2StreamReset())
    }

    @Test
    fun `extractPreferredNeteaseLyricContent prefers yrc over lrc`() {
        val payload = """
            {
              "code": 200,
              "yrc": {
                "lyric": "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记"
              },
              "lrc": {
                "lyric": "[00:12.58]难以忘记"
              }
            }
        """.trimIndent()

        val preferred = extractPreferredNeteaseLyricContent(payload)
        val parsed = parseNeteaseLyricsAuto(preferred)

        assertEquals("[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记", preferred)
        assertEquals("难以忘记", parsed.single().text)
        assertNotNull(parsed.single().words)
        assertEquals(3, parsed.single().words!!.size)
    }

    @Test
    fun `extractPreferredNeteaseLyricContent falls back to lrc when yrc missing`() {
        val payload = """
            {
              "code": 200,
              "lrc": {
                "lyric": "[00:12.58]难以忘记"
              }
            }
        """.trimIndent()

        val preferred = extractPreferredNeteaseLyricContent(payload)
        val parsed = parseNeteaseLyricsAuto(preferred)

        assertEquals("[00:12.58]难以忘记", preferred)
        assertEquals("难以忘记", parsed.single().text)
        assertNull(parsed.single().words)
    }

    @Test
    fun `parseNeteaseLyricsAuto parses ttml word timings`() {
        val rawLyrics = """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <div>
                        <p begin="00:01.000" end="00:02.000">
                            <span begin="00:01.000" end="00:01.500">Hello</span>
                            <span begin="00:01.500" end="00:02.000">World</span>
                        </p>
                    </div>
                </body>
            </tt>
        """.trimIndent()

        val parsed = parseNeteaseLyricsAuto(rawLyrics)

        assertEquals("HelloWorld", parsed.single().text)
        assertNotNull(parsed.single().words)
        assertEquals(2, parsed.single().words!!.size)
    }

    @Test
    fun `parseNeteaseLyricsAuto ignores malformed yrc timestamps`() {
        val parsed = parseNeteaseLyricsAuto(
            "[99999999999999999999,10](0,10,0)bad"
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `parseNeteaseYrc keeps empty word segments without shifting text`() {
        val parsed = parseNeteaseLyricsAuto(
            "[100,200](100,50,0)(150,50,0)字"
        )

        assertEquals("字", parsed.single().text)
        assertEquals(2, parsed.single().words?.size)
        assertEquals(0, parsed.single().words?.first()?.charCount)
        assertEquals(1, parsed.single().words?.last()?.charCount)
    }

    @Test
    fun `extractRomanizedNeteaseLyricContent reads romalrc`() {
        val payload = """
            {
              "code": 200,
              "romalrc": {
                "lyric": "[00:23.88]1/3 6 5 no ki ma gu re de"
              }
            }
        """.trimIndent()

        val romanized = extractRomanizedNeteaseLyricContent(payload)
        val parsed = parseNeteaseLyricsAuto(romanized)

        assertEquals("[00:23.88]1/3 6 5 no ki ma gu re de", romanized)
        assertEquals("1/3 6 5 no ki ma gu re de", parsed.single().text)
    }

    @Test
    fun `buildNeteaseLyricsCacheEntry parses original translated and romanized lyrics from one payload`() {
        val payload = """
            {
              "code": 200,
              "yrc": {
                "lyric": "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记"
              },
              "tlyric": {
                "lyric": "[00:12.58]hard to forget"
              },
              "romalrc": {
                "lyric": "[00:12.58]na n yi wang ji"
              }
            }
        """.trimIndent()

        val entry = PlayerLyricsProvider.buildNeteaseLyricsCacheEntry(payload)

        assertEquals(
            "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记",
            entry.preferredLyricText
        )
        assertEquals("难以忘记", entry.preferredLyricEntries.single().text)
        assertEquals("hard to forget", entry.translatedLyricEntries.single().text)
        assertEquals("[00:12.58]na n yi wang ji", entry.romanizedLyricText)
        assertEquals("na n yi wang ji", entry.romanizedLyricEntries.single().text)
    }

    @Test
    fun `cold NetEase lyric loads are deduplicated per song`() = runTest {
        val cache = TestLyricsCache()
        val releaseLoader = CompletableDeferred<Unit>()
        var loadCount = 0
        val loader: suspend (Long) -> String = {
            loadCount += 1
            releaseLoader.await()
            """{"lrc":{"lyric":"[00:01.00]line"}}"""
        }

        val first = async {
            PlayerLyricsProvider.getOrLoadNeteaseLyricsCacheEntry(11L, cache, loader)
        }
        val second = async {
            PlayerLyricsProvider.getOrLoadNeteaseLyricsCacheEntry(11L, cache, loader)
        }
        yield()

        assertEquals(1, loadCount)
        releaseLoader.complete(Unit)
        val firstEntry = first.await()
        assertNotNull(cache.get(11L))
        assertEquals(firstEntry, second.await())
        assertEquals(1, loadCount)
    }

    @Test
    fun `cold NetEase lyric load does not repopulate cache after clearing`() = runTest {
        val cache = TestLyricsCache()
        val loaderStarted = CompletableDeferred<Unit>()
        val releaseLoader = CompletableDeferred<Unit>()
        val loader: suspend (Long) -> String = {
            loaderStarted.complete(Unit)
            releaseLoader.await()
            """{"lrc":{"lyric":"[00:01.00]line"}}"""
        }

        val request = async {
            PlayerLyricsProvider.getOrLoadNeteaseLyricsCacheEntry(12L, cache, loader)
        }
        loaderStarted.await()
        cache.clear()
        PlayerLyricsProvider.clearLyricsCaches()
        releaseLoader.complete(Unit)
        request.await()

        assertNull(cache.get(12L))
    }

    @Test
    fun buildNeteaseLyricsCacheEntryRejectsCollapsedTimedLyrics() {
        val payload = """
            {
              "code": 200,
              "lrc": {
                "lyric": "[00:00.00]First line\n[00:00.00]Second line\n[00:00.00]Third line"
              }
            }
        """.trimIndent()

        val entry = PlayerLyricsProvider.buildNeteaseLyricsCacheEntry(payload)

        assertTrue(entry.preferredLyricEntries.isEmpty())
    }

    @Test
    fun hasCollapsedLyricEntryTimelineDetectsCachedZeroTimestampLyrics() {
        val entries = listOf(
            LyricEntry(text = "First", startTimeMs = 0L, endTimeMs = 0L),
            LyricEntry(text = "Second", startTimeMs = 0L, endTimeMs = 0L),
            LyricEntry(text = "Third", startTimeMs = 0L, endTimeMs = 0L)
        )

        assertTrue(hasCollapsedLyricEntryTimeline(entries))
    }

    @Test
    fun sanitizeYouTubeLyricsCacheEntryDropsCollapsedPrimaryTimeline() {
        val entry = YouTubeMusicLyricsCacheEntry(
            lyrics = listOf(
                LyricEntry(text = "First", startTimeMs = 0L, endTimeMs = 0L),
                LyricEntry(text = "Second", startTimeMs = 0L, endTimeMs = 0L),
                LyricEntry(text = "Third", startTimeMs = 0L, endTimeMs = 0L)
            )
        )

        assertNull(sanitizeYouTubeMusicLyricsCacheEntry(entry))
    }

    @Test
    fun sanitizeYouTubeLyricsCacheEntryDropsCollapsedTranslationAndAllowsRetry() {
        val entry = YouTubeMusicLyricsCacheEntry(
            lyrics = listOf(
                LyricEntry(text = "First", startTimeMs = 1_000L, endTimeMs = 2_000L),
                LyricEntry(text = "Second", startTimeMs = 3_000L, endTimeMs = 4_000L),
                LyricEntry(text = "Third", startTimeMs = 5_000L, endTimeMs = 6_000L)
            ),
            translatedLyrics = listOf(
                LyricEntry(text = "One", startTimeMs = 0L, endTimeMs = 0L),
                LyricEntry(text = "Two", startTimeMs = 0L, endTimeMs = 0L),
                LyricEntry(text = "Three", startTimeMs = 0L, endTimeMs = 0L)
            ),
            translationLookupComplete = true
        )

        val sanitized = sanitizeYouTubeMusicLyricsCacheEntry(entry)

        assertEquals(entry.lyrics, sanitized?.lyrics)
        assertTrue(sanitized?.translatedLyrics.isNullOrEmpty())
        assertEquals(false, sanitized?.translationLookupComplete)
    }

    @Test
    fun selectDurationMatchedExternalLyricsKeepsOriginalAndTranslationFromOneCandidate() {
        val selected = PlayerLyricsProvider.selectDurationMatchedExternalLyrics(
            expectedDurationMs = 240_000L,
            expectedTitle = "Correct song",
            expectedArtist = "Correct artist",
            candidates = listOf(
                EditableLyricMatchCandidate(
                    id = "correct",
                    source = EditableLyricMatchSource.CLOUD_MUSIC,
                    title = "Correct song",
                    artist = "Correct artist",
                    durationMs = 240_000L,
                    lyrics = "[00:01.00]Correct original"
                ),
                EditableLyricMatchCandidate(
                    id = "other",
                    source = EditableLyricMatchSource.QQ_MUSIC,
                    title = "Other song",
                    artist = "Other artist",
                    durationMs = 241_000L,
                    lyrics = "[00:01.00]Other original",
                    translatedLyrics = "[00:01.00]Wrong translation"
                )
            )
        )

        assertEquals("Correct original", selected?.lyrics?.single()?.text)
        assertTrue(selected?.translatedLyrics.isNullOrEmpty())
    }

    @Test
    fun selectDurationMatchedExternalLyricsRejectsSameDurationWrongSong() {
        val selected = PlayerLyricsProvider.selectDurationMatchedExternalLyrics(
            expectedDurationMs = 240_000L,
            expectedTitle = "Signal",
            expectedArtist = "Artist One",
            candidates = listOf(
                EditableLyricMatchCandidate(
                    id = "wrong",
                    source = EditableLyricMatchSource.CLOUD_MUSIC,
                    title = "Average",
                    artist = "Other Artist",
                    durationMs = 240_000L,
                    lyrics = "[00:01.00]Wrong song lyrics"
                )
            )
        )

        assertNull(selected)
    }

    @Test
    fun selectRankedDurationMatchedExternalLyricsPrefersConfiguredSourceOrder() {
        val selected = PlayerLyricsProvider.selectRankedDurationMatchedExternalLyrics(
            expectedDurationMs = 240_000L,
            expectedTitle = "Signal",
            expectedArtist = "Artist One",
            matches = listOf(
                rankedCandidate(
                    id = "lrclib",
                    source = EditableLyricMatchSource.LRCLIB,
                    score = 200,
                    lyrics = "[00:01.00]LRCLIB original"
                ),
                rankedCandidate(
                    id = "qq",
                    source = EditableLyricMatchSource.QQ_MUSIC,
                    score = 160,
                    lyrics = "[00:01.00]QQ original"
                ),
                rankedCandidate(
                    id = "kugou",
                    source = EditableLyricMatchSource.KUGOU,
                    score = 80,
                    lyrics = "[00:01.00]Kugou original"
                )
            )
        )

        assertEquals(EditableLyricMatchSource.KUGOU, selected?.source)
        assertEquals("Kugou original", selected?.lyrics?.single()?.text)
    }

    @Test
    fun selectRankedDurationMatchedExternalLyricsSkipsLowConfidenceAutoCandidates() {
        val selected = PlayerLyricsProvider.selectRankedDurationMatchedExternalLyrics(
            expectedDurationMs = 240_000L,
            expectedTitle = "Signal",
            expectedArtist = "Artist One",
            matches = listOf(
                rankedCandidate(
                    id = "low",
                    source = EditableLyricMatchSource.KUGOU,
                    confidence = EditableLyricMatchConfidence.LOW,
                    lyrics = "[00:01.00]Low confidence"
                ),
                rankedCandidate(
                    id = "medium",
                    source = EditableLyricMatchSource.CLOUD_MUSIC,
                    confidence = EditableLyricMatchConfidence.MEDIUM,
                    lyrics = "[00:01.00]Medium confidence"
                )
            )
        )

        assertNull(selected)
    }

    @Test
    fun selectRankedDurationMatchedExternalLyricsRejectsTitleAliasForAutomaticUse() {
        val selected = PlayerLyricsProvider.selectRankedDurationMatchedExternalLyrics(
            expectedDurationMs = 240_000L,
            expectedTitle = "Signal",
            expectedArtist = "Artist One",
            matches = listOf(
                rankedCandidate(
                    id = "alias",
                    source = EditableLyricMatchSource.KUGOU,
                    title = "Signal performance",
                    artist = "Artist One",
                    lyrics = "[00:01.00]Alias lyrics"
                )
            )
        )

        assertNull(selected)
    }

    @Test
    fun loadFirstUsableAutomaticExternalLyricsStopsAtFirstUsableSource() = runTest {
        val visitedSources = mutableListOf<EditableLyricMatchSource>()

        val selected = PlayerLyricsProvider.loadFirstUsableAutomaticExternalLyrics(
            request = automaticLyricRequest(),
            expectedDurationMs = 240_000L,
            expectedTitle = "Signal",
            expectedArtist = "Artist One"
        ) { source ->
            visitedSources += source
            when (source) {
                EditableLyricMatchSource.KUGOU -> listOf(
                    rankedCandidate(
                        id = "kugou",
                        source = source,
                        lyrics = "[00:01.00]Kugou original"
                    )
                )
                else -> error("lower priority source should not be queried: $source")
            }
        }

        assertEquals(EditableLyricMatchSource.KUGOU, selected?.source)
        assertEquals(listOf(EditableLyricMatchSource.KUGOU), visitedSources)
        assertEquals("Kugou original", selected?.lyrics?.single()?.text)
    }

    @Test
    fun loadFirstUsableAutomaticExternalLyricsContinuesPastUnusableSources() = runTest {
        val visitedSources = mutableListOf<EditableLyricMatchSource>()

        val selected = PlayerLyricsProvider.loadFirstUsableAutomaticExternalLyrics(
            request = automaticLyricRequest(),
            expectedDurationMs = 240_000L,
            expectedTitle = "Signal",
            expectedArtist = "Artist One"
        ) { source ->
            visitedSources += source
            when (source) {
                EditableLyricMatchSource.KUGOU -> listOf(
                    rankedCandidate(
                        id = "low",
                        source = source,
                        confidence = EditableLyricMatchConfidence.LOW,
                        lyrics = "[00:01.00]Low confidence"
                    )
                )
                EditableLyricMatchSource.CLOUD_MUSIC -> listOf(
                    rankedCandidate(
                        id = "broken",
                        source = source,
                        lyrics = "<broken>"
                    )
                )
                EditableLyricMatchSource.QQ_MUSIC -> listOf(
                    rankedCandidate(
                        id = "qq",
                        source = source,
                        lyrics = "[00:01.00]QQ original"
                    )
                )
                else -> error("LRCLIB should not be queried after QQ succeeds")
            }
        }

        assertEquals(EditableLyricMatchSource.QQ_MUSIC, selected?.source)
        assertEquals(
            listOf(
                EditableLyricMatchSource.KUGOU,
                EditableLyricMatchSource.CLOUD_MUSIC,
                EditableLyricMatchSource.QQ_MUSIC
            ),
            visitedSources
        )
        assertEquals("QQ original", selected?.lyrics?.single()?.text)
    }

    @Test
    fun resolveCachedYouTubeExternalLyricMatchRestoresMatchedResult() {
        val song = youtubeSong(name = "Signal")
        val cacheKey = PlayerLyricsProvider.buildYouTubeMusicExternalLyricMatchCacheKey(song)
        val cached = YouTubeMusicLyricsCacheEntry(
            lyrics = listOf(
                LyricEntry(
                    text = "Cached original",
                    startTimeMs = 1_000L,
                    endTimeMs = 2_000L
                )
            ),
            translatedLyrics = listOf(
                LyricEntry(
                    text = "Cached translation",
                    startTimeMs = 1_000L,
                    endTimeMs = 2_000L
                )
            ),
            translationLookupComplete = true,
            externalMatchCacheKey = cacheKey,
            externalMatchSource = EditableLyricMatchSource.CLOUD_MUSIC,
            externalMatchDurationDeltaMs = 1_500L
        )

        val resolved = PlayerLyricsProvider.resolveCachedYouTubeExternalLyricMatch(
            cached = cached,
            externalMatchCacheKey = cacheKey
        )

        assertEquals(EditableLyricMatchSource.CLOUD_MUSIC, resolved?.source)
        assertEquals(1_500L, resolved?.durationDeltaMs)
        assertEquals("Cached original", resolved?.lyrics?.single()?.text)
        assertEquals("Cached translation", resolved?.translatedLyrics?.single()?.text)
    }

    @Test
    fun resolveCachedYouTubeExternalLyricMatchRejectsStaleMetadataKey() {
        val originalSong = youtubeSong(name = "Signal")
        val renamedSong = youtubeSong(name = "Different signal")
        val originalCacheKey = PlayerLyricsProvider.buildYouTubeMusicExternalLyricMatchCacheKey(originalSong)
        val renamedCacheKey = PlayerLyricsProvider.buildYouTubeMusicExternalLyricMatchCacheKey(renamedSong)
        val cached = YouTubeMusicLyricsCacheEntry(
            lyrics = listOf(
                LyricEntry(
                    text = "Cached original",
                    startTimeMs = 1_000L,
                    endTimeMs = 2_000L
                )
            ),
            translationLookupComplete = true,
            externalMatchCacheKey = originalCacheKey,
            externalMatchSource = EditableLyricMatchSource.KUGOU
        )

        assertTrue(originalCacheKey != renamedCacheKey)
        assertNull(
            PlayerLyricsProvider.resolveCachedYouTubeExternalLyricMatch(
                cached = cached,
                externalMatchCacheKey = renamedCacheKey
            )
        )
    }

    @Test
    fun resolveYouTubeMusicTranslationCacheEntryDoesNotAttachOtherSourceTranslation() {
        val cached = YouTubeMusicLyricsCacheEntry(
            lyrics = listOf(
                LyricEntry(
                    text = "Correct original",
                    startTimeMs = 1_000L,
                    endTimeMs = 2_000L
                )
            )
        )
        val external = DurationMatchedExternalLyrics(
            lyrics = listOf(
                LyricEntry(
                    text = "Other original",
                    startTimeMs = 1_000L,
                    endTimeMs = 2_000L
                )
            ),
            translatedLyrics = listOf(
                LyricEntry(
                    text = "Wrong translation",
                    startTimeMs = 1_000L,
                    endTimeMs = 2_000L
                )
            ),
            source = EditableLyricMatchSource.CLOUD_MUSIC,
            durationDeltaMs = 0L
        )

        val resolved = resolveYouTubeMusicTranslationCacheEntry(cached, external)

        assertEquals(cached.lyrics, resolved?.lyrics)
        assertTrue(resolved?.translatedLyrics.isNullOrEmpty())
        assertTrue(resolved?.translationLookupComplete == true)
    }

    @Test
    fun shouldBlockExternalYouTubeMusicTranslationForStoredLyricsOnly() {
        assertFalse(shouldBlockExternalYouTubeMusicTranslation(null))
        assertTrue(shouldBlockExternalYouTubeMusicTranslation(""))
        assertTrue(shouldBlockExternalYouTubeMusicTranslation("[00:01.00]Stored original"))
        assertFalse(
            shouldBlockExternalYouTubeMusicTranslation(
                "[00:00.00]First\n[00:00.00]Second\n[00:00.00]Third"
            )
        )
    }

    private fun automaticLyricRequest(): EditableLyricMatchRequest {
        return EditableLyricMatchRequest(
            keyword = "Signal Artist One",
            trackName = "Signal",
            artistName = "Artist One",
            durationMs = 240_000L,
            sources = setOf(
                EditableLyricMatchSource.KUGOU,
                EditableLyricMatchSource.CLOUD_MUSIC,
                EditableLyricMatchSource.QQ_MUSIC,
                EditableLyricMatchSource.LRCLIB
            )
        )
    }

    private fun rankedCandidate(
        id: String,
        source: EditableLyricMatchSource,
        title: String = "Signal",
        artist: String = "Artist One",
        durationMs: Long = 240_000L,
        lyrics: String,
        score: Int = 120,
        confidence: EditableLyricMatchConfidence = EditableLyricMatchConfidence.HIGH
    ): RankedEditableLyricMatch {
        return RankedEditableLyricMatch(
            candidate = EditableLyricMatchCandidate(
                id = id,
                source = source,
                title = title,
                artist = artist,
                durationMs = durationMs,
                lyrics = lyrics
            ),
            score = score,
            durationDeltaMs = kotlin.math.abs(240_000L - durationMs),
            confidence = confidence
        )
    }

    private fun youtubeSong(name: String): SongItem {
        return SongItem(
            id = 42L,
            name = name,
            artist = "Artist One",
            album = "Album One",
            albumId = 0L,
            durationMs = 240_000L,
            coverUrl = null,
            mediaUri = "https://music.youtube.com/watch?v=video42"
        )
    }
}
