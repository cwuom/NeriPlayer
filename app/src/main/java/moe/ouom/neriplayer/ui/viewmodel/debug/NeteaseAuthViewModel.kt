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
 * File: moe.ouom.neriplayer.ui.viewmodel.debug/NeteaseAuthViewModel
 * Created: 2025/8/9
 */

import android.app.Application
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.R as AppR
import java.io.IOException
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.network.http.parsing.parseRawCookieText
import moe.ouom.neriplayer.data.auth.web.clearWebViewLoginState
import moe.ouom.neriplayer.data.auth.web.WebLoginPlatform
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds

data class NeteaseAuthUiState(
    val phone: String = "",
    val captcha: String = "",
    val sending: Boolean = false,
    val loggingIn: Boolean = false,
    val countdownSec: Int = 0,
    val isLoggedIn: Boolean = false,
    val health: SavedCookieAuthHealth = SavedCookieAuthHealth(),
    val hasSavedCookies: Boolean = false
)

sealed interface NeteaseAuthEvent {
    data class ShowSnack(val message: UiText) : NeteaseAuthEvent
    data class AskConfirmSend(val masked: String) : NeteaseAuthEvent
    data object LoginSuccess : NeteaseAuthEvent
}

class NeteaseAuthViewModel(app: Application) : AndroidViewModel(app) {

    private val cookieRepo by lazy { AppContainer.neteaseCookieRepo }
    private val cookieStore: MutableMap<String, String> = mutableMapOf()
    private val api by lazy { AppContainer.neteaseClient }

    private val _uiState = MutableStateFlow(NeteaseAuthUiState())
    val uiState: StateFlow<NeteaseAuthUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<NeteaseAuthEvent>(extraBufferCapacity = 8)
    val events: MutableSharedFlow<NeteaseAuthEvent> = _events

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val initialHealth = cookieRepo.getAuthHealth()
            val initialCookies = cookieRepo.getCookiesOnce()
            _uiState.value = _uiState.value.copy(
                health = initialHealth,
                isLoggedIn = initialHealth.state != SavedCookieAuthState.Missing,
                hasSavedCookies = initialCookies.isNotEmpty()
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            cookieRepo.cookieFlow.collect { saved ->
                cookieStore.clear()
                cookieStore.putAll(saved)
                _uiState.value = _uiState.value.copy(
                    hasSavedCookies = saved.isNotEmpty()
                )
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            cookieRepo.authHealthFlow.collect { health ->
                _uiState.value = _uiState.value.copy(
                    health = health,
                    isLoggedIn = health.state != SavedCookieAuthState.Missing
                )
            }
        }
    }

