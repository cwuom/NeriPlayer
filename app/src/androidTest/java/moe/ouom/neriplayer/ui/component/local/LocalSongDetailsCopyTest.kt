package moe.ouom.neriplayer.ui.component.local

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarTestTag
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class LocalSongDetailsCopyTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var audio: File

    @Before
    fun prepare() {
        assumeComposeHostAvailable()
        audio = File.createTempFile("details-copy-", ".wav", context.cacheDir)
        val samples = ByteArray(16_000)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(samples.size + 36)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples.size).array()
        audio.writeBytes(header + samples)
    }

    @After
    fun cleanFixture() {
        if (::audio.isInitialized) audio.delete()
    }

    @Test fun materialLightCopiesEveryDisplayedFieldInsideDialog() = verifyCopy(UiStyle.Material, false)
    @Test fun materialDarkCopiesEveryDisplayedFieldInsideDialog() = verifyCopy(UiStyle.Material, true)
    @Test fun cleanLightCopiesEveryDisplayedFieldInsideDialog() = verifyCopy(UiStyle.Clean, false)
    @Test fun cleanDarkCopiesEveryDisplayedFieldInsideDialog() = verifyCopy(UiStyle.Clean, true)

    private fun verifyCopy(style: UiStyle, dark: Boolean) {
        var visible by mutableStateOf(true)
        val song = SongItem(
            id = 781L, name = "Copy track", artist = "Copy artist", album = "Copy album",
            albumId = 0L, durationMs = 1_000L, coverUrl = null,
            localFilePath = audio.absolutePath
        )
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false, forceDark = dark, dynamicColor = false,
                seedColorHex = "FFB59D", uiStyle = style, animateColors = false
            ) {
                Text("Details background")
                if (visible) LocalSongDetailsDialog(song, onDismiss = { visible = false })
            }
        }
        val titleLabel = context.getString(CoreCommonR.string.local_song_detail_title)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(titleLabel, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var copiedFields = 0
        for (labelId in FieldLabels) {
            val label = context.getString(labelId)
            if (composeRule.onAllNodesWithText(label, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()) continue
            val row = composeRule.onNodeWithText(label, useUnmergedTree = true)
                .performScrollTo().onParent().assertHasClickAction()
            val value = row.onChildren().fetchSemanticsNodes().last()
                .config[SemanticsProperties.Text].joinToString("") { it.text }
            row.performClick()
            composeRule.waitForIdle()
            instrumentation.runOnMainSync {
                assertEquals(value, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
            copiedFields++
        }
        assertTrue("the real WAV fixture must expose metadata and its file path", copiedFields >= 10)
        composeRule.onNodeWithTag(NeriSnackbarTestTag)
            .assertIsDisplayed().assert(hasAnyAncestor(isDialog()))
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm)).performClick()
        composeRule.onNodeWithTag(NeriSnackbarTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Details background").assertIsDisplayed()
    }

    private companion object {
        val FieldLabels = listOf(
            CoreCommonR.string.local_song_detail_display_name, CoreCommonR.string.local_song_detail_title,
            CoreCommonR.string.local_song_detail_artist, CoreCommonR.string.local_song_detail_album,
            CoreCommonR.string.local_song_detail_album_artist, CoreCommonR.string.local_song_detail_composer,
            CoreCommonR.string.local_song_detail_genre, CoreCommonR.string.local_song_detail_year,
            CoreCommonR.string.local_song_detail_track, CoreCommonR.string.local_song_detail_disc,
            CoreCommonR.string.local_song_detail_duration, CoreCommonR.string.local_song_detail_extension,
            CoreCommonR.string.local_song_detail_bitrate, CoreCommonR.string.local_song_detail_sample_rate,
            CoreCommonR.string.local_song_detail_channels, CoreCommonR.string.local_song_detail_bit_depth,
            CoreCommonR.string.local_song_detail_size, CoreCommonR.string.local_song_detail_mime,
            CoreCommonR.string.local_song_detail_audio_mime, CoreCommonR.string.local_song_detail_modified,
            CoreCommonR.string.local_song_detail_cover, CoreCommonR.string.local_song_detail_lyric,
            CoreCommonR.string.local_song_detail_has_lyrics, CoreCommonR.string.local_song_detail_path,
            CoreCommonR.string.local_song_detail_lyric_path, CoreCommonR.string.local_song_detail_uri
        )
    }
}
