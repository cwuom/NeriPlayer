package moe.ouom.neriplayer.network.proxy

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

enum class DnsProvider(val title: String, val url: String, internal vararg val bootstrap: String) {
    Cloudflare("Cloudflare", "https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"),
    Google("Google", "https://dns.google/dns-query", "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888"),
    AdGuard("AdGuard", "https://dns.adguard-dns.com/dns-query", "94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff"),
    Quad9("Quad9", "https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112", "2620:fe::fe")
}

fun parseDohEndpoint(value: String): HttpUrl? = value.trim().toHttpUrlOrNull()?.takeIf {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
}

object NetworkDns : Dns {
    @Volatile
    private var resolver: Dns = Dns.SYSTEM

    internal val usesDnsOverHttps: Boolean get() = resolver !== Dns.SYSTEM

    private val transport by lazy {
        // DNS requests run on a separate dispatcher and never inherit media authentication
        OkHttpClient.Builder()
            .proxySelector(DynamicProxySelector)
            .followRedirects(false)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun configure(url: HttpUrl?) {
        if (url == null) {
            resolver = Dns.SYSTEM
            return
        }
        transport.connectionPool.evictAll()
        resolver = buildDohResolver(url, transport)
    }

    override fun lookup(hostname: String): List<InetAddress> {
        return resolverFor(hostname, Dns.SYSTEM, resolver).lookup(hostname)
    }
}

internal fun resolverFor(hostname: String, systemDns: Dns, encryptedDns: Dns): Dns {
    val host = hostname.trimEnd('.')
    // a proxy must be reachable before it can relay an encrypted DNS request
    val local = '.' !in host || host.endsWith(".local", true) || host.endsWith(".lan", true) ||
        host.endsWith(".localhost", true) || host.endsWith(".home.arpa", true) ||
        DynamicProxySelector.isProxyHost(host)
    return if (local) systemDns else encryptedDns
}

internal fun buildDohResolver(url: HttpUrl, transport: OkHttpClient): Dns {
    val preset = DnsProvider.entries.firstOrNull { it.url == url.toString() }
    val bootstrap = preset?.bootstrap?.map(InetAddress::getByName)
    // resolve the proxy and custom resolver host without recursively querying the active resolver
    val bootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.equals(url.host, ignoreCase = true) && bootstrap != null) bootstrap
            else Dns.SYSTEM.lookup(hostname)
    }
    return DnsOverHttps.Builder()
        .client(transport)
        .url(url)
        .systemDns(bootstrapDns)
        .post(true)
        .build()
}
