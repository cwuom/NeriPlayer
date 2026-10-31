package moe.ouom.neriplayer.platform.bilibili.api.stream

import okhttp3.Interceptor
import okhttp3.Response

class BiliStreamCredentialInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val originalUrl = chain.call().request().url
        if (!isBiliStreamHost(originalUrl.host)) return chain.proceed(request)

        // 网络拦截器逐跳检查，避免初始可信流地址把凭据带到外部重定向目标
        val target = request.url
        val safeRequest = if (target.isHttps && isBiliStreamHost(target.host)) request
        else request.newBuilder().removeHeader("Cookie").build()
        return chain.proceed(safeRequest)
    }
}
