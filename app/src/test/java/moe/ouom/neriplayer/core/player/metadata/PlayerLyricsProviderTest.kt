package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchCandidate
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchSource
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

    @Test
    fun `resolveLocalLyricOverrideState keeps blank local override as cleared`() {
        assertEquals(LocalLyricOverrideState.ABSENT, resolveLocalLyricOverrideState(null))
        assertEquals(LocalLyricOverrideState.CLEARED, resolveLocalLyricOverrideState(""))
        assertEquals(LocalLyricOverrideState.CLEARED, resolveLocalLyricOverrideState("   "))
        assertEquals(LocalLyricOverrideState.PRESENT, resolveLocalLyricOverrideState("[00:00.00]歌词"))
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
}
