package moe.ouom.neriplayer.ui.viewmodel.debug

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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.SavedCookieAuthState
import org.json.JSONObject

data class NeteaseAuthUiState(
    val phone: String = "",
    val captcha: String = "",
    val sending: Boolean = false,
    val loggingIn: Boolean = false,
    val countdownSec: Int = 0,
    val isLoggedIn: Boolean = false,
    val health: SavedCookieAuthHealth = SavedCookieAuthHealth()
)

sealed interface NeteaseAuthEvent {
    data class ShowSnack(val message: String) : NeteaseAuthEvent
    data class AskConfirmSend(val masked: String) : NeteaseAuthEvent
    data class ShowCookies(val cookies: Map<String, String>) : NeteaseAuthEvent
    data object LoginSuccess : NeteaseAuthEvent
    data class PromptReauth(val health: SavedCookieAuthHealth) : NeteaseAuthEvent
}

@Suppress("unused")
class NeteaseAuthViewModel(app: Application) : AndroidViewModel(app) {

    private val cookieRepo = AppContainer.neteaseCookieRepo
    private val cookieStore: MutableMap<String, String> = mutableMapOf()
    private val api = AppContainer.neteaseClient

    private val _uiState = MutableStateFlow(
        NeteaseAuthUiState(
            health = cookieRepo.getAuthHealth(),
            isLoggedIn = cookieRepo.getAuthHealth().state != SavedCookieAuthState.Missing
        )
    )
    val uiState: StateFlow<NeteaseAuthUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<NeteaseAuthEvent>(extraBufferCapacity = 8)
    val events: MutableSharedFlow<NeteaseAuthEvent> = _events

