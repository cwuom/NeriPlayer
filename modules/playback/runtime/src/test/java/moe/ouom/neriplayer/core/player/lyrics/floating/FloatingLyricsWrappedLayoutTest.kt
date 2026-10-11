package moe.ouom.neriplayer.core.player.lyrics.floating

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class FloatingLyricsWrappedLayoutTest {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = android.graphics.Color.WHITE
    }

    @Test
    fun `long multilingual sentences wrap into a bounded block`() {
        for (text in listOf(
            "这是一句很长的中文歌词，需要在较窄的窗口内换行展示。".repeat(4),
            "A long English sentence with spaces and emoji 🎵 🎶 ".repeat(4),
            "مرحبا بالعالم هذه كلمات أغنية طويلة ".repeat(4)
        )) {
            val layout = FloatingLyricsWrappedLayout(text, 180, paint, 0.5f)
            assertEquals(3, layout.lineCount)
            assertTrue(layout.heightPx > paint.fontSpacing.toInt())
        }
    }

    @Test
    fun `wider layout reduces height while explicit line breaks are respected`() {
        val text = "First lyric sentence and its continuation"
        val narrow = FloatingLyricsWrappedLayout(text, 160, paint, 0f)
        val wide = FloatingLyricsWrappedLayout(text, 1200, paint, 1f)
        assertTrue(narrow.heightPx > wide.heightPx)
        assertEquals(1, wide.lineCount)
        assertEquals(2, FloatingLyricsWrappedLayout("first\nsecond", 1200, paint, 0.5f).lineCount)
    }

    @Test
    fun `left and right remain physical for RTL LTR and mixed paragraphs`() {
        val width = 320
        val texts = listOf(
            "", "English lyric words", "مرحبا بالعالم", "שלום עולם",
            "English words\nمرحبا بالعالم\nשלום עולם",
            "مرحبا بالعالم\nEnglish words\nשלום עולם",
            "مرحبا بالعالم هذه كلمات أغنية طويلة ".repeat(4),
            "\nשלום עולם\n"
        )
        for (text in texts) {
            for (factor in listOf(0f, 0.5f, 1f)) {
                val wrapped = FloatingLyricsWrappedLayout(text, width, paint, factor)
                val layout = platformLayout(wrapped)
                for (line in 0 until layout.lineCount) {
                    if (layout.getLineLeft(line) == layout.getLineRight(line)) continue
                    when (factor) {
                        0f -> assertEquals("physical left: $text", 0f, layout.getLineLeft(line), 0.1f)
                        1f -> assertEquals("physical right: $text", width.toFloat(), layout.getLineRight(line), 0.1f)
                        else -> assertEquals(width.toFloat(), layout.getLineLeft(line) + layout.getLineRight(line), 0.1f)
                    }
                }
            }
        }
        val mixed = platformLayout(FloatingLyricsWrappedLayout("English\nمرحبا\nשלום", width, paint, 0f))
        assertEquals(1, mixed.getParagraphDirection(0))
        assertEquals(-1, mixed.getParagraphDirection(1))
        assertEquals(-1, mixed.getParagraphDirection(2))
    }

    @Test
    fun `drawing retains layout metrics and reveal clips content`() {
        val layout = FloatingLyricsWrappedLayout("歌词 preview words wrap here", 180, paint, 0.5f)
        val height = layout.heightPx
        val bitmap = Bitmap.createBitmap(200, height + 20, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        layout.clipReveal(canvas, 0f, 0f)
        layout.draw(canvas, paint)
        canvas.restore()
        assertTrue((0 until bitmap.height).all { y -> (0 until bitmap.width).all { x -> bitmap.getPixel(x, y) == 0 } })
        canvas.save()
        layout.clipReveal(canvas, 1f, 4f)
        layout.draw(canvas, paint)
        canvas.restore()
        assertTrue((0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) != 0 } })
        val outlined = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        repeat(10) { layout.draw(canvas, outlined) }
        assertEquals(height, layout.heightPx)
        bitmap.recycle()
    }

    private fun platformLayout(wrapped: FloatingLyricsWrappedLayout): Layout =
        FloatingLyricsWrappedLayout::class.java.getDeclaredField("layout")
            .apply { isAccessible = true }.get(wrapped) as Layout
}
