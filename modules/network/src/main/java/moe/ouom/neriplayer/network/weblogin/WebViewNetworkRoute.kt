package moe.ouom.neriplayer.network.weblogin

import android.content.Context
import android.net.ConnectivityManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.network.proxy.DynamicProxySelector
import moe.ouom.neriplayer.network.proxy.NetworkDns

internal class WebProxyTransitions(
    private val desired: () -> Proxy?,
    private val install: suspend (Proxy?) -> Boolean
) {
    private val lock = Mutex()
    private var applied: Proxy? = null

    suspend fun awaitCurrent(allowSystemDirect: () -> Boolean = { false }): Boolean = lock.withLock {
        var target = desired()
        while (target != applied) {
            val installed = install(target)
            if (installed) applied = target
            val latest = desired()
            if (!installed && latest == target) {
                return@withLock target == Proxy.NO_PROXY && applied == null && allowSystemDirect()
            }
            target = latest
        }
        true
    }
}

internal fun webProxyConfig(route: Proxy?): ProxyConfig? {
    if (route == null) return null
    val builder = ProxyConfig.Builder()
    if (route == Proxy.NO_PROXY) return builder.addDirect().build()
    val address = requireNotNull(route.address() as? InetSocketAddress)
    val scheme = if (route.type() == Proxy.Type.SOCKS) "socks5" else "http"
    val url = URI(scheme, null, address.hostString, address.port, null, null, null)
    return builder.addProxyRule(url.toASCIIString()).build()
}

object WebViewNetworkRoute {
    private val immediateExecutor = Executor { it.run() }
    private val transitions = WebProxyTransitions({ DynamicProxySelector.overrideProxy }, ::install)

    fun refresh(scope: CoroutineScope, context: Context) {
        scope.launch { awaitCurrent(context) }
    }

    suspend fun awaitBackgroundAuthRoute(context: Context): Boolean =
        awaitCurrent(context) && supportsBackgroundAuthDns(NetworkDns.usesDnsOverHttps, DynamicProxySelector.overrideProxy)

    suspend fun awaitCurrent(context: Context): Boolean = withContext(Dispatchers.Main.immediate) {
        transitions.awaitCurrent {
            // older WebView providers can use the platform route if Android confirms it is direct
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            runCatching {
                connectivity != null && connectivity.activeNetwork != null && connectivity.defaultProxy == null
            }.getOrDefault(false)
        }
    }

    private suspend fun install(route: Proxy?): Boolean {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
            val controller = ProxyController.getInstance()
            val config = webProxyConfig(route)
            if (config == null) {
                suspendCoroutine { done -> controller.clearProxyOverride(immediateExecutor) { done.resume(Unit) } }
            } else {
                suspendCoroutine { done ->
                    controller.setProxyOverride(config, immediateExecutor) { done.resume(Unit) }
                }
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            // the WebView provider can be missing or updating while the app is running
            return false
        } catch (_: LinkageError) {
            return false
        }
    }
}

// WebView 不使用 OkHttp 的 DoH，只有明确安装的代理能代它解析认证站点的域名
internal fun supportsBackgroundAuthDns(usesDnsOverHttps: Boolean, proxy: Proxy?): Boolean =
    !usesDnsOverHttps || proxy?.address() != null
