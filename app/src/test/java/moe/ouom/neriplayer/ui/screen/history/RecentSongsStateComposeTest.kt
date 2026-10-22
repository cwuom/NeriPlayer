package moe.ouom.neriplayer.ui.screen.history

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.Locale
import moe.ouom.neriplayer.ui.screen.playlist.SongIdentityLookup

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RecentSongsStateComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `hidden search skips indexing and visible search reads tokens off the UI thread`() {
        val callerReads = AtomicInteger()
        val workerReads = AtomicInteger()
        val song = SongItem(
            id = 1L, name = "晴天", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 60_000L, coverUrl = null
        )
        val rows = object : AbstractList<RecentSongRowItem>() {
            override val size = 1

            override fun get(index: Int): RecentSongRowItem {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    callerReads.incrementAndGet()
                } else {
                    workerReads.incrementAndGet()
                }
                return RecentSongRowItem(song, "selection", "item")
            }
        }
        val snapshot = RecentSongsSnapshot(rows, listOf(song))
        var searchVisible by mutableStateOf(false)
        composeRule.setContent {
            val displayed = rememberRecentSearchResults(snapshot, "", context, searchVisible)
            Text(displayed.songs.size.toString())
        }

        composeRule.waitForIdle()
        assertEquals(0, callerReads.get())
        assertEquals(0, workerReads.get())

        composeRule.runOnIdle { searchVisible = true }
        waitForAsyncCondition { workerReads.get() > 0 }
        assertEquals(0, callerReads.get())
    }

    @Test
    fun `search retains ranked queue order and follows the latest query and history`() {
        var history by mutableStateOf(
            listOf(entry(1L, "晴天"), entry(2L, "A晴天B"), entry(3L, "Beta"))
        )
        var query by mutableStateOf("qt")
        val displayed = AtomicReference<RecentSongsSnapshot>()
        composeRule.setContent {
            val snapshot = rememberRecentSongs(history)
            val results = rememberRecentSearchResults(snapshot, query, context, searchVisible = true)
            SideEffect { displayed.set(results) }
            Text(results.songs.size.toString())
        }

        waitForSongs(displayed, listOf(1L, 2L))
        composeRule.runOnIdle { query = "beta" }
        waitForSongs(displayed, listOf(3L))
        composeRule.runOnIdle {
            history = listOf(entry(4L, "New Alpha"), entry(5L, "New Beta"))
            query = "alpha"
        }
        waitForSongs(displayed, listOf(4L))
        composeRule.runOnIdle { query = "" }
        waitForSongs(displayed, listOf(4L, 5L))
    }

    @Test
    fun `changing the query never publishes songs from the previous query`() {
        val history = listOf(entry(1L, "Alpha"), entry(2L, "Beta"))
        var query by mutableStateOf("alpha")
        val displayed = AtomicReference<RecentSongsSnapshot>()
        val observations = ConcurrentLinkedQueue<Pair<String, List<Long>>>()
        composeRule.setContent {
            val snapshot = rememberRecentSongs(history)
            val results = rememberRecentSearchResults(snapshot, query, context, searchVisible = true)
            val currentQuery = query
            SideEffect {
                displayed.set(results)
                observations.add(currentQuery to results.songs.map { it.id })
            }
            Text(results.songs.size.toString())
        }

        waitForSongs(displayed, listOf(1L))
        composeRule.runOnIdle { query = "beta" }
        waitForSongs(displayed, listOf(2L))

        val betaResults = observations.filter { it.first == "beta" }.map { it.second }
        assertEquals(false, betaResults.isEmpty())
        betaResults.forEach { ids ->
            assertEquals("A changed query must not expose the previous playback or deletion rows", false, 1L in ids)
        }
    }

    @Test
    fun `local album search follows the current UI language`() {
        val chinese = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        )
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        )
        var displayContext by mutableStateOf(chinese)
        var query by mutableStateOf("本地文件")
        val history = listOf(
            entry(1L, "Track").copy(album = "__local_files__", mediaUri = "file:///music/track.flac")
        )
        val displayed = AtomicReference<RecentSongsSnapshot>()
        composeRule.setContent {
            val snapshot = rememberRecentSongs(history)
            val results = rememberRecentSearchResults(snapshot, query, displayContext, searchVisible = true)
            SideEffect { displayed.set(results) }
            Text(results.songs.size.toString())
        }

        waitForSongs(displayed, listOf(1L))
        composeRule.runOnIdle { query = "missing" }
        waitForSongs(displayed, emptyList())
        composeRule.runOnIdle {
            displayContext = english
            query = "local files"
        }
        waitForSongs(displayed, listOf(1L))
    }

    @Test
    fun `changing the UI language never publishes matches from the previous language`() {
        val chinese = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        )
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        )
        var displayContext by mutableStateOf(chinese)
        val history = listOf(entry(1L, "Track").copy(album = "__local_files__"))
        val displayed = AtomicReference<RecentSongsSnapshot>()
        val englishResults = ConcurrentLinkedQueue<List<Long>>()
        composeRule.setContent {
            val snapshot = rememberRecentSongs(history)
            val results = rememberRecentSearchResults(snapshot, "本地文件", displayContext, searchVisible = true)
            val isEnglish = displayContext === english
            SideEffect {
                displayed.set(results)
                if (isEnglish) englishResults.add(results.songs.map { it.id })
            }
            Text(results.songs.size.toString())
        }

        waitForSongs(displayed, listOf(1L))
        composeRule.runOnIdle { displayContext = english }
        waitForSongs(displayed, emptyList())

        assertEquals(false, englishResults.isEmpty())
        englishResults.forEach { ids -> assertEquals(emptyList<Long>(), ids) }
    }

    @Test
    fun `changed favorites disable stale lookup while the replacement is prepared`() {
        val first = SongItem(
            id = 1L, name = "First", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 60_000L, coverUrl = null
        )
        val replacementSong = first.copy(id = 2L, name = "Replacement")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacement = object : AbstractList<SongItem>() {
            override val size = 1

            override fun get(index: Int): SongItem {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return replacementSong
            }
        }
        var favorites by mutableStateOf(listOf(first), referentialEqualityPolicy())
        val lookup = AtomicReference<SongIdentityLookup?>()
        composeRule.setContent {
            val prepared = rememberRecentFavoriteLookup(favorites)
            SideEffect { lookup.set(prepared) }
            Text(if (prepared == null) "preparing" else "ready")
        }
        waitForAsyncCondition { lookup.get()?.contains(first) == true }

        try {
            composeRule.runOnIdle { favorites = replacement }
            waitForAsyncCondition { started.count == 0L }
            composeRule.runOnIdle { assertNull(lookup.get()) }
        } finally {
            release.countDown()
        }

        waitForAsyncCondition { lookup.get()?.contains(replacementSong) == true }
        assertEquals(false, lookup.get()?.contains(first))
    }

    private fun waitForSongs(displayed: AtomicReference<RecentSongsSnapshot>, ids: List<Long>) {
        waitForAsyncCondition { displayed.get()?.songs?.map { it.id } == ids }
        val result = displayed.get()
        assertEquals(ids, result.rows.map { it.song.id })
        result.rows.forEachIndexed { index, row -> assertEquals(result.songs[index], row.song) }
    }

    private fun waitForAsyncCondition(condition: () -> Boolean) {
        composeRule.waitUntil(5_000) {
            composeRule.waitForIdle()
            condition()
        }
    }

    private fun entry(id: Long, name: String) = PlayedEntry(
        id = id,
        name = name,
        artist = "Artist",
        album = "Album",
        durationMs = 60_000L,
        coverUrl = null,
        playedAt = 1_000L - id
    )
}
