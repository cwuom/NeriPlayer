package moe.ouom.neriplayer.util

import moe.ouom.neriplayer.util.network.hostMatchesAnyDomain as hostMatchesAnyDomainImpl
import moe.ouom.neriplayer.util.network.hostMatchesDomain as hostMatchesDomainImpl
import moe.ouom.neriplayer.util.network.normalizeTrustedHost as normalizeTrustedHostImpl

fun normalizeTrustedHost(host: String?): String {
    return normalizeTrustedHostImpl(host)
}

fun hostMatchesDomain(host: String?, domain: String): Boolean {
    return hostMatchesDomainImpl(host, domain)
}

fun hostMatchesAnyDomain(host: String?, domains: Iterable<String>): Boolean {
    return hostMatchesAnyDomainImpl(host, domains)
}
