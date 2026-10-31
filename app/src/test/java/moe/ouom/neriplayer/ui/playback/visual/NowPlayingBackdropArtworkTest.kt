package moe.ouom.neriplayer.ui.playback.visual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.ImageLoader
import java.io.File
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingBackdropArtworkTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var hostView: View

    @Test
    fun knownEmptyArtworkRemovesMountedRetainedBlurAndDoesNotMountTheDynamicRenderer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "backdrop-clear-${System.nanoTime()}.png")
        val sourceBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            sourceBitmap.eraseColor(Color.RED)
            file.outputStream().use { check(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            sourceBitmap.recycle()
        }
        val oldCoverUrl = Uri.fromFile(file).toString()
        val savedLoader = Coil.imageLoader(context)
        val loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null).build()
        val mounted = mutableStateOf(true)
        val backdropLifecycle = composeRule.runOnIdle {
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
            }
        }
        val restored = SongItem(
            id = 74_129L,
            name = "Backdrop clear",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 60_000L,
            coverUrl = null,
            mediaUri = "content://backdrop-clear/audio/74129",
            originalCoverUrl = "",
            channelId = "local",
            audioId = "74129"
        )
        val blurState = NowPlayingBlurState().apply {
            onImageSuccess("edited", "edited", oldCoverUrl, 0f)
        }
        val pendingCover = NowPlayingOverlayCover(null, "backdrop-clear-song", restored.copy(originalCoverUrl = null), 0)
        val frame = mutableStateOf(
            NowPlayingBackdropFrame(
                pendingCover,
                NowPlayingOverlayBackground(true, 0f, 0f, true, true),
                blurState,
                hasCoverBlur = true,
                blurStrength = 0f,
                imageSizePx = 96,
                assetVersion = "backdrop-clear",
                requestKey = null,
                latestRequestKey = null
            )
        )
        try {
            Coil.setImageLoader(loader)
            composeRule.setContent {
                hostView = LocalView.current
                if (mounted.value) {
                    CompositionLocalProvider(LocalLifecycleOwner provides backdropLifecycle) {
                        Box(Modifier.size(96.dp).testTag("backdrop-clear")) {
                            NowPlayingBackdropLayers(frame.value)
                        }
                    }
                }
            }
            val bounds = composeRule.onNodeWithTag("backdrop-clear").fetchSemanticsNode().boundsInRoot
            val x = bounds.center.x.toInt()
            val y = bounds.center.y.toInt()
            composeRule.waitUntil(timeoutMillis = 10_000L) {
                composeRule.runOnIdle { samplePixel(x, y) == Color.RED }
            }
            val baselineViews = composeRule.runOnIdle { plainViews(hostView).toSet() }
            composeRule.runOnIdle {
                assertEquals("a pending URL must keep the actually decoded backdrop", Color.RED, samplePixel(x, y))
                frame.value = frame.value.copy(cover = pendingCover.copy(url = oldCoverUrl, song = restored))
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertNotEquals("explicitly empty artwork must stop painting retained blur", Color.RED, samplePixel(x, y))
                assertEquals("the dynamic renderer must not replace the cleared blur with old artwork", baselineViews, plainViews(hostView).toSet())
                frame.value = frame.value.copy(
                    hasCoverBlur = false,
                    background = frame.value.background.copy(blurEnabled = false)
                )
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals("explicitly empty artwork also suppresses the standalone dynamic layer", baselineViews, plainViews(hostView).toSet())
                assertNotEquals(Color.RED, samplePixel(x, y))
                frame.value = frame.value.copy(cover = pendingCover.copy(url = oldCoverUrl))
            }
            composeRule.waitForIdle()
            val renderer = composeRule.runOnIdle {
                val addedViews = plainViews(hostView).filterNot { it in baselineViews }
                assertEquals("the ordinary dynamic background must mount one real renderer", 1, addedViews.size)
                addedViews.single().also {
                    frame.value = frame.value.copy(cover = pendingCover.copy(url = oldCoverUrl, song = restored))
                }
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals("restoring no artwork must remove the previously mounted renderer", baselineViews, plainViews(hostView).toSet())
                assertFalse("the old renderer must be detached so it cannot paint the removed artwork", renderer.isAttachedToWindow)
                assertNotEquals(Color.RED, samplePixel(x, y))
            }
        } finally {
            try {
                composeRule.runOnIdle {
                    mounted.value = false
                    backdropLifecycle.lifecycle.currentState = Lifecycle.State.DESTROYED
                }
                composeRule.waitForIdle()
            } finally {
                Coil.setImageLoader(savedLoader)
                loader.shutdown()
                check(file.delete())
            }
        }
    }

    private fun samplePixel(x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
        return try {
            hostView.draw(Canvas(bitmap))
            bitmap.getPixel(x, y)
        } finally {
            bitmap.recycle()
        }
    }

    private fun plainViews(view: View): List<View> = buildList {
        if (view.javaClass == View::class.java) add(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) addAll(plainViews(view.getChildAt(index)))
        }
    }
}
