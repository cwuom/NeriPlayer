package moe.ouom.neriplayer.widget

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PlaybackWidgetVisualsTest {
    @Test
    fun artworkPalettesKeepReadableTextAndPlaybackIconsInBothThemes() {
        val seedColors = listOf(Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE)

        listOf(false, true).forEach { isDarkTheme ->
            seedColors.forEach { seedColor ->
                val artwork = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(seedColor)
                }
                val visuals = buildPlaybackWidgetVisuals(artwork, isDarkTheme)
                val caseLabel = "dark=$isDarkTheme seed=${Integer.toHexString(seedColor)}"

                assertReadableText(visuals, caseLabel)
                val primaryControl = requireNotNull(visuals.primaryControl)
                assertReadableContrast(
                    foreground = visuals.primaryControlTint,
                    background = primaryControl.getPixel(
                        primaryControl.width / 2,
                        primaryControl.height / 2,
                    ),
                    caseLabel = "$caseLabel playback icon",
                )
            }
        }
    }

    @Test
    fun emptyArtworkPalettesKeepReadableTextInBothThemes() {
        listOf(false, true).forEach { isDarkTheme ->
            val visuals = buildPlaybackWidgetVisuals(artwork = null, isDarkTheme = isDarkTheme)

            assertNull(visuals.artwork)
            assertNull(visuals.primaryControl)
            assertReadableText(visuals, "dark=$isDarkTheme empty artwork")
        }
    }

    @Test
    fun fallbackDrawableColorsKeepReadableTextAndPlaybackIconsInBothThemes() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext

        listOf(false, true).forEach { isDarkTheme ->
            val configuration = Configuration(baseContext.resources.configuration).apply {
                val nightMode = if (isDarkTheme) {
                    Configuration.UI_MODE_NIGHT_YES
                } else {
                    Configuration.UI_MODE_NIGHT_NO
                }
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
            val context = baseContext.createConfigurationContext(configuration)
            val background = drawableCenterColor(
                requireNotNull(context.getDrawable(R.drawable.widget_playback_background)),
            )
            val primaryControl = drawableCenterColor(
                requireNotNull(context.getDrawable(R.drawable.widget_primary_control_background)),
            )

            assertReadableContrast(
                foreground = context.getColor(R.color.widget_text_primary),
                background = background,
                caseLabel = "dark=$isDarkTheme fallback primary text",
            )
            assertReadableContrast(
                foreground = context.getColor(R.color.widget_text_secondary),
                background = background,
                caseLabel = "dark=$isDarkTheme fallback secondary text",
            )
            assertReadableContrast(
                foreground = context.getColor(R.color.widget_primary_control_icon),
                background = primaryControl,
                caseLabel = "dark=$isDarkTheme fallback playback icon",
            )
        }
    }

    @Test
    fun tintedThemeBackgroundDrawablesKeepRoundedCornersAtEveryShape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val sizesDp = listOf(250 to 56, 160 to 160, 320 to 180)

        listOf(false, true).forEach { isDarkTheme ->
            val visuals = buildPlaybackWidgetVisuals(artwork = null, isDarkTheme = isDarkTheme)
            sizesDp.forEach { (widthDp, heightDp) ->
                val drawable = requireNotNull(context.getDrawable(R.drawable.widget_playback_clip))
                drawable.colorFilter = PorterDuffColorFilter(
                    visuals.backgroundColor,
                    PorterDuff.Mode.SRC_ATOP,
                )
                val bitmap = renderDrawable(
                    drawable = drawable,
                    widthPx = (widthDp * density).roundToInt(),
                    heightPx = (heightDp * density).roundToInt(),
                )
                try {
                    assertRoundedEdges(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun fallbackBackgroundDrawablesKeepRoundedCornersAtEveryShape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density

        listOf(250 to 56, 160 to 160, 320 to 180).forEach { (widthDp, heightDp) ->
            val bitmap = renderDrawable(
                drawable = requireNotNull(context.getDrawable(R.drawable.widget_playback_background)),
                widthPx = (widthDp * density).roundToInt(),
                heightPx = (heightDp * density).roundToInt(),
            )
            try {
                assertRoundedEdges(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun compactArtworkKeepsTheCoverEdgesForAFullCardBackdrop() {
        val artwork = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(172, 95, 72))
        }
        val visuals = buildPlaybackWidgetVisuals(artwork)

        assertRoundedEdges(visuals.artwork)
        assertEquals(artwork.getPixel(0, 0), requireNotNull(visuals.compactArtwork).getPixel(0, 0))
    }

    @Test
    fun fullCoverBackdropsKeepHostAspectRatioAndRoundedCorners() {
        val artwork = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        listOf(PlaybackWidgetSize(110, 110), PlaybackWidgetSize(110, 180), PlaybackWidgetSize(300, 240))
            .forEach { size ->
                val backdrop = playbackWidgetBackdrop(artwork, size, 24f)
                assertEquals(size.widthDp.toFloat() / size.heightDp, backdrop.width.toFloat() / backdrop.height, 0.02f)
                assertRoundedEdges(backdrop)
            }
    }

    @Test
    fun roundedSurfacesKeepHostAspectRatioCenterColorAndTransparentCorners() {
        val color = Color.rgb(46, 78, 109)
        val sizes = listOf(
            PlaybackWidgetSize(110, 110),
            PlaybackWidgetSize(110, 180),
            PlaybackWidgetSize(300, 240),
            PlaybackWidgetSize(320, 56),
        )
        sizes.forEach { size ->
            val surface = playbackWidgetSurface(size, cornerRadiusDp = 24f, color = color)
            try {
                assertEquals(maxOf(size.widthDp, size.heightDp), maxOf(surface.width, surface.height))
                assertEquals(
                    "Host aspect ratio must stay within half a bitmap pixel",
                    size.widthDp * surface.height.toFloat(),
                    size.heightDp * surface.width.toFloat(),
                    maxOf(size.widthDp, size.heightDp) / 2f,
                )
                assertEquals(color, surface.getPixel(surface.width / 2, surface.height / 2))
                assertRoundedEdges(surface)
            } finally {
                surface.recycle()
            }
        }
    }

    @Test
    fun surfaceAndBackdropUseDisplayDensityWithoutExceedingTheBitmapCap() {
        val artwork = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val cases = listOf(
            Triple(PlaybackWidgetSize(300, 180), 900, 540),
            Triple(PlaybackWidgetSize(600, 480), 1024, 819),
        )
        try {
            cases.forEach { (size, widthPx, heightPx) ->
                val surface = playbackWidgetSurface(size, cornerRadiusDp = 24f, color = Color.WHITE, renderScale = 3f)
                val backdrop = playbackWidgetBackdrop(artwork, size, cornerRadiusDp = 24f, renderScale = 3f)
                try {
                    assertEquals(widthPx, surface.width)
                    assertEquals(heightPx, surface.height)
                    assertEquals(widthPx, backdrop.width)
                    assertEquals(heightPx, backdrop.height)
                    assertTrue("Surface and cover must share the density-scaled mask", surface.sameAs(backdrop))
                    assertRoundedEdges(surface)
                } finally {
                    surface.recycle()
                    backdrop.recycle()
                }
            }
        } finally {
            artwork.recycle()
        }
    }

    @Test
    fun roundedCornersRespectTheRadiusAndShareTheBackdropMask() {
        val size = PlaybackWidgetSize(256, 256)
        val artwork = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val surface = playbackWidgetSurface(size, cornerRadiusDp = 40f, color = Color.WHITE)
        val backdrop = playbackWidgetBackdrop(artwork, size, cornerRadiusDp = 40f)
        val circular = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawRoundRect(
                RectF(0f, 0f, 256f, 256f),
                40f, 40f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE },
            )
        }
        val visuals = buildPlaybackWidgetVisuals(artwork)
        try {
            assertTrue("Surface and backdrop must use the same corner mask", surface.sameAs(backdrop))
            listOf(9 to 9, 246 to 9, 9 to 246, 246 to 246).forEach { (x, y) ->
                assertEquals("Widget corners must match the configured circular radius", 0,
                    Color.alpha(surface.getPixel(x, y)))
                assertEquals("Circular arc must exclude ($x, $y)", 0, Color.alpha(circular.getPixel(x, y)))
            }
            assertEquals("The straight edge starts at the configured radius", 255,
                Color.alpha(surface.getPixel(40, 0)))
            assertEquals("The vertical edge starts at the configured radius", 255,
                Color.alpha(surface.getPixel(0, 40)))
            assertTrue(
                "Artwork must use the same circular rounding",
                Color.alpha(requireNotNull(visuals.artwork).getPixel(6, 6)) == 0,
            )
        } finally {
            surface.recycle()
            backdrop.recycle()
            circular.recycle()
            visuals.artwork?.recycle()
            visuals.compactArtwork?.recycle()
            visuals.primaryControl?.recycle()
            artwork.recycle()
        }
    }

    @Test
    fun cornerRadiusDoesNotExceedHalfTheShortestEdge() {
        val size = PlaybackWidgetSize(256, 128)
        val oversized = playbackWidgetSurface(size, cornerRadiusDp = 1_000f, color = Color.WHITE)
        val bounded = playbackWidgetSurface(size, cornerRadiusDp = size.heightDp / 2f, color = Color.WHITE)
        try {
            assertTrue("Oversized radii must stop growing at the shortest edge", oversized.sameAs(bounded))
            assertRoundedEdges(oversized)
        } finally {
            oversized.recycle()
            bounded.recycle()
        }
    }

    @Test
    fun miniBackdropScrimPreservesCornersAndWhiteTextContrast() {
        val artwork = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val size = PlaybackWidgetSize(240, 180)
        val plain = playbackWidgetBackdrop(artwork, size, cornerRadiusDp = 24f)
        val shaded = playbackWidgetBackdrop(artwork, size, cornerRadiusDp = 24f, applyScrim = true)
        try {
            assertEquals(Color.WHITE, plain.getPixel(plain.width / 2, plain.height / 2))
            val top = shaded.getPixel(shaded.width / 2, 1)
            val bottom = shaded.getPixel(shaded.width / 2, shaded.height - 2)
            assertTrue("Top scrim must apply the 60 percent black overlay", Color.red(top) in 100..103)
            assertTrue("Bottom scrim must apply the 70 percent black overlay", Color.red(bottom) in 75..78)
            assertReadableContrast(Color.WHITE, top, "mini top scrim")
            assertReadableContrast(Color.WHITE, bottom, "mini bottom scrim")
            assertRoundedEdges(shaded)
        } finally {
            plain.recycle()
            shaded.recycle()
            artwork.recycle()
        }
    }

    @Test
    fun transparentMiniCoversKeepAnOpaqueScrimAndReadableWhiteText() {
        val size = PlaybackWidgetSize(180, 110)
        val coverColors = listOf(Color.TRANSPARENT, Color.argb(128, 255, 0, 0))
        coverColors.forEach { coverColor ->
            val artwork = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(coverColor)
            }
            val plain = playbackWidgetBackdrop(artwork, size, cornerRadiusDp = 24f, renderScale = 3f)
            val shaded = playbackWidgetBackdrop(
                artwork, size, cornerRadiusDp = 24f, applyScrim = true, renderScale = 3f,
            )
            try {
                val center = plain.getPixel(plain.width / 2, plain.height / 2)
                val expected = ColorUtils.compositeColors(coverColor, Color.WHITE)
                assertEquals("Transparent cover must retain an opaque base", 255, Color.alpha(center))
                assertEquals(Color.red(expected).toFloat(), Color.red(center).toFloat(), 1f)
                assertEquals(Color.green(expected).toFloat(), Color.green(center).toFloat(), 1f)
                assertEquals(Color.blue(expected).toFloat(), Color.blue(center).toFloat(), 1f)
                listOf(1, shaded.height / 2, shaded.height - 2).forEach { y ->
                    val background = shaded.getPixel(shaded.width / 2, y)
                    assertEquals("Mini scrim must stay opaque inside the card", 255, Color.alpha(background))
                    assertReadableContrast(Color.WHITE, background, "transparent mini cover at y=$y")
                }
                assertRoundedEdges(plain)
                assertRoundedEdges(shaded)
            } finally {
                plain.recycle()
                shaded.recycle()
                artwork.recycle()
            }
        }
    }

    @Test
    fun artworkColorsRemainVisibleInsteadOfBeingWashedOut() {
        val artwork = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val visuals = buildPlaybackWidgetVisuals(artwork)
        val hsv = FloatArray(3)
        Color.colorToHSV(visuals.backgroundColor, hsv)
        assertTrue("Card should retain the artwork color", hsv[1] >= 0.20f)
        val button = requireNotNull(visuals.primaryControl)
        Color.colorToHSV(button.getPixel(button.width / 2, button.height / 2), hsv)
        assertTrue("Button should retain the artwork color", hsv[1] >= 0.45f)
    }

    @Test
    fun fallbackBackgroundIsHiddenUntilTheUpdaterNeedsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inflater = LayoutInflater.from(context)

        listOf(R.layout.widget_playback_2x2, R.layout.widget_playback_4x2).forEach { layoutRes ->
            val root = inflater.inflate(layoutRes, null)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_fallback_background).visibility)
        }
    }

    private fun assertReadableText(visuals: PlaybackWidgetVisuals, caseLabel: String) {
        assertReadableContrast(
            foreground = visuals.textPrimary,
            background = visuals.backgroundColor,
            caseLabel = "$caseLabel primary text",
        )
        assertReadableContrast(
            foreground = visuals.textSecondary,
            background = visuals.backgroundColor,
            caseLabel = "$caseLabel secondary text",
        )
    }

    private fun assertReadableContrast(foreground: Int, background: Int, caseLabel: String) {
        val contrast = ColorUtils.calculateContrast(foreground, background)

        assertTrue("$caseLabel contrast=$contrast must be at least 4.5", contrast >= 4.5)
    }

    private fun drawableCenterColor(drawable: Drawable): Int {
        val bitmap = renderDrawable(drawable, widthPx = 64, heightPx = 64)
        return try {
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderDrawable(drawable: Drawable, widthPx: Int, heightPx: Int): Bitmap {
        return Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, widthPx, heightPx)
            drawable.draw(Canvas(bitmap))
        }
    }

    private fun assertRoundedEdges(bitmap: Bitmap?) {
        assertNotNull(bitmap)
        val image = requireNotNull(bitmap)

        assertEquals(0, Color.alpha(image.getPixel(0, 0)))
        assertEquals(0, Color.alpha(image.getPixel(image.width - 1, 0)))
        assertEquals(0, Color.alpha(image.getPixel(0, image.height - 1)))
        assertEquals(0, Color.alpha(image.getPixel(image.width - 1, image.height - 1)))
        assertEquals(255, Color.alpha(image.getPixel(image.width / 2, 1)))
        assertEquals(255, Color.alpha(image.getPixel(image.width / 2, image.height - 2)))
        assertEquals(255, Color.alpha(image.getPixel(1, image.height / 2)))
        assertEquals(255, Color.alpha(image.getPixel(image.width - 2, image.height / 2)))
    }
}
