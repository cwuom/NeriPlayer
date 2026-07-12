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
import moe.ouom.neriplayer.core.api.netease.NeteaseQrLoginClient
import moe.ouom.neriplayer.data.auth.web.ForegroundWebLoginGuard
import moe.ouom.neriplayer.data.auth.web.normalizeNeteaseWebLoginCookies
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.lockPortraitIfPhone
import org.json.JSONObject
import kotlin.math.roundToInt

class NeteaseQrLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = "result_cookie_map_json"
        private const val LOG_TAG = "NERI-NeteaseQrLogin"
        private const val POLL_INTERVAL_MS = 1_500L
        private const val QR_SIZE_DP = 260
    }

    private val qrClient by lazy { NeteaseQrLoginClient(this) }
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
        foregroundWebLoginToken = ForegroundWebLoginGuard.enter("netease")

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
        val primary = root.materialColor(com.google.android.material.R.attr.colorPrimary, Color.rgb(199, 37, 53))
        val onPrimary = root.materialColor(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        val outline = root.materialColor(com.google.android.material.R.attr.colorOutline, Color.LTGRAY)
        val softPrimary = ColorUtils.blendARGB(surface, primary, 0.08f)

        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(softPrimary, surface)
        )

        val toolbarBackground = ColorUtils.blendARGB(surface, softPrimary, 0.38f)

        val appBar = AppBarLayout(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(toolbarBackground)
        }
        appBar.addView(
            MaterialToolbar(this).apply {
                title = getString(R.string.netease_qr_login)
                setNavigationIcon(R.drawable.ic_arrow_back_24)
                setNavigationOnClickListener { finish() }
                setBackgroundColor(toolbarBackground)
            }
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 48.dp())
        }
        val qrCardSizePx = minOf(280.dp(), resources.displayMetrics.widthPixels - 80.dp()).coerceAtLeast(220.dp())
        val qrImageSizePx = minOf(QR_SIZE_DP.dp(), qrCardSizePx - 24.dp()).coerceAtLeast(196.dp())

        val titleText = TextView(this).apply {
            text = getString(R.string.netease_qr_login_title)
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
        }
        val subtitleText = TextView(this).apply {
            text = getString(R.string.netease_qr_login_subtitle)
            gravity = Gravity.CENTER
            textSize = 14f
            setLineSpacing(2.dp().toFloat(), 1f)
            setTextColor(onSurfaceVariant)
        }

        val qrCard = MaterialCardView(this).apply {
            radius = 34.dp().toFloat()
            cardElevation = 0f
            strokeWidth = 1.dp()
            strokeColor = ColorUtils.blendARGB(outline, surface, 0.42f)
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = true
            preventCornerOverlap = true
            layoutParams = LinearLayout.LayoutParams(qrCardSizePx, qrCardSizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        qrImage = ImageView(this).apply {
            background = roundedBackground(Color.WHITE, 28.dp())
            clipToOutline = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = FrameLayout.LayoutParams(qrImageSizePx, qrImageSizePx, Gravity.CENTER)
        }
        qrCard.addView(qrImage)

        statusText = TextView(this).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 15f
            setPadding(14.dp(), 9.dp(), 14.dp(), 9.dp())
            setTextColor(onSurface)
            background = roundedBackground(surfaceVariant, 18.dp())
        }
        hintText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setLineSpacing(2.dp().toFloat(), 1f)
            setTextColor(onSurfaceVariant)
        }
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(primary)
        }
        retryButton = MaterialButton(this).apply {
            text = getString(R.string.netease_qr_login_retry)
            cornerRadius = 18.dp()
            minHeight = 50.dp()
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(primary)
            setTextColor(onPrimary)
            setOnClickListener { startQrLogin() }
        }
        webFallbackButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.netease_qr_login_web_fallback)
            cornerRadius = 18.dp()
            minHeight = 50.dp()
            insetTop = 0
            insetBottom = 0
            strokeWidth = 1.dp()
            strokeColor = ColorStateList.valueOf(ColorUtils.blendARGB(outline, primary, 0.25f))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(primary)
            setOnClickListener { openWebFallback() }
        }

        val panel = MaterialCardView(this).apply {
            radius = 32.dp().toFloat()
            cardElevation = 0f
            strokeWidth = 1.dp()
            strokeColor = ColorUtils.blendARGB(outline, surface, 0.52f)
            setCardBackgroundColor(ColorUtils.blendARGB(surface, surfaceVariant, 0.26f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val panelContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16.dp(), 18.dp(), 16.dp(), 18.dp())
        }

        panelContent.addView(qrCard)
        panelContent.addVerticalSpace(14)
        panelContent.addView(statusText, wrapContentCentered())
        panelContent.addVerticalSpace(8)
        panelContent.addView(hintText, matchWidthWrapHeight())
        panelContent.addVerticalSpace(8)
        panelContent.addView(progressBar)
        panelContent.addVerticalSpace(14)
        panelContent.addView(retryButton, matchWidthWrapHeight())
        panelContent.addVerticalSpace(8)
        panelContent.addView(webFallbackButton, matchWidthWrapHeight())
        panel.addView(panelContent)

        content.addView(titleText, matchWidthWrapHeight())
        content.addVerticalSpace(6)
        content.addView(subtitleText, matchWidthWrapHeight())
        content.addVerticalSpace(16)
        content.addView(panel)

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
            setStatus(getString(R.string.netease_qr_login_loading))
            hintText.text = getString(R.string.netease_qr_login_hint)
            qrImage.setImageDrawable(null)

            val session = runCatching {
                withContext(Dispatchers.IO) { qrClient.createSession() }
            }.getOrElse { error ->
                setLoadingState(false)
                setErrorStatus(getString(R.string.netease_qr_login_failed, error.readableMessage()))
                NPLogger.w(LOG_TAG, "Create QR login session failed", error)
                return@launch
            }
            NPLogger.d(
                LOG_TAG,
                "QR session ready key=${session.key.take(4)}...${session.key.takeLast(4)} " +
                    "chainId=${session.chainId} ydDeviceTokenLength=${session.ydDeviceToken.length} " +
                    "seedCookieKeys=${session.seedCookieKeys}"
            )

            val bitmap = withContext(Dispatchers.Default) {
                createQrBitmap(session.qrContent, QR_SIZE_DP.dp())
            }
            qrImage.setImageBitmap(bitmap)
            setLoadingState(false)
            setStatus(getString(R.string.netease_qr_login_waiting))
            pollQrLogin(session)
        }
    }

    private suspend fun pollQrLogin(session: moe.ouom.neriplayer.core.api.netease.NeteaseQrLoginSession) {
        while (lifecycleScope.isActive && !hasReturned) {
            pollRound += 1
            NPLogger.d(LOG_TAG, "Poll round=$pollRound")
            val check = runCatching {
                withContext(Dispatchers.IO) { qrClient.checkLogin(session) }
            }.getOrElse { error ->
                setErrorStatus(getString(R.string.netease_qr_login_failed, error.readableMessage()))
                NPLogger.w(LOG_TAG, "Check QR login failed", error)
                return
            }
            NPLogger.d(
                LOG_TAG,
                "Poll round=$pollRound code=${check.code} message=${check.message} cookieKeys=${check.cookies.keys}"
            )

            when (check.code) {
                801 -> setStatus(getString(R.string.netease_qr_login_waiting))
                802 -> setStatus(getString(R.string.netease_qr_login_scanned))
                803 -> {
                    finishWithCookies(check.cookies)
                    return
                }
                800 -> {
                    setErrorStatus(getString(R.string.netease_qr_login_expired))
                    return
                }
                else -> {
                    val message = check.message.ifBlank { "code=${check.code}" }
                    NPLogger.w(LOG_TAG, "Unexpected QR status code=${check.code} message=$message")
                    setErrorStatus(getString(R.string.netease_qr_login_failed, message))
                    return
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun finishWithCookies(cookies: Map<String, String>) {
        val normalized = normalizeNeteaseWebLoginCookies(cookies)
        NPLogger.d(
            LOG_TAG,
            "Finish with cookies rawKeys=${cookies.keys} normalizedKeys=${normalized.keys} " +
                "hasMusicU=${normalized["MUSIC_U"].isNullOrBlank().not()} hasCsrf=${normalized["__csrf"].isNullOrBlank().not()}"
        )
        if (normalized["MUSIC_U"].isNullOrBlank()) {
            setErrorStatus(getString(R.string.netease_qr_login_cookie_incomplete))
            NPLogger.w(LOG_TAG, "QR login confirmed but cookie is incomplete, keys=${cookies.keys}")
            return
        }

        hasReturned = true
        val json = JSONObject().apply {
            normalized.forEach { (key, value) -> put(key, value) }
        }.toString()
        setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
        NPLogger.d(LOG_TAG, "QR login OK, cookie keys=${normalized.keys}")
        finish()
    }

    private fun openWebFallback() {
        pollJob?.cancel()
        NPLogger.d(LOG_TAG, "Open web fallback login")
        webLoginLauncher.launch(Intent(this, NeteaseWebLoginActivity::class.java))
    }

    private fun setLoadingState(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        retryButton.isEnabled = !loading
        webFallbackButton.isEnabled = true
    }

    private fun setStatus(text: String) {
        statusText.text = text
        NPLogger.d(LOG_TAG, "UI status=$text")
        val surfaceVariant = statusText.materialColor(
            com.google.android.material.R.attr.colorSurfaceVariant,
            Color.rgb(244, 241, 246)
        )
        statusText.setTextColor(statusText.materialColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        statusText.background = roundedBackground(surfaceVariant, 18.dp())
    }

    private fun setErrorStatus(text: String) {
        statusText.text = text
        NPLogger.w(LOG_TAG, "UI error=$text")
        val error = statusText.materialColor(com.google.android.material.R.attr.colorError, Color.RED)
        val surface = statusText.materialColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        statusText.setTextColor(error)
        statusText.background = roundedBackground(
            ColorUtils.blendARGB(surface, error, 0.12f),
            18.dp()
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
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
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
