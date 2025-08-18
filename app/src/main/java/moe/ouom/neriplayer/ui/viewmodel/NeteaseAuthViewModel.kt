package moe.ouom.neriplayer.ui.viewmodel

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
 * File: moe.ouom.neriplayer.ui.viewmodel/NeteaseAuthViewModel
 * Created: 2025/8/9
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import org.json.JSONObject

data class NeteaseAuthUiState(
    val phone: String = "",
    val captcha: String = "",
    val sending: Boolean = false,
    val loggingIn: Boolean = false,
    val countdownSec: Int = 0,
    val isLoggedIn: Boolean = false
)

sealed interface NeteaseAuthEvent {
    data class ShowSnack(val message: String) : NeteaseAuthEvent
    data class AskConfirmSend(val masked: String) : NeteaseAuthEvent
    /** 登录成功后弹窗展示 Cookies */
    data class ShowCookies(val cookies: Map<String, String>) : NeteaseAuthEvent
    data object LoginSuccess : NeteaseAuthEvent
}

class NeteaseAuthViewModel(app: Application) : AndroidViewModel(app) {

    private val cookieRepo = AppContainer.neteaseCookieRepo
    private val cookieStore: MutableMap<String, String> = mutableMapOf()
    private val api = AppContainer.neteaseClient

    private val _uiState = MutableStateFlow(NeteaseAuthUiState())
    val uiState: StateFlow<NeteaseAuthUiState> = _uiState

    private val _events = MutableSharedFlow<NeteaseAuthEvent>(extraBufferCapacity = 8)
    val events: MutableSharedFlow<NeteaseAuthEvent> = _events

    init {
        viewModelScope.launch(Dispatchers.IO) {
            cookieRepo.cookieFlow.collect { saved ->
                if (saved.isNotEmpty()) {
                    cookieStore.clear()
                    cookieStore.putAll(saved)
                    _uiState.value = _uiState.value.copy(isLoggedIn = cookieStore.containsKey("MUSIC_U"))
                }
            }
        }
    }

    fun onPhoneChange(new: String) {
        _uiState.value = _uiState.value.copy(phone = new.filter { it.isDigit() }.take(20))
    }

    fun onCaptchaChange(new: String) {
        _uiState.value = _uiState.value.copy(captcha = new.filter { it.isDigit() }.take(10))
    }

    fun askConfirmSendCaptcha() {
        val phone = _uiState.value.phone.trim()
        if (!isValidPhone(phone)) {
            emitSnack("请输入 11 位大陆手机号")
            return
        }
        _events.tryEmit(NeteaseAuthEvent.AskConfirmSend(maskPhone(phone)))
    }

    /** 发送验证码 */
    fun sendCaptcha(ctcode: String = "86") {
        val phone = _uiState.value.phone.trim()
        if (!isValidPhone(phone)) {
            emitSnack("手机号无效")
            return
        }
        if (_uiState.value.countdownSec > 0 || _uiState.value.sending) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = _uiState.value.copy(sending = true)
                val resp = api.sendCaptcha(phone, ctcode.toInt())
                val ok = JSONObject(resp).optInt("code", -1) == 200
                if (ok) {
                    emitSnack("验证码已发送")
                    startCountdown(60)
                } else {
                    val msg = JSONObject(resp).optString("msg", "发送失败")
                    emitSnack("发送失败：$msg")
                }
            } catch (e: Exception) {
                emitSnack("发送失败：" + (e.message ?: "网络错误"))
            } finally {
                _uiState.value = _uiState.value.copy(sending = false)
            }
        }
    }

    /** 验证码一键登录 */
    fun loginByCaptcha(countryCode: String = "86") {
        val phone = _uiState.value.phone.trim()
        val captcha = _uiState.value.captcha.trim()
        if (!isValidPhone(phone)) {
            emitSnack("手机号无效")
            return
        }
        if (captcha.isEmpty()) {
            emitSnack("请输入验证码")
            return
        }
        if (_uiState.value.loggingIn) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(loggingIn = true)
            try {
                // 校验验证码
                val verifyResp = api.verifyCaptcha(phone, captcha, countryCode.toInt())
                val verifyOk = JSONObject(verifyResp).optInt("code", -1) == 200
                if (!verifyOk) {
                    val msg = JSONObject(verifyResp).optString("msg", "验证码错误")
                    emitSnack("登录失败：$msg")
                    return@launch
                }

                // 登录
                val loginResp = api.loginByCaptcha(
                    phone = phone,
                    captcha = captcha,
                    ctcode = countryCode.toInt(),
                    remember = true
                )
                val obj = JSONObject(loginResp)
                val code = obj.optInt("code", -1)
                if (code == 200) {
                    val latest = api.getCookies()

                    cookieStore.clear()
                    cookieStore.putAll(latest)

                    try {
                        api.ensureWeapiSession()
                        val withCsrf = api.getCookies()
                        cookieStore.clear()
                        cookieStore.putAll(withCsrf)
                    } catch (_: Exception) { }

                    cookieRepo.saveCookies(cookieStore)

                    _uiState.value = _uiState.value.copy(isLoggedIn = true)
                    emitSnack("登录成功")
                    _events.tryEmit(NeteaseAuthEvent.ShowCookies(cookieStore.toMap()))
                    _events.tryEmit(NeteaseAuthEvent.LoginSuccess)
                } else {
                    val msg = obj.optString("msg", "登录失败，请选择其它登录方式")
                    emitSnack("登录失败：$msg")
                }
            } catch (e: Exception) {
                emitSnack("登录失败：" + (e.message ?: "网络错误"))
            } finally {
                _uiState.value = _uiState.value.copy(loggingIn = false)
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        viewModelScope.launch {
            var left = seconds
            while (left >= 0) {
                _uiState.value = _uiState.value.copy(countdownSec = left)
                delay(1000)
                left--
            }
        }
    }

    fun importCookiesFromMap(map: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            // 补齐关键字段
            val m = map.toMutableMap()
            m.putIfAbsent("os", "pc")
            m.putIfAbsent("appver", "8.10.35")

            cookieStore.clear()
            cookieStore.putAll(m)

            cookieRepo.saveCookies(cookieStore)

            _uiState.value = _uiState.value.copy(isLoggedIn = cookieStore.containsKey("MUSIC_U"))
            _events.tryEmit(NeteaseAuthEvent.ShowCookies(cookieStore.toMap()))
            _events.tryEmit(NeteaseAuthEvent.LoginSuccess)
            emitSnack("Cookie 已保存")
        }
    }

    /** 接收原始 Cookie 字符串：MUSIC_U=...; __csrf=...; ... */
    fun importCookiesFromRaw(raw: String) {
        val parsed = linkedMapOf<String, String>()
        raw.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains('=') }
            .forEach { s ->
                val idx = s.indexOf('=')
                if (idx > 0) {
                    val k = s.substring(0, idx).trim()
                    val v = s.substring(idx + 1).trim()
                    if (k.isNotEmpty()) parsed[k] = v
                }
            }
        if (parsed.isEmpty()) {
            emitSnack("未解析到合法 Cookie 项")
            return
        }
        importCookiesFromMap(parsed)
    }

    private fun isValidPhone(p: String): Boolean = p.length == 11 && p.all { it.isDigit() }

    private fun maskPhone(p: String): String =
        if (p.length >= 7) p.take(3) + "****" + p.takeLast(4) else p

    private fun emitSnack(msg: String) {
        _events.tryEmit(NeteaseAuthEvent.ShowSnack(msg))
    }
}