    fun refreshAuthHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            cookieRepo.refreshHealth()
            val health = cookieRepo.getAuthHealthOnce()
            _uiState.value = _uiState.value.copy(
                health = health,
                isLoggedIn = health.state != SavedCookieAuthState.Missing
            )
        }
    }

    fun clearCookies() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { api.logout() }
            try {
                cookieRepo.clear()
            } catch (_: IOException) {
                emitSnack(UiText.Resource(CoreCommonR.string.auth_storage_clear_failed))
                return@launch
            }
            cookieStore.clear()
            clearWebViewLoginState(
                context = getApplication(),
                platform = WebLoginPlatform.NETEASE
            )
            _events.tryEmit(
                NeteaseAuthEvent.ShowSnack(
                    UiText.Resource(CoreCommonR.string.auth_cookie_cleared)
                )
            )
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
            emitSnack(UiText.Resource(AppR.string.auth_phone_required))
            return
        }
        _events.tryEmit(NeteaseAuthEvent.AskConfirmSend(maskPhone(phone)))
    }

    fun sendCaptcha(ctcode: String = "86") {
        val phone = _uiState.value.phone.trim()
        if (!isValidPhone(phone)) {
            emitSnack(UiText.Resource(AppR.string.auth_phone_invalid))
            return
        }
        if (_uiState.value.countdownSec > 0 || _uiState.value.sending) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = _uiState.value.copy(sending = true)
                val resp = api.sendCaptcha(phone, ctcode.toInt())
                val ok = JSONObject(resp).optInt("code", -1) == 200
                if (ok) {
                    emitSnack(UiText.Resource(AppR.string.auth_captcha_sent))
                    startCountdown()
                } else {
                    val message = providerMessage(JSONObject(resp), AppR.string.auth_captcha_send_error)
                    emitSnack(UiText.Resource(AppR.string.auth_captcha_send_failed, listOf(message)))
                }
            } catch (e: Exception) {
                emitSnack(UiText.Resource(AppR.string.auth_captcha_send_failed, listOf(failureMessage(e))))
            } finally {
                _uiState.value = _uiState.value.copy(sending = false)
            }
        }
    }

    fun loginByCaptcha(countryCode: String = "86") {
        val phone = _uiState.value.phone.trim()
        val captcha = _uiState.value.captcha.trim()
        if (!isValidPhone(phone)) {
            emitSnack(UiText.Resource(AppR.string.auth_phone_invalid))
            return
        }
        if (captcha.isEmpty()) {
            emitSnack(UiText.Resource(AppR.string.auth_captcha_required))
            return
        }
        if (_uiState.value.loggingIn) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(loggingIn = true)
            try {
                val verifyResp = api.verifyCaptcha(phone, captcha, countryCode.toInt())
                val verifyOk = JSONObject(verifyResp).optInt("code", -1) == 200
                if (!verifyOk) {
                    val message = providerMessage(JSONObject(verifyResp), AppR.string.auth_captcha_invalid)
                    emitSnack(UiText.Resource(AppR.string.auth_login_failed, listOf(message)))
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

                    if (!cookieRepo.saveCookies(cookieStore)) {
                        emitSnack(UiText.Resource(CoreCommonR.string.auth_storage_save_failed))
                        return@launch
                    }

                    _uiState.value = _uiState.value.copy(isLoggedIn = true)
                    _events.tryEmit(NeteaseAuthEvent.LoginSuccess)
                } else {
                    val message = providerMessage(obj, AppR.string.auth_login_retry)
                    emitSnack(UiText.Resource(AppR.string.auth_login_failed, listOf(message)))
                }
            } catch (e: Exception) {
                emitSnack(UiText.Resource(AppR.string.auth_login_failed, listOf(failureMessage(e))))
            } finally {
                _uiState.value = _uiState.value.copy(loggingIn = false)
            }
        }
    }

    fun importCookiesFromMap(map: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (map.isEmpty()) {
                emitSnack(UiText.Resource(CoreCommonR.string.auth_cookie_empty))
                return@launch
            }

            val validation = cookieRepo.validateCookies(map)
            if (!validation.isAccepted) {
                emitSnack(UiText.Resource(CoreCommonR.string.auth_cookie_invalid))
                return@launch
            }

            cookieStore.clear()
            cookieStore.putAll(validation.sanitizedCookies)

            if (!cookieRepo.saveCookies(cookieStore)) {
                emitSnack(UiText.Resource(CoreCommonR.string.auth_storage_save_failed))
                return@launch
            }

            _uiState.value = _uiState.value.copy(isLoggedIn = true)
            _events.tryEmit(NeteaseAuthEvent.LoginSuccess)
        }
    }

    fun importCookiesFromRaw(raw: String) {
        val parsed = parseRawCookieText(raw)
        if (parsed.isEmpty()) {
            emitSnack(UiText.Resource(CoreCommonR.string.auth_cookie_invalid))
            return
        }
        importCookiesFromMap(parsed)
    }

    private fun startCountdown() {
        viewModelScope.launch {
            var left = 60
            while (left >= 0) {
                _uiState.value = _uiState.value.copy(countdownSec = left)
                delay(1000.milliseconds)
                left--
            }
        }
    }

    private fun isValidPhone(p: String): Boolean = p.length == 11 && p.all { it.isDigit() }

    private fun maskPhone(p: String): String =
        if (p.length >= 7) p.take(3) + "****" + p.takeLast(4) else p

    private fun emitSnack(msg: UiText) {
        _events.tryEmit(NeteaseAuthEvent.ShowSnack(msg))
    }

    private fun providerMessage(response: JSONObject, @StringRes fallback: Int): UiText =
        response.optString("msg").takeIf { it.isNotBlank() }?.let(UiText::Literal)
            ?: UiText.Resource(fallback)

    private fun failureMessage(error: Exception): UiText =
        if (error is IOException) UiText.Resource(AppR.string.auth_network_error)
        else error.message?.takeIf { it.isNotBlank() }?.let(UiText::Literal)
            ?: UiText.Resource(AppR.string.auth_network_error)
}
