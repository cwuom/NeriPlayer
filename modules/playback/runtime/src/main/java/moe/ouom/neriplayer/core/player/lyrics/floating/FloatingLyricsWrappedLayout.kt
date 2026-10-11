package moe.ouom.neriplayer.core.player.lyrics.floating

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AlignmentSpan

/** Platform shaping and line breaking are computed once per text, style, alignment or width change. */
internal class FloatingLyricsWrappedLayout(
    text: String,
    widthPx: Int,
    sourcePaint: Paint,
    alignmentFactor: Float
) {
    private val paint = TextPaint(sourcePaint)
    private val revealPath = Path()
    private val layout = StaticLayout.Builder.obtain(
        physicallyAlignedText(text, alignmentFactor), 0, text.length, paint, widthPx.coerceAtLeast(1)
    )
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
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

private fun physicallyAlignedText(text: String, alignmentFactor: Float): CharSequence {
    if (text.isEmpty()) return text
    val physicalDirection = when {
        alignmentFactor < 0.25f -> Layout.DIR_LEFT_TO_RIGHT
        alignmentFactor > 0.75f -> Layout.DIR_RIGHT_TO_LEFT
        else -> return text
    }
    val result = SpannableString(text)
    var start = 0
    while (start < text.length) {
        val newline = text.indexOf('\n', start)
        val end = if (newline < 0) text.length else newline + 1
        val paragraphDirection = if (TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, start, end - start)) {
            Layout.DIR_RIGHT_TO_LEFT
        } else {
            Layout.DIR_LEFT_TO_RIGHT
        }
        // Normal/opposite are paragraph-relative, while the setting specifies physical left/right.
        val alignment = if (physicalDirection == paragraphDirection) {
            Layout.Alignment.ALIGN_NORMAL
        } else {
            Layout.Alignment.ALIGN_OPPOSITE
        }
        result.setSpan(AlignmentSpan.Standard(alignment), start, end, Spanned.SPAN_PARAGRAPH)
        start = end
    }
    return result
}
