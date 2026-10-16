package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingFastLyricsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AdvancedLyricsPreparationTest {
    @Test
    fun publicViewDoesNotParseRawTranslationDuringSynchronousComposition() = runTest {
        val translation = (0 until 300).joinToString("\n") { line ->
            "[${line / 60}:${(line % 60).toString().padStart(2, '0')}.00]translation $line"
        }
        withComposition {
            Mockito.mockStatic(parserClass, Mockito.CALLS_REAL_METHODS).use { parser ->
                setContent {
                    // 空原文避免创建布局节点，仍通过真实入口检查独立翻译的同步解析
                    AdvancedLyricsView(
                        lyrics = emptyList(), currentTimeMs = 0L, textColor = Color.White,
                        lyricFontScale = 1f, rawTranslatedLyrics = translation
                    )
                }

                parser.verify({ parseNeteaseLyricsAuto(translation) }, Mockito.never())
            }
        }
    }

    @Test
    fun publicViewDoesNotBuildRawYrcDuringSynchronousComposition() = runTest {
        val raw = "[1000,1000](1000,500,0)first(1500,500,0)second"
        val parsed = parseNeteaseLyricsAuto(raw)
        withComposition {
            Mockito.mockStatic(viewClass, Mockito.CALLS_REAL_METHODS).use { builder ->
                builder.`when`<SyncedLyrics> {
                    buildAdvancedSyncedLyrics(raw, null, parsed, emptyList(), false)
                }.thenReturn(SyncedLyrics(emptyList()))
                setContent {
                    AdvancedLyricsView(
                        lyrics = parsed, currentTimeMs = 0L, textColor = Color.White,
                        lyricFontScale = 1f, rawLyrics = raw
                    )
                }

                builder.verify({ buildAdvancedSyncedLyrics(raw, null, parsed, emptyList(), false) }, Mockito.never())
            }
        }
    }

    @Test
    fun backgroundPreparationKeepsTheOriginalYrcTimingAndSpacing() = runBlocking {
        val raw = "[1000,1000](0,500,0)first(500,500,0)second"
        val input = input(raw).copy(lyrics = parseNeteaseLyricsAuto(raw))
        val expected = buildAdvancedSyncedLyrics(raw, null, input.lyrics, emptyList())
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        lateinit var entry: AdvancedLyricsPreparationEntry
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val cache = AdvancedLyricsPreparationCache(dispatcher) { source ->
                worker.set(Thread.currentThread())
                buildAdvancedSyncedLyrics(source.rawLyrics, source.rawTranslatedLyrics,
                    source.lyrics, source.translatedLyrics, source.showPhoneticAsTranslation)
            }
            entry = cache.entryFor(input)
            entry.prepare()
        }

        assertNotSame(caller, worker.get())
        assertEquals(expected, entry.ready)
        val line = checkNotNull(entry.ready).lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals(1000, line.start)
        assertEquals(1500, line.syllables.last().start)
        assertEquals("first second", line.syllables.joinToString("") { it.content })
    }

    @Test
    fun preparationPreservesRichTtmlAndExplicitSecondaryClearSemantics() = runTest {
        val raw = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                <body><div><p begin="00:00:01.000" end="00:00:03.000">
                    <span begin="00:00:01.000" end="00:00:03.000">first</span>
                    <span ttm:role="x-bg" begin="00:00:01.000" end="00:00:02.000">
                        <span begin="00:00:01.000" end="00:00:02.000">background</span>
                        <span ttm:role="x-translation">old accompaniment</span>
                    </span>
                    <span ttm:role="x-translation">old first</span>
                    <span ttm:role="x-roman">old phonetic</span>
                </p></div></body>
            </tt>
        """.trimIndent()
        val cache = AdvancedLyricsPreparationCache(StandardTestDispatcher(testScheduler))
        val variants = listOf(input(raw), input(raw).copy(rawTranslatedLyrics = ""),
            input(raw).copy(showPhoneticAsTranslation = true))
        for (source in variants) {
            val entry = cache.entryFor(source)
            entry.prepare()
            assertEquals(buildAdvancedSyncedLyrics(source.rawLyrics, source.rawTranslatedLyrics,
                source.lyrics, source.translatedLyrics, source.showPhoneticAsTranslation), entry.ready)
        }

        val original = checkNotNull(cache.entryFor(variants[0]).ready).lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals("old first", original.translation)
        assertEquals("old accompaniment", original.accompanimentLines!!.single().translation)
        val cleared = checkNotNull(cache.entryFor(variants[1]).ready).lines.single() as KaraokeLine.MainKaraokeLine
        assertNull(cleared.translation)
        assertNull(cleared.accompanimentLines!!.single().translation)
        val phonetic = checkNotNull(cache.entryFor(variants[2]).ready).lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals("old phonetic", phonetic.translation)
        assertNull(phonetic.accompanimentLines!!.single().translation)
    }

    @Test
    fun cacheSeparatesCompleteInputsAndRetainsOnlyFourEntries() {
        val cache = AdvancedLyricsPreparationCache()
        val original = input("original")
        val first = cache.entryFor(original)
        assertSame(first, cache.entryFor(original.copy()))
        assertNotSame(first, cache.entryFor(original.copy(rawTranslatedLyrics = "")))
        assertNotSame(first, cache.entryFor(original.copy(showPhoneticAsTranslation = true)))
        assertNotSame(first, cache.entryFor(original.copy(lyrics = listOf(LyricEntry("changed", 0, 1)))))
        cache.entryFor(original.copy(translatedLyrics = listOf(LyricEntry("translated", 0, 1))))
        assertNotSame(first, cache.entryFor(original))
    }

    @Test
    fun cancellingOneConsumerCannotPublishItsLateResultOrCancelAnotherInput() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val currentResult = result("current")
        lateinit var old: AdvancedLyricsPreparationEntry
        lateinit var current: AdvancedLyricsPreparationEntry
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val cache = AdvancedLyricsPreparationCache(dispatcher) { source ->
                if (source.rawLyrics == "old") {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    result("late old")
                } else currentResult
            }
            old = cache.entryFor(input("old"))
            current = cache.entryFor(input("current"))
            val oldJob = launch(start = CoroutineStart.UNDISPATCHED) {
                old.prepare()
            }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                current.prepare()
                oldJob.cancel()
            } finally {
                release.countDown()
                oldJob.join()
            }
        }

        assertNull(old.ready)
        assertSame(currentResult, current.ready)
    }

    @Test
    fun anotherConsumerOfTheSameInputCanFinishAfterTheFirstConsumerCancels() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = result("shared")
        val builds = AtomicInteger()
        lateinit var entry: AdvancedLyricsPreparationEntry
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val cache = AdvancedLyricsPreparationCache(dispatcher) {
                if (builds.incrementAndGet() == 1) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    result("cancelled")
                } else completed
            }
            entry = cache.entryFor(input("shared"))
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                entry.prepare()
            }
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                entry.prepare()
            }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                first.cancel()
            } finally {
                release.countDown()
                joinAll(first, second)
            }
            entry.prepare()
        }

        assertEquals(2, builds.get())
        assertSame(completed, entry.ready)
    }

    @Test
    fun aNewCompositionImmediatelyReusesTheLoadedSnapshotPreparation() = runTest {
        val loaded = buildNowPlayingFastLyricsState("[00:01.00]warm", null, null)
        val input = input(loaded.rawLyrics).copy(lyrics = loaded.lyrics)
        val entry = loaded.advancedLyricsPreparation.entryFor(input)
        entry.prepare()
        val prepared = checkNotNull(entry.ready)
        assertTrue(prepared.lines.isNotEmpty())
        repeat(2) {
            withComposition {
                var visible: SyncedLyrics? = null
                setContent {
                    CompositionLocalProvider(LocalAdvancedLyricsPreparationCache provides loaded.advancedLyricsPreparation) {
                        visible = rememberAdvancedSyncedLyrics(input)
                    }
                }
                assertSame(prepared, visible)
                runCurrent()
                assertSame(prepared, entry.ready)
            }
        }

        val changed = loaded.copy(rawLyrics = "changed")
        assertNotSame(loaded.advancedLyricsPreparation, changed.advancedLyricsPreparation)
        assertNull(changed.advancedLyricsPreparation.entryFor(input).ready)
    }

    @Test
    fun simultaneousViewsKeepIndependentInputsWithoutReturningTheOtherResult() = runTest {
        val original = input("same original")
        val phonetic = original.copy(showPhoneticAsTranslation = true)
        val firstResult = result("translation")
        val secondResult = result("phonetic")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = AdvancedLyricsPreparationCache(dispatcher) { source ->
            if (source.showPhoneticAsTranslation) secondResult else firstResult
        }
        cache.entryFor(original).prepare()
        cache.entryFor(phonetic).prepare()
        withComposition {
            var first: SyncedLyrics? = null
            var second: SyncedLyrics? = null
            var changed: SyncedLyrics? = null
            setContent {
                CompositionLocalProvider(LocalAdvancedLyricsPreparationCache provides cache) {
                    first = rememberAdvancedSyncedLyrics(original)
                    second = rememberAdvancedSyncedLyrics(phonetic)
                    changed = rememberAdvancedSyncedLyrics(original.copy(rawLyrics = "new original"))
                }
            }
            assertSame(firstResult, first)
            assertSame(secondResult, second)
            assertTrue(checkNotNull(changed).lines.isEmpty())
        }
    }

    @Test
    fun ordinaryCallerKeepsItsLocalPreparationButNewInputsNeverReturnOldLyrics() = runTest {
        withComposition {
            var visible: SyncedLyrics? = null
            fun render(source: AdvancedLyricsPreparationInput) {
                setContent { visible = rememberAdvancedSyncedLyrics(source) }
            }
            suspend fun awaitPrepared(source: AdvancedLyricsPreparationInput): SyncedLyrics {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (true) {
                    runCurrent()
                    render(source)
                    val current = checkNotNull(visible)
                    if (current.lines.isNotEmpty()) return current
                    check(System.nanoTime() < deadline) { "真实 Default 歌词准备超时" }
                    withContext(Dispatchers.Default) { delay(1) }
                }
            }
            val first = input("[00:01.00]first")
            val second = input("[00:01.00]second")
            render(first)
            assertTrue(checkNotNull(visible).lines.isEmpty())
            val firstResult = awaitPrepared(first)
            assertEquals("first", (firstResult.lines.single() as SyncedLine).content)
            render(first)
            runCurrent()
            assertSame(firstResult, visible)

            render(second)
            assertTrue(checkNotNull(visible).lines.isEmpty())
            val secondResult = awaitPrepared(second)
            assertEquals("second", (secondResult.lines.single() as SyncedLine).content)
            assertNotSame(firstResult, secondResult)
            render(second)
            runCurrent()
            assertSame(secondResult, visible)
            render(first)
            assertSame(firstResult, visible)
        }
    }

    private fun input(raw: String?) = AdvancedLyricsPreparationInput(raw, null, emptyList(), emptyList(), false)

    private fun result(text: String) = SyncedLyrics(listOf(SyncedLine(text, null, 0, 1000)))

    private suspend fun TestScope.withComposition(block: suspend Composition.() -> Unit) {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        try {
            composition.block()
        } finally {
            composition.dispose()
            recomposer.cancel()
            recomposer.join()
        }
    }

    private companion object {
        val parserClass: Class<*> = Class.forName("moe.ouom.neriplayer.lyrics.parser.LyricParserKt")
        val viewClass: Class<*> = Class.forName("moe.ouom.neriplayer.ui.component.lyrics.AdvancedLyricsViewKt")
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
