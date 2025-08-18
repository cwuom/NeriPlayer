package moe.ouom.neriplayer.ui.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import java.io.IOException

private const val TAG_PROBE = "NERI-NeteaseApiProbeVM"

data class ProbeUiState(
    val running: Boolean = false,
    val lastMessage: String = "",
    val lastJsonPreview: String = ""
)

class NeteaseApiProbeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppContainer.neteaseCookieRepo
    private val client = AppContainer.neteaseClient

    private val _ui = MutableStateFlow(ProbeUiState())
    val ui: StateFlow<ProbeUiState> = _ui

    /** 初始化 Cookie */
    private suspend fun ensureCookies() {
        val cookies = withContext(Dispatchers.IO) { repo.getCookiesOnce() }.toMutableMap()
        if (!cookies.containsKey("os")) cookies["os"] = "pc"
        runCatching { withContext(Dispatchers.IO) { client.ensureWeapiSession() } }
        NPLogger.d(TAG_PROBE, "Cookies injected: keys=${cookies.keys.joinToString()}")
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun callAccountAndCopy() = launchAndCopy("account") {
        val raw = client.getCurrentUserAccount()
        raw
    }

    fun callUserIdAndCopy() = launchAndCopy("userId") {
        val id = client.getCurrentUserId()
        """{"code":200,"userId":$id}"""
    }

    fun callCreatedPlaylistsAndCopy() = launchAndCopy("createdPlaylists") {
        client.getUserCreatedPlaylists(0)
    }

    fun callSubscribedPlaylistsAndCopy() = launchAndCopy("subscribedPlaylists") {
        client.getUserSubscribedPlaylists(0)
    }

    fun callLikedPlaylistIdAndCopy() = launchAndCopy("likedPlaylistId") {
        client.getLikedPlaylistId(0)
    }

    fun callLyric33894312AndCopy() = launchAndCopy("lyric_33894312") {
        client.getLyricNew(33894312L)
    }

    fun callAllAndCopy() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(running = true, lastMessage = "正在调用所有接口...", lastJsonPreview = "")
            try {
                ensureCookies()

                val accountRaw = withContext(Dispatchers.IO) { client.getCurrentUserAccount() }
                val userId = withContext(Dispatchers.IO) { client.getCurrentUserId() }
                val createdRaw = withContext(Dispatchers.IO) { client.getUserCreatedPlaylists(0) }
                val subsRaw = withContext(Dispatchers.IO) { client.getUserSubscribedPlaylists(0) }
                val likedPlIdRaw = withContext(Dispatchers.IO) { client.getLikedPlaylistId(0) }
                val lyric33894312Raw = withContext(Dispatchers.IO) { client.getLyricNew(33894312L) }

                // 组装聚合 JSON
                val result = JSONObject().apply {
                    put("account", JSONObject(accountRaw))
                    put("userId", userId)
                    put("createdPlaylists", JSONObject(createdRaw))
                    put("subscribedPlaylists", JSONObject(subsRaw))
                    put("likedPlaylistId", JSONObject(likedPlIdRaw))
                    put("lyric_33894312", JSONObject(lyric33894312Raw))
                    // 不包含 liked songs list
                }.toString()

                copyToClipboard("netease_api_all", result)
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = "OK，所有接口已调用完成并复制到剪贴板",
                    lastJsonPreview = result
                )
            } catch (e: IOException) {
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = "网络/服务器异常：${e.message ?: e.javaClass.simpleName}"
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = "调用/解析失败：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun launchAndCopy(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(running = true, lastMessage = "调用中：$label ...", lastJsonPreview = "")
            try {
                ensureCookies()
                val raw = withContext(Dispatchers.IO) { block() }
                copyToClipboard("netease_api_$label", raw)
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = "已复制到剪贴板：$label",
                    lastJsonPreview = raw
                )
            } catch (e: IOException) {
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = "网络/服务器异常：${e.message ?: e.javaClass.simpleName}"
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = "调用/解析失败：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }
}
