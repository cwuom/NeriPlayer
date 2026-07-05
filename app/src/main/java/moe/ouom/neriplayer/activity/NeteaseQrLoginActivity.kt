package moe.ouom.neriplayer.activity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
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
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurface,
                    Color.WHITE
                )
            )
        }

        val appBar = AppBarLayout(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurface,
                    Color.WHITE
                )
            )
        }
        appBar.addView(
            MaterialToolbar(this).apply {
                title = getString(R.string.netease_qr_login)
                setNavigationIcon(R.drawable.ic_arrow_back_24)
                setNavigationOnClickListener { finish() }
            }
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp(), 32.dp(), 24.dp(), 32.dp())
        }

        qrImage = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(QR_SIZE_DP.dp(), QR_SIZE_DP.dp())
        }
        statusText = TextView(this).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 18f
            setTextColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        }
        hintText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(
                MaterialColors.getColor(
                    root,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    Color.DKGRAY
                )
            )
        }
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        retryButton = MaterialButton(this).apply {
            text = getString(R.string.netease_qr_login_retry)
            setOnClickListener { startQrLogin() }
        }
        webFallbackButton = MaterialButton(this).apply {
            text = getString(R.string.netease_qr_login_web_fallback)
            setOnClickListener { openWebFallback() }
        }

        content.addView(qrImage)
        content.addVerticalSpace(20)
        content.addView(statusText, matchWidthWrapHeight())
        content.addVerticalSpace(8)
        content.addView(hintText, matchWidthWrapHeight())
        content.addVerticalSpace(16)
        content.addView(progressBar)
        content.addVerticalSpace(20)
        content.addView(retryButton, matchWidthWrapHeight())
        content.addVerticalSpace(8)
        content.addView(webFallbackButton, matchWidthWrapHeight())

        val scrollView = ScrollView(this).apply {
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
            scrollView.updatePadding(bottom = nav.bottom)
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
        statusText.setTextColor(
            MaterialColors.getColor(
                statusText,
                com.google.android.material.R.attr.colorOnSurface,
                Color.BLACK
            )
        )
    }

    private fun setErrorStatus(text: String) {
        statusText.text = text
        NPLogger.w(LOG_TAG, "UI error=$text")
        statusText.setTextColor(
            MaterialColors.getColor(
                statusText,
                com.google.android.material.R.attr.colorError,
                Color.RED
            )
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

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).roundToInt()
    }
}
