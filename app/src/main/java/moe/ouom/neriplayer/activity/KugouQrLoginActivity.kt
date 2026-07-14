package moe.ouom.neriplayer.activity

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.activity/KugouQrLoginActivity
 * Created: 2025/07/05
 */

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.util.NPLogger
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

class KugouQrLoginActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "KugouQrLogin"
        private const val POLL_INTERVAL_MS = 2000L
        private const val QR_SIZE_PX = 600
    }

    private val kugouClient = AppContainer.kugouClient
    private var pollJob: Job? = null

    private lateinit var qrImageView: ImageView
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        startQrLogin()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
        }

        TextView(this).apply {
            text = "酷狗音乐扫码登录"
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 48 })
        }

        progressBar = ProgressBar(this).apply {
            visibility = View.VISIBLE
            root.addView(this)
        }

        qrImageView = ImageView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX)
            root.addView(this)
        }

        statusTextView = TextView(this).apply {
            text = "正在获取二维码..."
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 })
        }

        retryButton = MaterialButton(this).apply {
            text = "重试"
            visibility = View.GONE
            setOnClickListener { startQrLogin() }
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 })
        }

        setContentView(root)
    }

    private fun startQrLogin() {
        pollJob?.cancel()
        progressBar.visibility = View.VISIBLE
        qrImageView.visibility = View.GONE
        retryButton.visibility = View.GONE
        statusTextView.text = "正在获取二维码..."

        lifecycleScope.launch {
            try {
                val response = kugouClient.createQrKey()
                if (response.status != 200) throw Exception("HTTP ${response.status}")

                val data = response.body["data"]?.jsonObject ?: throw Exception("Invalid data")
                val qrcode = data["qrcode"]?.jsonPrimitive?.content ?: throw Exception("Missing key")
                val url = kugouClient.createQrCodeUrl(qrcode)

                val bitmap = createQrBitmap(url)
                qrImageView.setImageBitmap(bitmap)
                qrImageView.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                statusTextView.text = "请使用酷狗音乐 App 扫码"

                startPolling(qrcode)
            } catch (e: Exception) {
                NPLogger.e(TAG, "Failed to start QR login", e)
                statusTextView.text = "加载失败: ${e.message}"
                progressBar.visibility = View.GONE
                retryButton.visibility = View.VISIBLE
            }
        }
    }

    private fun startPolling(key: String) {
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                try {
                    val response = kugouClient.checkQrCode(key)
                    if (response.status != 200) continue

                    val data = response.body["data"]?.jsonObject ?: continue
                    val status = data["status"]?.jsonPrimitive?.intOrNull

                    when (status) {
                        1 -> statusTextView.text = "等待扫码"
                        4 -> {
                            val token = data["token"]?.jsonPrimitive?.content ?: ""
                            val userid = data["userid"]?.jsonPrimitive?.content ?: ""
                            if (token.isNotEmpty() && userid.isNotEmpty()) {
                                handleLoginSuccess(token, userid)
                                break
                            }
                        }
                        2 -> {
                            statusTextView.text = "等待确认"
                        }
                        0 -> {
                            statusTextView.text = "二维码已过期"
                            retryButton.visibility = View.VISIBLE
                            break
                        }
                    }
                } catch (e: Exception) {
                    NPLogger.w(TAG, "Poll failed: ${e.message}")
                }
            }
        }
    }

    private fun handleLoginSuccess(token: String, userid: String) {
        NPLogger.d(TAG, "Login success: token=$token, userid=$userid")
        val cookies = mapOf("token" to token, "userid" to userid)
        AppContainer.kugouCookieRepo.saveCookies(cookies)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun createQrBitmap(content: String): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, hints)
        val bitmap = createBitmap(QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.RGB_565)
        for (x in 0 until QR_SIZE_PX) {
            for (y in 0 until QR_SIZE_PX) {
                bitmap[x, y] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
    }
}
