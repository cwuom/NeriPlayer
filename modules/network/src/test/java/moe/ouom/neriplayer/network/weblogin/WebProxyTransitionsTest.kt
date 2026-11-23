package moe.ouom.neriplayer.network.weblogin

import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebProxyTransitionsTest {
    private val custom = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("localhost", 8888))

    @Test
    fun `only system clears the Chromium override`() {
        assertNull(webProxyConfig(null))
        assertEquals(listOf("direct://"), webProxyConfig(Proxy.NO_PROXY)?.proxyRules?.map { it.url })
        assertEquals(listOf("http://localhost:8888"), webProxyConfig(custom)?.proxyRules?.map { it.url })
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("::1", 1080))
        assertEquals(listOf("socks5://[::1]:1080"), webProxyConfig(socks)?.proxyRules?.map { it.url })
    }

    @Test
    fun `direct uses an override on cold start and only system clears it`() = runTest {
        var desired: Proxy? = Proxy.NO_PROXY
        val transitions = mutableListOf<Proxy?>()
        val route = WebProxyTransitions({ desired }) { transitions.add(it) }
        for (target in listOf(Proxy.NO_PROXY, custom, Proxy.NO_PROXY, null, null)) {
            desired = target
            assertTrue(route.awaitCurrent())
        }
        assertEquals(listOf(Proxy.NO_PROXY, custom, Proxy.NO_PROXY, null), transitions)
    }

    @Test
    fun `navigation waits for the last requested route when changes overlap`() = runTest {
        var desired: Proxy? = custom
        val pending = CompletableDeferred<Unit>()
        val installing = CompletableDeferred<Unit>()
        val transitions = mutableListOf<Proxy?>()
        val route = WebProxyTransitions({ desired }) {
            transitions.add(it)
            if (it == custom) {
                installing.complete(Unit)
                pending.await()
            }
            true
        }
        val first = async { route.awaitCurrent() }
        installing.await()
        desired = Proxy.NO_PROXY
        val next = async { route.awaitCurrent() }
        pending.complete(Unit)
        assertTrue(first.await())
        assertTrue(next.await())
        assertEquals(listOf(custom, Proxy.NO_PROXY), transitions)
    }

    @Test
    fun `unsupported direct checks the effective system route on every navigation`() = runTest {
        var desired: Proxy? = Proxy.NO_PROXY
        val route = WebProxyTransitions({ desired }) { false }
        assertTrue(route.awaitCurrent { true })
        assertFalse(route.awaitCurrent { false })
        desired = custom
        assertFalse(route.awaitCurrent { true })
    }

    @Test
    fun `unavailable overrides fail before navigation and can be retried`() = runTest {
        var works = false
        var desired: Proxy? = Proxy.NO_PROXY
        val route = WebProxyTransitions({ desired }) { works }
        assertFalse(route.awaitCurrent())
        works = true
        assertTrue(route.awaitCurrent())
        desired = null
        works = false
        assertFalse(route.awaitCurrent())
    }
}
