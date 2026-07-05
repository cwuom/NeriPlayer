package moe.ouom.neriplayer.data.auth.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal suspend fun clearWebViewLoginState() {
    withContext(Dispatchers.Main.immediate) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookiesAwait()
        cookieManager.removeSessionCookiesAwait()
        cookieManager.flush()
        WebStorage.getInstance().deleteAllData()
    }
}

private suspend fun CookieManager.removeAllCookiesAwait() {
    suspendCancellableCoroutine { continuation ->
        removeAllCookies {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
    }
}

private suspend fun CookieManager.removeSessionCookiesAwait() {
    suspendCancellableCoroutine { continuation ->
        removeSessionCookies {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
    }
}
