package moe.ouom.neriplayer.activity.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import com.google.android.material.snackbar.Snackbar
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.core.di.NetworkRoute
import moe.ouom.neriplayer.core.di.networkRoute
import moe.ouom.neriplayer.data.settings.bootstrap.readBootstrapSettingsSnapshotSync
import moe.ouom.neriplayer.network.weblogin.WebViewNetworkRoute
import moe.ouom.neriplayer.ui.feedback.showNeriViewSnackbar

private const val BYPASS_SYSTEM_PROXY = "network.bypass_system_proxy"
private const val CUSTOM_PROXY = "network.proxy"
private const val DNS_ENDPOINT = "network.dns"

internal fun Intent.authRoute(): NetworkRoute? {
    if (!hasExtra(BYPASS_SYSTEM_PROXY)) return null
    return NetworkRoute(
        bypassProxy = getBooleanExtra(BYPASS_SYSTEM_PROXY, true),
        proxyEndpoint = getStringExtra(CUSTOM_PROXY).orEmpty(),
        dohEndpoint = getStringExtra(DNS_ENDPOINT).orEmpty()
    )
}

internal fun loginIntent(context: Context, activityClass: Class<out Activity>): Intent {
    // the login processes keep their own WebView and cannot observe the main process DataStore
    val route = (context as? Activity)?.intent?.authRoute()
        ?: readBootstrapSettingsSnapshotSync(context).networkRoute()
    return Intent(context, activityClass)
        .putExtra(BYPASS_SYSTEM_PROXY, route.bypassProxy)
        .putExtra(CUSTOM_PROXY, route.proxyEndpoint)
        .putExtra(DNS_ENDPOINT, route.dohEndpoint)
}

internal fun Activity.installLoginRoute() {
    val route = intent.authRoute() ?: readBootstrapSettingsSnapshotSync(this).networkRoute()
    route.install()
}

internal suspend fun WebView.loadLoginUrl(url: String) {
    if (WebViewNetworkRoute.awaitCurrent(context)) {
        loadUrl(url)
    } else {
        showNeriViewSnackbar(this, context.getString(CommonR.string.network_webview_proxy_unavailable), Snackbar.LENGTH_INDEFINITE)
    }
}
