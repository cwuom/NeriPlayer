package moe.ouom.neriplayer.data.network

import okhttp3.OkHttpClient

/** 沿用宿主的代理、认证和流量统计配置，每次取用当前客户端 */
object DataHttpClients {
    @Volatile
    private var provider: (() -> OkHttpClient)? = null

    fun bind(provider: () -> OkHttpClient) {
        this.provider = provider
    }

    val shared: OkHttpClient
        get() = checkNotNull(provider) { "Data HTTP client provider has not been installed" }.invoke()
}
