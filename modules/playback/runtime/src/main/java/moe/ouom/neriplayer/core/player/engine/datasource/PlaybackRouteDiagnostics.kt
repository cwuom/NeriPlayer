package moe.ouom.neriplayer.core.player.engine.datasource

import android.os.SystemClock
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.resolver.netease.isNeteasePlaybackHost
import moe.ouom.neriplayer.platform.bilibili.api.stream.isBiliStreamHost
import okhttp3.Interceptor
import okhttp3.Response

internal object PlaybackRouteDiagnostics : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val source = when {
            isNeteasePlaybackHost(host) -> "Netease"
            host == BILI_AKAMAI_HOST || isBiliStreamHost(host) -> "Bilibili"
            else -> return chain.proceed(request)
        }

        val proxyType = chain.connection()?.route()?.proxy?.type()
        val startedAtMs = SystemClock.elapsedRealtime()
        var responseCode: Int? = null
        try {
            return chain.proceed(request).also { responseCode = it.code }
        } finally {
            NPLogger.i(
                "NERI-PlaybackHttp",
                "$source media request: host=$host, proxyType=${proxyType ?: "unknown"}, " +
                    "responseCode=${responseCode ?: "none"}, responseHeadersMs=${SystemClock.elapsedRealtime() - startedAtMs}"
            )
        }
    }
}
