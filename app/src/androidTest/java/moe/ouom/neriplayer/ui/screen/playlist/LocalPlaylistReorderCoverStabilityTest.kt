package moe.ouom.neriplayer.ui.screen.playlist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.playlist.reorder.rememberLocalPlaylistReorderCoverPlaylist
import moe.ouom.neriplayer.ui.util.rememberPlaylistDisplayCoverUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalPlaylistReorderCoverStabilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val fixture = mutableStateOf<CoverFixture?>(null)
    private val samples = mutableListOf<CoverSample>()
    private lateinit var files: List<File>
    private lateinit var songs: List<SongItem>
    private var ownedDirectory: File? = null
    private var mounted = false
    @Volatile private var resolvedCover: String? = null

    @Before
    fun createOwnedCovers() {
        assumeComposeHostAvailable()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "playlist-reorder-cover-${UUID.randomUUID()}")
        check(directory.mkdirs())
        ownedDirectory = directory
        files = listOf(AndroidColor.RED, AndroidColor.GREEN, AndroidColor.BLUE, AndroidColor.YELLOW)
            .mapIndexed { index, color ->
                File(directory, "cover-$index.png").also { file ->
                    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(color)
                        file.outputStream().use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        songs = files.mapIndexed { index, file ->
            SongItem(
                id = System.nanoTime() + index,
                name = "cover song $index",
                artist = "cover fixture",
                album = "cover fixture",
                albumId = 0,
                durationMs = 60_000,
                coverUrl = file.absolutePath
            )
        }
    }

    @After
    fun removeOwnedCovers() {
        if (mounted) {
            composeRule.runOnIdle { fixture.value = null }
            composeRule.waitForIdle()
        }
        ownedDirectory?.deleteRecursively()
    }

    @Test
    fun holdingReorderKeepsTheCoverUntilTheSavedOrderIsAcknowledged() {
        showCover()
        assertCover(files[0], AndroidColor.RED)
        updateFixture { it.copy(phase = Phase.Holding) }
        val holdingPixels = mutableListOf<Int>()
        listOf(
            listOf(songs[0], songs[2], songs[1]),
            listOf(songs[1], songs[0], songs[2]),
            listOf(songs[2], songs[1], songs[0]),
            listOf(songs[1], songs[2], songs[0])
        ).forEach { order ->
            updateFixture { it.copy(displaySongs = order) }
            holdingPixels += currentPixel()
        }
        updateFixture { it.copy(phase = Phase.AwaitingSave) }
        val waitingPixel = currentPixel()
        updateFixture {
            it.copy(
                playlist = it.playlist.copy(
                    songs = it.displaySongs.toMutableList(),
                    modifiedAt = it.playlist.modifiedAt + 1
                ),
                phase = Phase.Saved
            )
        }
        assertCover(files[1], AndroidColor.GREEN)

        val frozenSamples = composeRule.runOnIdle {
            samples.filter { it.phase == Phase.Holding || it.phase == Phase.AwaitingSave }
        }
        assertTrue("the holding and pending-save phases were not rendered", frozenSamples.isNotEmpty())
        assertTrue(
            "cover changed while the order was transient: samples=$frozenSamples, " +
                "pixels=$holdingPixels, waitingPixel=$waitingPixel",
            frozenSamples.all { it.url == files[0].absolutePath } &&
                holdingPixels.all { it == AndroidColor.RED } && waitingPixel == AndroidColor.RED
        )
    }

    @Test
    fun idleSongMembershipTabAndCustomCoverChangesStillRefreshTheCover() {
        showCover()
        assertCover(files[0], AndroidColor.RED)
        updateFixture {
            val next = listOf(songs[3]) + it.displaySongs
            it.copy(
                playlist = it.playlist.copy(songs = next.toMutableList(), modifiedAt = it.playlist.modifiedAt + 1),
                displaySongs = next
            )
        }
        assertCover(files[3], AndroidColor.YELLOW)
        updateFixture {
            val next = listOf(songs[1], songs[0])
            it.copy(
                playlist = it.playlist.copy(songs = next.toMutableList(), modifiedAt = it.playlist.modifiedAt + 1),
                displaySongs = next
            )
        }
        assertCover(files[1], AndroidColor.GREEN)
        updateFixture { it.copy(displaySongs = listOf(songs[2]), tabKey = "downloads") }
        assertCover(files[2], AndroidColor.BLUE)
        updateFixture { it.copy(playlist = it.playlist.copy(customCoverUrl = files[0].absolutePath)) }
        assertCover(files[0], AndroidColor.RED)
    }

    @Test
    fun holdingReorderDoesNotHideCustomCoverMembershipOrTabChanges() {
        showCover()
        assertCover(files[0], AndroidColor.RED)
        updateFixture { it.copy(phase = Phase.Holding) }
        updateFixture { it.copy(playlist = it.playlist.copy(customCoverUrl = files[1].absolutePath)) }
        assertCover(files[1], AndroidColor.GREEN)
        updateFixture {
            val next = listOf(songs[2], songs[0])
            it.copy(
                playlist = it.playlist.copy(
                    customCoverUrl = null,
                    songs = next.toMutableList(),
                    modifiedAt = it.playlist.modifiedAt + 1
                ),
                displaySongs = next
            )
        }
        assertCover(files[2], AndroidColor.BLUE)
        updateFixture { it.copy(displaySongs = listOf(songs[1]), tabKey = "downloads") }
        assertCover(files[1], AndroidColor.GREEN)
    }

    private fun showCover() {
        val initialSongs = songs.take(3)
        fixture.value = CoverFixture(
            playlist = LocalPlaylist(System.nanoTime(), "cover stability", initialSongs.toMutableList()),
            displaySongs = initialSongs
        )
        composeRule.setContent {
            fixture.value?.let { current ->
                val coverPlaylist = rememberLocalPlaylistReorderCoverPlaylist(
                    playlist = current.playlist,
                    displayedSongs = current.displaySongs,
                    tabKey = current.tabKey,
                    freezeOrder = current.phase == Phase.Holding || current.phase == Phase.AwaitingSave
                )
                val url = rememberPlaylistDisplayCoverUrl(coverPlaylist)
                SideEffect {
                    resolvedCover = url
                    samples += CoverSample(current.phase, url)
                }
                val bitmap = remember(url) { url?.let(BitmapFactory::decodeFile) }
                Box(Modifier.size(64.dp).testTag(CoverTag).background(Color.Magenta)) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        mounted = true
        waitForResolvedCover()
    }

    private fun updateFixture(update: (CoverFixture) -> CoverFixture) {
        composeRule.runOnIdle { fixture.value = update(checkNotNull(fixture.value)) }
        waitForResolvedCover()
    }

    private fun waitForResolvedCover() {
        composeRule.waitForIdle()
        composeRule.waitUntil(UiTimeoutMs) { resolvedCover != null }
        composeRule.waitForIdle()
    }

    private fun assertCover(file: File, color: Int) {
        composeRule.waitUntil(UiTimeoutMs) { resolvedCover == file.absolutePath }
        composeRule.waitForIdle()
        assertEquals(file.absolutePath, resolvedCover)
        assertEquals("the rendered cover color did not match the resolved file", color, currentPixel())
    }

    private fun currentPixel(): Int {
        val image = composeRule.onNodeWithTag(CoverTag).captureToImage().asAndroidBitmap()
        return image.getPixel(image.width / 2, image.height / 2)
    }

    private data class CoverFixture(
        val playlist: LocalPlaylist,
        val displaySongs: List<SongItem>,
        val phase: Phase = Phase.Idle,
        val tabKey: String = "manual"
    )

    private data class CoverSample(val phase: Phase, val url: String?)
    private enum class Phase { Idle, Holding, AwaitingSave, Saved }

    private companion object {
        const val CoverTag = "playlist-reorder-cover"
        const val UiTimeoutMs = 10_000L
    }
}
