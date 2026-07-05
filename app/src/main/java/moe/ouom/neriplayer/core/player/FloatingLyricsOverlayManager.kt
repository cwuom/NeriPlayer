package moe.ouom.neriplayer.core.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_ALIGNMENT_LEFT
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_ALIGNMENT_RIGHT
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_TRANSLATION_STYLE_SCALE
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsColorHex
import kotlin.math.roundToInt

@SuppressLint("StaticFieldLeak")
object FloatingLyricsOverlayManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var lyricTextView: AnimatedOutlinedLyricTextView? = null
    private var translationTextView: AnimatedOutlinedLyricTextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var preferences = FloatingLyricsPreferences()
    private var lyricLine: String? = null
    private var translationLine: String? = null
    private var pendingLyricLine: String? = null
    private var pendingTranslationLine: String? = null
    private var contentUpdateScheduled = false
    private val contentUpdateRunnable = Runnable {
        contentUpdateScheduled = false
        lyricLine = pendingLyricLine
        translationLine = pendingTranslationLine
        syncOverlay()
    }
    private var layoutUpdateScheduled = false
    private val layoutUpdateRunnable = Runnable {
        layoutUpdateScheduled = false
        layoutParams?.let(::applyStoredPosition)
        updateLayout()
    }
    private var startedActivityCount = 0

    fun initialize(app: Application) {
        if (application === app) {
            return
        }
        release()
        application = app
        windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount += 1
                syncOverlay()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                syncOverlay()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = syncOverlay()
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        callbacks = lifecycleCallbacks
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        syncOverlay()
    }

    fun updatePreferences(nextPreferences: FloatingLyricsPreferences) {
        runOnMain {
            preferences = nextPreferences.normalized()
            if (!shouldShowOverlay()) {
                removeOverlay()
                return@runOnMain
            }
            val needsInitialText = rootView == null
            ensureOverlay()
            updateOverlayStyle()
            if (needsInitialText) {
                updateOverlayText()
            }
        }
    }

    fun updateContent(line: String?, translation: String?) {
        runOnMain {
            pendingLyricLine = line?.trim()?.takeIf { it.isNotEmpty() }
            pendingTranslationLine = translation?.trim()?.takeIf { it.isNotEmpty() }
            scheduleContentUpdate()
        }
    }

    fun release() {
        runOnMain {
            removeOverlay()
            callbacks?.let { callback ->
                application?.unregisterActivityLifecycleCallbacks(callback)
            }
            callbacks = null
            application = null
            windowManager = null
            mainHandler.removeCallbacks(contentUpdateRunnable)
            mainHandler.removeCallbacks(layoutUpdateRunnable)
            contentUpdateScheduled = false
            layoutUpdateScheduled = false
            startedActivityCount = 0
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun openOverlayPermissionSettings(context: Context) {
        val packageUri = "package:${context.packageName}".toUri()
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallbackIntent)
        }
    }

    private fun syncOverlay() {
        runOnMain {
            if (!shouldShowOverlay()) {
                removeOverlay()
                return@runOnMain
            }
            ensureOverlay()
            updateOverlayStyle()
            updateOverlayText()
        }
    }

    private fun shouldShowOverlay(): Boolean {
        val app = application ?: return false
        if (!preferences.enabled || lyricLine.isNullOrBlank()) {
            return false
        }
        if (!hasOverlayPermission(app)) {
            return false
        }
        return !(preferences.hideInApp && startedActivityCount > 0)
    }

    private fun ensureOverlay() {
        if (rootView != null) {
            return
        }
        val app = application ?: return
        val manager = windowManager ?: return
        val title = AnimatedOutlinedLyricTextView(app)
        val translation = AnimatedOutlinedLyricTextView(app)
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, 0)
            addView(title, matchWidthLayoutParams())
            addView(translation, matchWidthLayoutParams())
        }
        lyricTextView = title
        translationTextView = translation
        rootView = root
        layoutParams = buildLayoutParams().also { params ->
            applyStoredPosition(params)
            manager.addView(root, params)
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            dp(preferences.maxWidthDp).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun updateOverlayText() {
        val nextLyric = lyricLine.orEmpty()
        val showTranslation = preferences.showTranslation && !translationLine.isNullOrBlank()
        val nextTranslation = if (showTranslation) translationLine.orEmpty() else ""
        val revealAnimationEnabled = preferences.revealAnimationEnabled
        val revealDurationMs = if (revealAnimationEnabled) {
            listOf(nextLyric, nextTranslation)
                .maxOf { AnimatedOutlinedLyricTextView.resolveRevealDurationMs(it) }
        } else {
            null
        }
        lyricTextView?.setLyricText(
            nextText = nextLyric,
            revealDurationMs = revealDurationMs,
            revealAnimationEnabled = revealAnimationEnabled
        )
        translationTextView?.apply {
            visibility = if (showTranslation) View.VISIBLE else View.GONE
            setLyricText(
                nextText = nextTranslation,
                revealDurationMs = revealDurationMs,
                revealAnimationEnabled = revealAnimationEnabled
            )
        }
    }

    private fun scheduleContentUpdate() {
        if (contentUpdateScheduled) {
            return
        }
        contentUpdateScheduled = true
        mainHandler.postDelayed(contentUpdateRunnable, CONTENT_UPDATE_COALESCE_MS)
    }

    private fun updateOverlayStyle() {
        val textColor = "#${normalizeFloatingLyricsColorHex(preferences.textColorHex)}".toColorInt()
        val outlineColor = (
            "#${normalizeFloatingLyricsColorHex(preferences.outlineColorHex)}"
        ).toColorInt()
        val alignmentFactor = when (preferences.alignment) {
            FLOATING_LYRICS_ALIGNMENT_LEFT -> 0f
            FLOATING_LYRICS_ALIGNMENT_RIGHT -> 1f
            else -> 0.5f
        }
        rootView?.background = null
        layoutParams?.width = dp(preferences.maxWidthDp).roundToInt()
        lyricTextView?.apply {
            setRevealAnimationEnabled(preferences.revealAnimationEnabled)
            setAlignmentFactor(alignmentFactor)
            setLyricStyle(
                textColor = withAlpha(textColor, preferences.lyricAlpha),
                outlineColor = withAlpha(outlineColor, preferences.lyricAlpha),
                textSizePx = sp(preferences.fontSizeSp),
                outlineWidthPx = dp(preferences.outlineWidthDp),
                bold = true
            )
        }
        translationTextView?.apply {
            setRevealAnimationEnabled(preferences.revealAnimationEnabled)
            setAlignmentFactor(alignmentFactor)
            setLyricStyle(
                textColor = withAlpha(textColor, preferences.translationAlpha),
                outlineColor = withAlpha(outlineColor, preferences.translationAlpha),
                textSizePx = sp(
                    (preferences.fontSizeSp * FLOATING_LYRICS_TRANSLATION_STYLE_SCALE)
                        .coerceAtLeast(7f)
                ),
                outlineWidthPx = dp(preferences.translationOutlineWidthDp),
                bold = false
            )
        }
        updateOverlayMinimumHeight()
        scheduleLayoutUpdate()
    }

    private fun updateOverlayMinimumHeight() {
        val lyricHeight = lyricTextView?.preferredMeasuredHeightPx() ?: 0
        val translationHeight = translationTextView?.preferredMeasuredHeightPx() ?: 0
        rootView?.minimumHeight = lyricHeight + translationHeight
    }

    private fun scheduleLayoutUpdate() {
        val view = rootView ?: return
        if (layoutUpdateScheduled) {
            return
        }
        layoutUpdateScheduled = true
        view.postOnAnimation(layoutUpdateRunnable)
    }

    private fun applyStoredPosition(params: WindowManager.LayoutParams) {
        val view = rootView ?: return
        val screen = resolveScreenSize()
        val viewWidth = resolveViewWidth(view)
        val viewHeight = resolveViewHeight(view)
        val verticalRange = resolveVerticalDragRange(screen, viewHeight)
        params.x = ((screen.x - viewWidth).coerceAtLeast(0) * preferences.positionX).roundToInt()
        params.y = verticalRange.first + (verticalRange.size * preferences.positionY).roundToInt()
        clampParamsToScreen(params)
    }

    private fun clampParamsToScreen(params: WindowManager.LayoutParams) {
        val view = rootView ?: return
        val screen = resolveScreenSize()
        val viewWidth = resolveViewWidth(view)
        val viewHeight = resolveViewHeight(view)
        val verticalRange = resolveVerticalDragRange(screen, viewHeight)
        params.x = params.x.coerceIn(0, (screen.x - viewWidth).coerceAtLeast(0))
        params.y = params.y.coerceIn(verticalRange.first, verticalRange.last)
    }

    private fun updateLayout() {
        val manager = windowManager ?: return
        val view = rootView ?: return
        val params = layoutParams ?: return
        runCatching { manager.updateViewLayout(view, params) }
    }

    private fun removeOverlay() {
        val manager = windowManager
        val view = rootView
        if (manager != null && view != null) {
            runCatching { manager.removeView(view) }
        }
        rootView = null
        lyricTextView = null
        translationTextView = null
        layoutParams = null
        mainHandler.removeCallbacks(contentUpdateRunnable)
        mainHandler.removeCallbacks(layoutUpdateRunnable)
        contentUpdateScheduled = false
        layoutUpdateScheduled = false
    }

    private fun resolveScreenSize(): Point {
        val manager = windowManager ?: return Point(1, 1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.currentWindowMetrics.bounds
            Point(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        } else {
            @Suppress("DEPRECATION")
            Point().also { manager.defaultDisplay.getRealSize(it) }
        }
    }

    private fun resolveVerticalDragRange(screen: Point, viewHeight: Int): IntRange {
        val minY = -resolveStatusBarInsetTop()
        val maxY = (screen.y - viewHeight).coerceAtLeast(minY + 1)
        return minY..maxY
    }

    private val IntRange.size: Int
        get() = (last - first).coerceAtLeast(1)

    private fun resolveViewWidth(view: View): Int {
        return view.width.takeIf { it > 0 }
            ?: layoutParams?.width?.takeIf { it > 0 }
            ?: dp(preferences.maxWidthDp).roundToInt()
    }

    private fun resolveViewHeight(view: View): Int {
        val currentHeight = view.height.coerceAtLeast(view.minimumHeight)
        return currentHeight.takeIf { it > 0 } ?: dp(48)
    }

    private fun resolveStatusBarInsetTop(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = windowManager ?: return 0
            manager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                .top
        } else {
            @Suppress("DEPRECATION")
            rootView?.rootWindowInsets?.systemWindowInsetTop ?: 0
        }
    }

    private fun matchWidthLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        return AndroidColor.argb(
            (alpha.coerceIn(0f, 1f) * 255).roundToInt(),
            AndroidColor.red(color),
            AndroidColor.green(color),
            AndroidColor.blue(color)
        )
    }

    private fun dp(value: Int): Int {
        val density = application?.resources?.displayMetrics?.density ?: 1f
        return (value * density).roundToInt()
    }

    private fun dp(value: Float): Float {
        val density = application?.resources?.displayMetrics?.density ?: 1f
        return value * density
    }

    private fun sp(value: Float): Float {
        val metrics = application?.resources?.displayMetrics
            ?: return value
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private const val CONTENT_UPDATE_COALESCE_MS = 16L
}
