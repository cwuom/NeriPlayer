package moe.ouom.neriplayer.ui.component.lyrics

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaCovers
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaDownloads
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricShareSheetComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val lyrics = listOf(
        LyricEntry(text = "First line", startTimeMs = 0L, endTimeMs = 1_000L),
        LyricEntry(text = "Second line", startTimeMs = 1_000L, endTimeMs = 2_000L),
        LyricEntry(text = " ", startTimeMs = 2_000L, endTimeMs = 3_000L),
        LyricEntry(text = "Third line", startTimeMs = 3_000L, endTimeMs = 4_000L),
        LyricEntry(text = "Fourth line", startTimeMs = 4_000L, endTimeMs = 5_000L)
    )
    private var dismissCount = 0
    private val messages = mutableListOf<String>()

    @Before
    fun setUp() {
        LocalMediaHostAccess.bind(
            downloads = AndroidLocalMediaDownloads,
            covers = AndroidLocalMediaCovers,
            crashLogs = CrashLogCleanup(ExceptionHandler::clearCrashLogs)
        )
    }

    @After
    fun tearDown() {
        File(context.cacheDir, "lyric_share_cards").deleteRecursively()
    }

    private fun song(
        coverUrl: String? = null,
        mediaUri: String? = null,
        localFilePath: String? = null
    ) = SongItem(
        id = 42L,
        name = "Share Song",
        artist = "Share Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 5_000L,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        localFilePath = localFilePath
    )

    private fun clickAction(labelRes: Int) {
        composeRule.onNodeWithContentDescription(context.getString(labelRes)).performClick()
    }

    private fun awaitDismissOrMessage(expectedDismissCount: Int = 1) {
        composeRule.waitUntil(timeoutMillis = 20_000L) {
            ShadowLooper.idleMainLooper()
            dismissCount == expectedDismissCount || messages.isNotEmpty()
        }
    }

    private fun setSheet(
        sheetLyrics: List<LyricEntry> = lyrics,
        initialLine: LyricEntry = lyrics[3],
        song: SongItem = song()
    ) {
        composeRule.setContent {
            LyricShareSheet(
                song = song,
                lyrics = sheetLyrics,
                initialLine = initialLine,
                queue = listOf(song),
                onDismiss = { dismissCount++ },
                onShowMessage = { messages += it }
            )
        }
        composeRule.waitForIdle()
    }

    private fun selectedTitle(count: Int) =
        context.getString(CoreCommonR.string.lyric_share_selected_lines, count)

    private fun lastChooserTarget(): Intent {
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        return chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!
    }

    @Test
    fun `sheet preselects the playing line and extends the selection by tap and long press`() {
        setSheet()
        composeRule.onNodeWithText(selectedTitle(1)).assertExists()
        composeRule.onNodeWithText(
            context.getString(CoreCommonR.string.lyric_share_character_count, "Third line".length)
        ).assertExists()

        composeRule.onNodeWithText("Fourth line").performClick()
        composeRule.onNodeWithText(selectedTitle(2)).assertExists()

        composeRule.onNodeWithText("First line").performTouchInput { longClick() }
        composeRule.onNodeWithText(selectedTitle(4)).assertExists()

        composeRule.onNodeWithText("Second line").performClick()
        composeRule.onNodeWithText(selectedTitle(3)).assertExists()
    }

    @Test
    fun `copy places the selected lines on the clipboard and closes the sheet`() {
        setSheet(initialLine = LyricEntry(text = "Second line", startTimeMs = 1_200L, endTimeMs = 2_000L))
        composeRule.onNodeWithText("Third line").performClick()

        composeRule.onNodeWithContentDescription(
            context.getString(CoreCommonR.string.lyric_share_copy_lyrics)
        ).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { dismissCount == 1 }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Second line\nThird line", clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test
    fun `sheet without shareable lyrics dismisses itself`() {
        val blank = LyricEntry(text = "  ", startTimeMs = 0L, endTimeMs = 1_000L)
        setSheet(sheetLyrics = listOf(blank), initialLine = blank)

        assertEquals(1, dismissCount)
    }

    @Test
    fun `share song opens a text chooser with the song title`() {
        setSheet()

        composeRule.onNodeWithContentDescription(
            context.getString(CoreCommonR.string.lyric_share_song)
        ).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { dismissCount == 1 }

        val target = lastChooserTarget()
        assertEquals("text/plain", target.type)
        assertTrue(target.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Share Song"))
    }

    @Test
    fun `share song appends the platform link when the song has one`() {
        setSheet(song = song(mediaUri = "https://music.youtube.com/watch?v=abc123XYZ_0"))

        clickAction(CoreCommonR.string.lyric_share_song)
        awaitDismissOrMessage()

        val text = lastChooserTarget().getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text, text.contains("https://music.youtube.com/watch?v=abc123XYZ_0"))
    }

    @Test
    fun `sharing a missing local file reports failure`() {
        setSheet(song = song(localFilePath = File(context.cacheDir, "missing.mp3").absolutePath))

        clickAction(CoreCommonR.string.lyric_share_song)
        composeRule.waitUntil(timeoutMillis = 5_000L) { dismissCount == 1 }

        assertEquals(listOf(context.getString(CoreCommonR.string.local_song_share_failed)), messages)
    }

    // FileProvider caches its roots per process while Robolectric gives each test a new data
    // directory, so every lyric card share has to happen inside this one test.
    @Test
    @Config(qualifiers = "w411dp-h900dp")
    fun `lyric cards are rendered saved and shared as pngs`() {
        val coverFile = File(context.cacheDir, "share-cover.png")
        coverFile.outputStream().use { output ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(Color.rgb(200, 40, 90)) }
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val longLyrics = List(12) { index ->
            LyricEntry(
                text = "A rather long lyric line number $index that needs wrapping on the card ".repeat(4),
                startTimeMs = index * 1_000L,
                endTimeMs = (index + 1) * 1_000L
            )
        }
        val remoteSong = song(
            coverUrl = coverFile.toURI().toString(),
            mediaUri = "https://music.youtube.com/watch?v=abc123XYZ_0"
        )
        val localSong = song(localFilePath = File(context.cacheDir, "local.mp3").absolutePath)
        var currentSong by mutableStateOf(remoteSong)
        composeRule.setContent {
            LyricShareSheet(
                song = currentSong,
                lyrics = longLyrics,
                initialLine = longLyrics[0],
                queue = listOf(currentSong),
                onDismiss = { dismissCount++ },
                onShowMessage = { messages += it }
            )
        }
        composeRule.onNodeWithText(longLyrics[2].text).performTouchInput { longClick() }

        clickAction(CoreCommonR.string.lyric_share_card)
        awaitDismissOrMessage(expectedDismissCount = 1)

        assertEquals(emptyList<String>(), messages)
        val remoteTarget = lastChooserTarget()
        assertEquals("image/png", remoteTarget.type)
        @Suppress("DEPRECATION")
        assertNotNull(remoteTarget.getParcelableExtra(Intent.EXTRA_STREAM))
        assertEquals(
            "Share Song - Share Artist\nhttps://music.youtube.com/watch?v=abc123XYZ_0",
            remoteTarget.getStringExtra(Intent.EXTRA_TEXT)
        )
        val firstCard = File(context.cacheDir, "lyric_share_cards").listFiles().orEmpty().single()
        assertTrue(firstCard.length() > 0L)

        currentSong = localSong
        composeRule.waitForIdle()
        clickAction(CoreCommonR.string.lyric_share_card)
        awaitDismissOrMessage(expectedDismissCount = 2)

        assertEquals(emptyList<String>(), messages)
        assertEquals("Share Song - Share Artist", lastChooserTarget().getStringExtra(Intent.EXTRA_TEXT))
        val cards = File(context.cacheDir, "lyric_share_cards").listFiles().orEmpty()
        assertEquals(2, cards.size)
        assertTrue(firstCard in cards)
    }
}
