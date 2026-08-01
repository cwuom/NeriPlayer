package moe.ouom.neriplayer.activity.auth

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliQrLoginClient
import moe.ouom.neriplayer.core.api.bili.BiliQrLoginSession
import moe.ouom.neriplayer.data.auth.web.ForegroundWebLoginGuard
import moe.ouom.neriplayer.data.auth.web.shouldAutoCompleteBiliWebLogin
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.platform.lockPortraitIfPhone
import org.json.JSONObject
import kotlin.math.roundToInt

class BiliQrLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = BiliWebLoginActivity.RESULT_COOKIE
        private const val LOG_TAG = "NERI-BiliQrLogin"
        private const val POLL_INTERVAL_MS = 1_500L
        private const val QR_SIZE_DP = 216
        private const val BILI_PINK = 0xFFFB7299.toInt()
    }

    private val qrClient by lazy { BiliQrLoginClient() }
    private var foregroundWebLoginToken: AutoCloseable? = null
    private var pollJob: Job? = null
    private var hasReturned = false
    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: MaterialButton
    private lateinit var webFallbackButton: MaterialButton
    private var pollRound: Int = 0

    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        NPLogger.d(LOG_TAG, "Web fallback resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            hasReturned = true
            setResult(RESULT_OK, result.data)
            finish()
            return@registerForActivityResult
        }
        if (!hasReturned) {
            startQrLogin()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitIfPhone()
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        foregroundWebLoginToken = ForegroundWebLoginGuard.enter("bilibili")

        buildLayout()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    NPLogger.d(LOG_TAG, "User exits QR login page")
                    finish()
                }
            }
        )
        startQrLogin()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        foregroundWebLoginToken?.close()
        foregroundWebLoginToken = null
        NPLogger.d(LOG_TAG, "QR login activity destroyed")
        super.onDestroy()
    }

    private fun buildLayout() {
        val root = CoordinatorLayout(this).apply {
            fitsSystemWindows = false
        }
        val surface = root.materialColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val onSurface = root.materialColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val surfaceVariant = root.materialColor(
            com.google.android.material.R.attr.colorSurfaceVariant,
            Color.rgb(244, 241, 246)
        )
        val onSurfaceVariant = root.materialColor(
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.DKGRAY
        )
        val onPrimary = root.materialColor(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        val softPrimary = ColorUtils.blendARGB(surface, BILI_PINK, 0.12f)
        val softSurface = ColorUtils.blendARGB(surface, surfaceVariant, 0.18f)

        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(softPrimary, softSurface, surface)
        )

        val appBar = AppBarLayout(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
        }
        appBar.addView(
            MaterialToolbar(this).apply {
                title = getString(R.string.bili_qr_login)
                setNavigationIcon(R.drawable.ic_arrow_back_24)
                setNavigationOnClickListener { finish() }
                setBackgroundColor(Color.TRANSPARENT)
            }
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp(), 22.dp(), 24.dp(), 48.dp())
        }
        val qrCardSizePx = minOf(240.dp(), resources.displayMetrics.widthPixels - 96.dp()).coerceAtLeast(204.dp())
        val qrImageSizePx = minOf(QR_SIZE_DP.dp(), qrCardSizePx - 24.dp()).coerceAtLeast(180.dp())
        val actionWidthPx = minOf(420.dp(), resources.displayMetrics.widthPixels - 48.dp()).coerceAtLeast(228.dp())

        val titleText = TextView(this).apply {
            text = getString(R.string.bili_qr_login_title)
            gravity = Gravity.CENTER
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
        }
        val subtitleText = TextView(this).apply {
            text = getString(R.string.bili_qr_login_subtitle)
            gravity = Gravity.CENTER
            textSize = 14f
            setLineSpacing(2.dp().toFloat(), 1f)
            setTextColor(onSurfaceVariant)
        }

        val qrCard = MaterialCardView(this).apply {
            radius = 28.dp().toFloat()
            cardElevation = 2.dp().toFloat()
            strokeWidth = 0
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = false
            preventCornerOverlap = true
            layoutParams = LinearLayout.LayoutParams(qrCardSizePx, qrCardSizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        qrImage = ImageView(this).apply {
            background = roundedBackground(Color.WHITE, 22.dp())
            clipToOutline = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
            layoutParams = FrameLayout.LayoutParams(qrImageSizePx, qrImageSizePx, Gravity.CENTER)
        }
        qrCard.addView(qrImage)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(BILI_PINK)
            layoutParams = FrameLayout.LayoutParams(42.dp(), 42.dp(), Gravity.CENTER)
        }
        qrCard.addView(progressBar)

        statusText = TextView(this).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 15f
            setPadding(16.dp(), 9.dp(), 16.dp(), 9.dp())
            setTextColor(BILI_PINK)
            background = roundedBackground(ColorUtils.blendARGB(surface, BILI_PINK, 0.11f), 20.dp())
        }
        hintText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setLineSpacing(3.dp().toFloat(), 1f)
            setTextColor(onSurfaceVariant)
        }
        retryButton = MaterialButton(this).apply {
            text = getString(R.string.bili_qr_login_retry)
            cornerRadius = 20.dp()
            minHeight = 52.dp()
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(BILI_PINK)
            setTextColor(onPrimary)
            setOnClickListener { startQrLogin() }
        }
        webFallbackButton = MaterialButton(this).apply {
            text = getString(R.string.bili_qr_login_web_fallback)
            cornerRadius = 20.dp()
            minHeight = 50.dp()
            insetTop = 0
            insetBottom = 0
            strokeWidth = 0
            backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(surface, BILI_PINK, 0.10f))
            setTextColor(BILI_PINK)
            setOnClickListener { openWebFallback() }
        }

        content.addView(titleText, matchWidthWrapHeight())
        content.addVerticalSpace(8)
        content.addView(subtitleText, matchWidthWrapHeight())
        content.addVerticalSpace(22)
        content.addView(qrCard)
        content.addVerticalSpace(14)
        content.addView(statusText, wrapContentCentered())
        content.addVerticalSpace(10)
        content.addView(hintText, fixedWidthWrapHeight(actionWidthPx))
        content.addVerticalSpace(16)
        content.addView(retryButton, fixedWidthWrapHeight(actionWidthPx))
        content.addVerticalSpace(10)
        content.addView(webFallbackButton, fixedWidthWrapHeight(actionWidthPx))

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                behavior = AppBarLayout.ScrollingViewBehavior()
            }
            addView(content)
        }

        root.addView(scrollView)
        root.addView(appBar)
        appBar.bringToFront()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            appBar.updatePadding(top = status.top)
            scrollView.updatePadding(bottom = nav.bottom + 16.dp())
            insets
        }
    }

    private fun startQrLogin() {
        pollJob?.cancel()
        qrClient.reset()
        pollRound = 0
        NPLogger.d(LOG_TAG, "Start QR login")
        pollJob = lifecycleScope.launch {
            setLoadingState(true)
            setStatus(getString(R.string.bili_qr_login_loading))
            hintText.text = getString(R.string.bili_qr_login_hint)
            qrImage.setImageDrawable(null)

            val session = runCatching {
                withContext(Dispatchers.IO) { qrClient.createSession() }
            }.getOrElse { error ->
                setLoadingState(false)
                setErrorStatus(getString(R.string.bili_qr_login_failed, error.readableMessage()))
                NPLogger.w(LOG_TAG, "Create QR login session failed", error)
                return@launch
            }
            NPLogger.d(LOG_TAG, "QR session ready key=${session.key.take(4)}...${session.key.takeLast(4)}")

            val bitmap = withContext(Dispatchers.Default) {
                createQrBitmap(session.qrContent, QR_SIZE_DP.dp())
            }
            qrImage.setImageBitmap(bitmap)
            setLoadingState(false)
            setStatus(getString(R.string.bili_qr_login_waiting))
            pollQrLogin(session)
        }
    }

    private suspend fun pollQrLogin(session: BiliQrLoginSession) {
        while (lifecycleScope.isActive && !hasReturned) {
            pollRound += 1
            NPLogger.d(LOG_TAG, "Poll round=$pollRound")
            val check = runCatching {
                withContext(Dispatchers.IO) { qrClient.checkLogin(session) }
            }.getOrElse { error ->
                setErrorStatus(getString(R.string.bili_qr_login_failed, error.readableMessage()))
                NPLogger.w(LOG_TAG, "Check QR login failed", error)
                return
            }
            NPLogger.d(
                LOG_TAG,
                "Poll round=$pollRound code=${check.code} message=${check.message} cookieKeys=${check.cookies.keys}"
            )

            when (check.code) {
                86101 -> setStatus(getString(R.string.bili_qr_login_waiting))
                86090 -> setStatus(getString(R.string.bili_qr_login_scanned))
                0 -> {
                    finishWithCookies(check.cookies)
                    return
                }
                86038 -> {
                    setErrorStatus(getString(R.string.bili_qr_login_expired))
                    return
                }
                else -> {
                    val message = check.message.ifBlank { "code=${check.code}" }
                    NPLogger.w(LOG_TAG, "Unexpected QR status code=${check.code} message=$message")
                    setErrorStatus(getString(R.string.bili_qr_login_failed, message))
                    return
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun finishWithCookies(cookies: Map<String, String>) {
        if (!shouldAutoCompleteBiliWebLogin(cookies)) {
            setErrorStatus(getString(R.string.bili_qr_login_cookie_incomplete))
            NPLogger.w(LOG_TAG, "QR login confirmed but cookie is incomplete, keys=${cookies.keys}")
            return
        }

        hasReturned = true
        val json = JSONObject().apply {
            cookies.forEach { (key, value) -> put(key, value) }
        }.toString()
        setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
        NPLogger.d(LOG_TAG, "QR login OK, cookie keys=${cookies.keys}")
        finish()
    }

    private fun openWebFallback() {
        pollJob?.cancel()
        NPLogger.d(LOG_TAG, "Open web fallback login")
        webLoginLauncher.launch(Intent(this, BiliWebLoginActivity::class.java))
    }

    private fun setLoadingState(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        retryButton.isEnabled = !loading
        webFallbackButton.isEnabled = true
    }

    private fun setStatus(text: String) {
        statusText.text = text
        NPLogger.d(LOG_TAG, "UI status=$text")
        val surface = statusText.materialColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        statusText.setTextColor(BILI_PINK)
        statusText.background = roundedBackground(ColorUtils.blendARGB(surface, BILI_PINK, 0.11f), 20.dp())
    }

    private fun setErrorStatus(text: String) {
        statusText.text = text
        NPLogger.w(LOG_TAG, "UI error=$text")
        val error = statusText.materialColor(androidx.appcompat.R.attr.colorError, Color.RED)
        val surface = statusText.materialColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        statusText.setTextColor(error)
        statusText.background = roundedBackground(
            ColorUtils.blendARGB(surface, error, 0.12f),
            20.dp()
        )
    }

    private fun createQrBitmap(content: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val rowOffset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[rowOffset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return createBitmap(sizePx, sizePx).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }

    private fun Throwable.readableMessage(): String {
        return message ?: javaClass.simpleName
    }

    private fun LinearLayout.addVerticalSpace(heightDp: Int) {
        addView(
            View(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightDp.dp()
            )
        )
    }

    private fun matchWidthWrapHeight(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun fixedWidthWrapHeight(widthPx: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            widthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun wrapContentCentered(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun View.materialColor(attr: Int, fallback: Int): Int {
        return MaterialColors.getColor(this, attr, fallback)
    }

    private fun roundedBackground(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).roundToInt()
    }
}