    private var lastPromptSignature: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            cookieRepo.cookieFlow.collect { saved ->
                cookieStore.clear()
                cookieStore.putAll(saved)
            }
        }
        viewModelScope.launch {
            cookieRepo.authHealthFlow.collect { health ->
                _uiState.value = _uiState.value.copy(
                    health = health,
                    isLoggedIn = health.state != SavedCookieAuthState.Missing &&
                        health.state != SavedCookieAuthState.Expired
                )
                if (!health.shouldPromptRelogin) {
                    lastPromptSignature = null
                }
            }
        }
    }

    fun refreshAuthHealth(
        promptIfNeeded: Boolean = false,
        forcePrompt: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            cookieRepo.refreshHealth()
            var health = cookieRepo.getAuthHealthOnce()
            if (health.state != SavedCookieAuthState.Missing) {
                health = health.copy(
                    state = SavedCookieAuthState.Checking,
                    checkedAt = System.currentTimeMillis()
                )
                _uiState.value = _uiState.value.copy(
                    health = health,
                    isLoggedIn = true
                )
                health = validateCurrentAuth(cookieRepo.getAuthHealthOnce())
            }

            _uiState.value = _uiState.value.copy(
                health = health,
                isLoggedIn = health.state != SavedCookieAuthState.Missing &&
                    health.state != SavedCookieAuthState.Expired
            )
            if (promptIfNeeded) {
                emitPromptIfNeeded(health, forcePrompt)
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
            emitSnack("Please enter 11-digit phone number")
            return
        }
        _events.tryEmit(NeteaseAuthEvent.AskConfirmSend(maskPhone(phone)))
    }

    fun sendCaptcha(ctcode: String = "86") {
        val phone = _uiState.value.phone.trim()
        if (!isValidPhone(phone)) {
            emitSnack("Invalid phone number")
            return
        }
        if (_uiState.value.countdownSec > 0 || _uiState.value.sending) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = _uiState.value.copy(sending = true)
                val resp = api.sendCaptcha(phone, ctcode.toInt())
                val ok = JSONObject(resp).optInt("code", -1) == 200
                if (ok) {
                    emitSnack("Verification code sent")
                    startCountdown(60)
                } else {
                    val msg = JSONObject(resp).optString("msg", "Send failed")
                    emitSnack("Send failed: $msg")
                }
            } catch (e: Exception) {
                emitSnack("Send failed: " + (e.message ?: "Network error"))
            } finally {
                _uiState.value = _uiState.value.copy(sending = false)
            }
        }
    }

    fun loginByCaptcha(countryCode: String = "86") {
        val phone = _uiState.value.phone.trim()
        val captcha = _uiState.value.captcha.trim()
        if (!isValidPhone(phone)) {
            emitSnack("Invalid phone number")
            return
        }
        if (captcha.isEmpty()) {
            emitSnack("Please enter verification code")
            return
        }
        if (_uiState.value.loggingIn) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(loggingIn = true)
            try {
                val verifyResp = api.verifyCaptcha(phone, captcha, countryCode.toInt())
                val verifyOk = JSONObject(verifyResp).optInt("code", -1) == 200
                if (!verifyOk) {
                    val msg = JSONObject(verifyResp).optString("msg", "Verification code error")
                    emitSnack("Login failed: $msg")
                    return@launch
                }

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
                    } catch (_: Exception) {
                    }

                    cookieRepo.saveCookies(cookieStore)

                    _uiState.value = _uiState.value.copy(isLoggedIn = true)
                    emitSnack("Login successful")
                    _events.tryEmit(NeteaseAuthEvent.ShowCookies(cookieStore.toMap()))
                    _events.tryEmit(NeteaseAuthEvent.LoginSuccess)
                } else {
                    val msg = obj.optString("msg", "Login failed, please try another method")
                    emitSnack("Login failed: $msg")
                }
            } catch (e: Exception) {
                emitSnack("Login failed: " + (e.message ?: "Network error"))
            } finally {
                _uiState.value = _uiState.value.copy(loggingIn = false)
            }
        }
    }

    fun importCookiesFromMap(map: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val m = map.toMutableMap()
            m.putIfAbsent("os", "pc")
            m.putIfAbsent("appver", "8.10.35")

            if (m["MUSIC_U"].isNullOrBlank() && m["MUSIC_A"].isNullOrBlank()) {
                emitSnack(getApplication<Application>().getString(R.string.auth_cookie_invalid))
                return@launch
            }

            cookieStore.clear()
            cookieStore.putAll(m)

            cookieRepo.saveCookies(cookieStore)

            _uiState.value = _uiState.value.copy(isLoggedIn = true)
            _events.tryEmit(NeteaseAuthEvent.ShowCookies(cookieStore.toMap()))
            _events.tryEmit(NeteaseAuthEvent.LoginSuccess)
            emitSnack(getApplication<Application>().getString(R.string.auth_cookie_saved))
        }
    }

    fun importCookiesFromRaw(raw: String) {
        val parsed = linkedMapOf<String, String>()
        raw.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains('=') }
            .forEach { s ->
                val idx = s.indexOf('=')
                if (idx > 0) {
                    val key = s.substring(0, idx).trim()
                    val value = s.substring(idx + 1).trim()
                    if (key.isNotEmpty()) parsed[key] = value
                }
            }
        if (parsed.isEmpty()) {
            emitSnack(getApplication<Application>().getString(R.string.auth_cookie_invalid))
            return
        }
        importCookiesFromMap(parsed)
    }

    private fun validateCurrentAuth(localHealth: SavedCookieAuthHealth): SavedCookieAuthHealth {
        val checkedAt = System.currentTimeMillis()
        return runCatching {
            val raw = api.getCurrentUserAccount()
            val root = JSONObject(raw)
            val code = root.optInt("code", -1)
            val userId = root.optJSONObject("profile")?.optLong("userId", 0L) ?: 0L
            if (code == 200 && userId > 0L) {
                localHealth.copy(
                    state = SavedCookieAuthState.Valid,
                    checkedAt = checkedAt
                )
            } else {
                localHealth.copy(
                    state = SavedCookieAuthState.Expired,
                    checkedAt = checkedAt
                )
            }
        }.getOrElse {
            localHealth.copy(checkedAt = checkedAt)
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

    private fun emitPromptIfNeeded(
        health: SavedCookieAuthHealth,
        forcePrompt: Boolean
    ) {
        if (!health.shouldPromptRelogin) {
            lastPromptSignature = null
            return
        }

        val promptSignature = buildString {
            append(health.state.name)
            append(':')
            append(health.savedAt)
        }
        if (!forcePrompt && promptSignature == lastPromptSignature) {
            return
        }

        lastPromptSignature = promptSignature
        _events.tryEmit(NeteaseAuthEvent.PromptReauth(health))
    }

    private fun isValidPhone(p: String): Boolean = p.length == 11 && p.all { it.isDigit() }

    private fun maskPhone(p: String): String =
        if (p.length >= 7) p.take(3) + "****" + p.takeLast(4) else p

    private fun emitSnack(msg: String) {
        _events.tryEmit(NeteaseAuthEvent.ShowSnack(msg))
    }
}
