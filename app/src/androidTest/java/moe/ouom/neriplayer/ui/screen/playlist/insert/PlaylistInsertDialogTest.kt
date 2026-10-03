package moe.ouom.neriplayer.ui.screen.playlist.insert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaylistInsertDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val artworkFiles = mutableListOf<File>()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun removeSyntheticArtwork() {
        artworkFiles.forEach { it.delete() }
    }

    @Test
    fun previewShowsRealMetadataAndFinalPositionsWithoutCommitting() {
        val songs = demoSongs()
        val selectedKeys = setOf(songs[1].stableKey(), songs[4].stableKey())
        var confirmed: PlaylistInsertPreview? = null
        showDialog(songs, selectedKeys) { confirmed = it }

        replaceInput("2")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        savePreviewScreenshot()

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_title)).assertIsDisplayed()
        composeRule.onNodeWithText("星河漫游").assertIsDisplayed()
        composeRule.onNodeWithText("青岚").assertIsDisplayed()
        composeRule.onNodeWithTag("playlist-insert-artwork-${songs[1].stableKey()}").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val pixels = composeRule.onNodeWithTag("playlist-insert-artwork-${songs[1].stableKey()}")
                .captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 8].toArgb() == 0xFF6976BC.toInt()
        }
        savePreviewScreenshot()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.runOnIdle {
            assertNull(confirmed)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), songs.map { it.id })
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(6)
        composeRule.onNodeWithText("晨间列车").assertIsDisplayed()
        composeRule.onNodeWithText("4").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        savePreviewScreenshot()

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).performClick()
        composeRule.runOnIdle {
            val result = requireNotNull(confirmed)
            assertEquals(2, result.startPosition)
            assertEquals(listOf(songs[1].stableKey(), songs[4].stableKey()), result.movedKeys)
            assertEquals(
                listOf(songs[0], songs[1], songs[4], songs[2], songs[3], songs[5]).map { it.stableKey() },
                result.orderedKeys
            )
        }
    }

    @Test
    fun changingInputRequiresAnotherPreviewBeforeConfirmation() {
        val songs = demoSongs()
        var confirmed: PlaylistInsertPreview? = null
        showDialog(songs, setOf(songs[2].stableKey())) { confirmed = it }
        replaceInput("2")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_edit_position)).performClick()
        composeRule.onNode(hasSetTextAction()).assertTextContains("2")
        replaceInput("4")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.runOnIdle { assertNull(confirmed) }

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).performClick()
        composeRule.runOnIdle { assertEquals(4, confirmed?.startPosition) }
    }

    @Test
    fun changingSourceInvalidatesPreviewAndUsesTheNewSnapshot() {
        val songs = demoSongs()
        val source = mutableStateOf(songs)
        val selectedKeys = setOf(songs[2].stableKey())
        var confirmed: PlaylistInsertPreview? = null
        composeRule.setContent {
            MaterialTheme {
                PlaylistInsertDialog(source.value, selectedKeys, {}, { confirmed = it }, offlineMode = true)
            }
        }
        replaceInput("2")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()

        composeRule.runOnIdle { source.value = songs.reversed() }
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_stale)).assertIsDisplayed()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.runOnIdle { assertNull(confirmed) }
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).performClick()
        composeRule.runOnIdle { assertEquals(songs.reversed().map { it.stableKey() }, confirmed?.sourceKeys) }
    }

    @Test
    fun emptyAndOutOfRangeCannotBeConfirmed() {
        val songs = demoSongs()
        var confirmCount = 0
        showDialog(songs, setOf(songs[0].stableKey())) { confirmCount += 1 }

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).assertIsNotEnabled()
        replaceInput("0")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).assertIsNotEnabled()
        replaceInput("7")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, confirmCount) }
    }

    @Test
    fun removingSelectedSongInvalidatesAndDisablesPreview() {
        val songs = demoSongs()
        val source = mutableStateOf(songs)
        var confirmCount = 0
        composeRule.setContent {
            MaterialTheme {
                PlaylistInsertDialog(
                    source.value,
                    setOf(songs[2].stableKey()),
                    {},
                    { confirmCount += 1 },
                    offlineMode = true
                )
            }
        }
        replaceInput("2")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.runOnIdle { source.value = songs.filterNot { it == songs[2] } }

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).assertIsNotEnabled()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_invalid_selection)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, confirmCount) }
    }

    @Test
    fun longSelectionCanReachTheFollowingContextAndKeepConfirmAvailable() {
        val coverUrl = syntheticCover(100, 0xFF75A5B9.toInt())
        val songs = (1L..80L).map { index ->
            SongItem(
                id = index,
                name = "曲目 $index",
                artist = "测试歌手 $index",
                album = "preview-test",
                albumId = 1L,
                durationMs = 180_000L,
                coverUrl = coverUrl
            )
        }
        val selectedKeys = songs.subList(10, 70).mapTo(mutableSetOf()) { it.stableKey() }
        var confirmed: PlaylistInsertPreview? = null
        showDialog(songs, selectedKeys) { confirmed = it }
        replaceInput("5")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(65)
        composeRule.onNodeWithText("曲目 5").assertIsDisplayed()
        composeRule.onNodeWithText("65").assertIsDisplayed()
        composeRule.runOnIdle { assertNull(confirmed) }
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action))
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            val result = requireNotNull(confirmed)
            assertEquals(60, result.movedKeys.size)
            assertEquals(songs.subList(10, 70).map { it.stableKey() }, result.movedKeys)
            assertEquals(5, result.startPosition)
            assertEquals(songs.map { it.stableKey() }.toSet(), result.orderedKeys.toSet())
        }
    }

    private fun showDialog(
        songs: List<SongItem>,
        selectedKeys: Set<String>,
        onConfirm: (PlaylistInsertPreview) -> Unit
    ) {
        composeRule.setContent {
            MaterialTheme {
                PlaylistInsertDialog(songs, selectedKeys, {}, onConfirm, offlineMode = true)
            }
        }
    }

    private fun replaceInput(input: String) {
        composeRule.onNode(hasSetTextAction()).performTextReplacement(input)
    }

    private fun text(resourceId: Int): String = context.getString(resourceId)

    private fun savePreviewScreenshot() {
        composeRule.waitForIdle()
        val screenshot = composeRule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(context.cacheDir, "playlist-insert-preview.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun demoSongs(): List<SongItem> {
        val titles = listOf("雨后散步", "星河漫游", "晨间列车", "橘色黄昏", "海风来信", "午夜回声")
        val artists = listOf("小岛", "青岚", "白川", "山野", "夏眠", "月见")
        val colors = listOf(0xFF8BAF91, 0xFF6976BC, 0xFF75A5B9, 0xFFD69562, 0xFF73A9A3, 0xFF947BAF)
        return titles.mapIndexed { index, title ->
            SongItem(
                id = index + 1L,
                name = title,
                artist = artists[index],
                album = "preview-test",
                albumId = 1L,
                durationMs = 180_000L,
                coverUrl = syntheticCover(index, colors[index].toInt())
            )
        }
    }

    private fun syntheticCover(index: Int, color: Int): String {
        val file = File(context.cacheDir, "playlist-insert-test-cover-$index.png")
        artworkFiles += file
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
        paint.alpha = 90
        canvas.drawCircle(96f, 28f, 30f, paint)
        paint.alpha = 180
        canvas.drawCircle(37f, 93f, 48f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.toURI().toString()
    }
}
