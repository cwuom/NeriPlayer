package moe.ouom.neriplayer.core.player.lyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.withSave
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

internal class AnimatedOutlinedLyricTextView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val smoothInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val linearInterpolator = LinearInterpolator()

    private var lyricText = ""
    private var revealProgress = 1f
    private var alignmentFactor = 0.5f
    private var targetAlignmentFactor = 0.5f
    private var scrollOffset = 0f
    private var revealAnimator: ValueAnimator? = null
    private var alignmentAnimator: ValueAnimator? = null
    private var scrollAnimator: ValueAnimator? = null

    init {
        fillPaint.style = Paint.Style.FILL
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.strokeCap = Paint.Cap.ROUND
        setPadding(dp(3), 0, dp(3), 0)
    }

    fun setLyricText(
        nextText: String,
        revealDurationMs: Long? = null,
        revealAnimationEnabled: Boolean = true
    ) {
        if (lyricText == nextText) {
            return
        }
        lyricText = nextText
        scrollOffset = 0f
        requestLayout()
        if (revealAnimationEnabled) {
            startRevealAnimation(revealDurationMs)
        } else {
            showTextWithoutReveal()
        }
    }

    fun setLyricStyle(
        textColor: Int,
        outlineColor: Int,
        textSizePx: Float,
        outlineWidthPx: Float,
        bold: Boolean
    ) {
        val typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        val outlineWidth = outlineWidthPx.coerceAtLeast(0f)
        val horizontalPadding = ceil(outlineWidth + dp(1)).toInt()
        val verticalPadding = ceil(outlineWidth * 0.5f).toInt()
        fillPaint.color = textColor
        fillPaint.textSize = textSizePx
        fillPaint.typeface = typeface
        outlinePaint.color = outlineColor
        outlinePaint.textSize = textSizePx
        outlinePaint.typeface = typeface
        outlinePaint.strokeWidth = outlineWidth
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        requestLayout()
        invalidate()
        restartScrollAfterLayout()
    }

    fun setAlignmentFactor(nextFactor: Float) {
        val normalized = nextFactor.coerceIn(0f, 1f)
        if (abs(targetAlignmentFactor - normalized) < 0.001f) {
            return
        }
        targetAlignmentFactor = normalized
        alignmentAnimator?.cancel()
        alignmentAnimator = ValueAnimator.ofFloat(alignmentFactor, normalized).apply {
            var canceled = false
            duration = 420L
            interpolator = smoothInterpolator
            addUpdateListener { animator ->
                alignmentFactor = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        alignmentFactor = normalized
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    fun setRevealAnimationEnabled(enabled: Boolean) {
        if (!enabled && revealProgress < 1f) {
            showTextWithoutReveal()
        }
    }

    fun preferredMeasuredHeightPx(): Int {
        val fontMetrics = fillPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        return ceil(textHeight + outlinePaint.strokeWidth + paddingTop + paddingBottom)
            .toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = fillPaint.measureText(lyricText).takeIf { it > 0f } ?: 1f
        val fontMetrics = fillPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val desiredWidth = ceil(textWidth + outlinePaint.strokeWidth * 2f + paddingLeft + paddingRight)
            .toInt()
        val desiredHeight = ceil(textHeight + outlinePaint.strokeWidth + paddingTop + paddingBottom)
            .toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lyricText.isBlank()) {
            return
        }
        if (revealProgress <= 0f) {
            return
        }
        val contentLeft = paddingLeft + outlinePaint.strokeWidth
        val contentRight = width - paddingRight - outlinePaint.strokeWidth
        val contentWidth = (contentRight - contentLeft).coerceAtLeast(1f)
        val fullTextWidth = fillPaint.measureText(lyricText)
        val baseX = if (fullTextWidth <= contentWidth) {
            contentLeft + (contentWidth - fullTextWidth) * alignmentFactor
        } else {
            contentLeft - scrollOffset
        }
        val fontMetrics = fillPaint.fontMetrics
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val baseline = paddingTop + (contentHeight - textHeight) / 2f - fontMetrics.ascent

        val revealLeft = if (revealProgress >= 1f) {
            contentLeft
        } else {
            (baseX - outlinePaint.strokeWidth).coerceIn(contentLeft, contentRight)
        }
        val revealRight = if (revealProgress >= 1f) {
            contentRight
        } else {
            (baseX + fullTextWidth * revealProgress + outlinePaint.strokeWidth)
                .coerceIn(contentLeft, contentRight)
        }
        if (revealRight <= revealLeft) {
            return
        }
        canvas.withSave {
            clipRect(revealLeft, 0f, revealRight, height.toFloat())
            if (outlinePaint.strokeWidth > 0f) {
                drawText(lyricText, baseX, baseline, outlinePaint)
            }
            drawText(lyricText, baseX, baseline, fillPaint)
        }
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        super.onDetachedFromWindow()
    }

    private fun startRevealAnimation(durationMs: Long? = null) {
        revealAnimator?.cancel()
        scrollAnimator?.cancel()
        scrollAnimator = null
        revealProgress = if (lyricText.isBlank()) 1f else 0f
        invalidate()
        if (lyricText.isBlank()) {
            return
        }
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            var canceled = false
            duration = durationMs ?: resolveRevealDurationMs(lyricText)
            interpolator = linearInterpolator
            addUpdateListener { animator ->
                revealProgress = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        revealProgress = 1f
                        restartScrollAfterLayout()
                    }
                }
            })
            start()
        }
    }

    private fun showTextWithoutReveal() {
        revealAnimator?.cancel()
        scrollAnimator?.cancel()
        scrollAnimator = null
        revealProgress = 1f
        invalidate()
        restartScrollAfterLayout()
    }

    companion object {
        fun resolveRevealDurationMs(text: String): Long {
            return (text.length * 36L).coerceIn(360L, 1450L)
        }
    }

    private fun restartScrollAfterLayout() {
        if (revealProgress < 1f || lyricText.isBlank()) {
            return
        }
        scrollAnimator?.cancel()
        scrollAnimator = null
        scrollOffset = 0f
        invalidate()
        post { startScrollIfNeeded() }
    }

    private fun startScrollIfNeeded() {
        if (width <= 0 || lyricText.isBlank()) {
            return
        }
        val contentWidth = (width - paddingLeft - paddingRight - outlinePaint.strokeWidth * 2f)
            .coerceAtLeast(1f)
        val overflow = (fillPaint.measureText(lyricText) - contentWidth).coerceAtLeast(0f)
        if (overflow <= dp(2)) {
            return
        }
        val pxPerSecond = dp(38).coerceAtLeast(1).toFloat()
        val durationMs = ((overflow / pxPerSecond) * 1000f)
            .roundToLong()
            .coerceIn(2200L, 9000L)
        scrollAnimator = ValueAnimator.ofFloat(0f, overflow).apply {
            startDelay = 520L
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = linearInterpolator
            addUpdateListener { animator ->
                scrollOffset = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun cancelAnimations() {
        revealAnimator?.cancel()
        alignmentAnimator?.cancel()
        scrollAnimator?.cancel()
        revealAnimator = null
        alignmentAnimator = null
        scrollAnimator = null
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToLong().toInt()
    }
}
