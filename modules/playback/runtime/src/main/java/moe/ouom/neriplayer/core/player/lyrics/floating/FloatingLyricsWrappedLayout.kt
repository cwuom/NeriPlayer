package moe.ouom.neriplayer.core.player.lyrics.floating

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/** Platform shaping and line breaking are computed once per text, style, alignment or width change. */
internal class FloatingLyricsWrappedLayout(
    text: String,
    widthPx: Int,
    sourcePaint: Paint,
    alignmentFactor: Float
) {
    private val paint = TextPaint(sourcePaint)
    private val revealPath = Path()
    private val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
        .setAlignment(when {
            alignmentFactor < 0.25f -> Layout.Alignment.ALIGN_NORMAL
            alignmentFactor > 0.75f -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        })
        .setIncludePad(false)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setMaxLines(MAX_LINES)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

    val heightPx: Int get() = layout.height
    val lineCount: Int get() = layout.lineCount

    fun clipReveal(canvas: Canvas, progress: Float, effectExtentPx: Float) {
        revealPath.rewind()
        for (line in 0 until layout.lineCount) {
            val fraction = (progress * layout.lineCount - line).coerceIn(0f, 1f)
            if (fraction <= 0f) break
            val left = layout.getLineLeft(line)
            val right = layout.getLineRight(line)
            val isRtl = layout.getParagraphDirection(line) < 0
            revealPath.addRect(
                if (isRtl) right - (right - left) * fraction - effectExtentPx else left - effectExtentPx,
                layout.getLineTop(line).toFloat() - effectExtentPx,
                if (isRtl) right + effectExtentPx else left + (right - left) * fraction + effectExtentPx,
                layout.getLineBottom(line).toFloat() + effectExtentPx,
                Path.Direction.CW
            )
        }
        canvas.clipPath(revealPath)
    }

    fun draw(canvas: Canvas, sourcePaint: Paint) {
        paint.set(sourcePaint)
        layout.draw(canvas)
    }

    companion object {
        // A bounded block keeps narrow windows and malformed long lyrics from growing without limit.
        const val MAX_LINES = 3
    }
}
