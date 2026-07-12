package moe.ouom.neriplayer.util

import moe.ouom.neriplayer.util.network.matchesRootDomain as matchesRootDomainImpl

fun String.matchesRootDomain(rootDomain: String): Boolean {
    return this.matchesRootDomainImpl(rootDomain)
}
