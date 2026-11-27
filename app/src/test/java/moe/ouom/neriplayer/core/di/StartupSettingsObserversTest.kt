package moe.ouom.neriplayer.core.di

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupSettingsObserversTest {
    private val direct = NetworkRoute(true, "", "")

    @Test
    fun `matching cached proxy value does not apply or invalidate connections`() {
        for (enabled in listOf(false, true)) {
            val events = mutableListOf<String>()

            applyNetworkRouteChange(
                next = direct.copy(bypassProxy = enabled),
                currentValue = direct.copy(bypassProxy = enabled),
                applyValue = { events += "apply:${it.bypassProxy}" },
                onChanged = { events += "changed" }
            )

            assertEquals(emptyList<String>(), events)
        }
    }

    @Test
    fun `different initial proxy value is applied before connections are invalidated`() {
        var appliedValue = true
        val events = mutableListOf<String>()

        applyNetworkRouteChange(
            next = direct.copy(bypassProxy = false),
            currentValue = direct.copy(bypassProxy = appliedValue),
            applyValue = {
                appliedValue = it.bypassProxy
                events += "apply:${it.bypassProxy}"
            },
            onChanged = { events += "changed:$appliedValue" }
        )

        assertEquals(listOf("apply:false", "changed:false"), events)
        assertEquals(false, appliedValue)
    }

    @Test
    fun `resolver and custom proxy changes apply even with the same bypass mode`() {
        val doh = direct.copy(dohEndpoint = "https://resolver.example/dns-query")
        val proxy = doh.copy(proxyEndpoint = "socks5://127.0.0.1:1080")
        var applied = direct
        var invalidations = 0
        for (next in listOf(doh, doh, proxy, direct)) {
            applyNetworkRouteChange(next, applied, { applied = it }, { invalidations++ })
        }
        assertEquals(direct, applied)
        assertEquals(3, invalidations)
    }

    @Test
    fun `proxy changes invalidate once per effective transition including returning to the cached value`() {
        var appliedValue = true
        val events = mutableListOf<String>()

        for (enabled in listOf(true, true, false, false, true, true)) {
            applyNetworkRouteChange(
                next = direct.copy(bypassProxy = enabled),
                currentValue = direct.copy(bypassProxy = appliedValue),
                applyValue = {
                    appliedValue = it.bypassProxy
                    events += "apply:${it.bypassProxy}"
                },
                onChanged = { events += "changed:$appliedValue" }
            )
        }

        assertEquals(
            listOf("apply:false", "changed:false", "apply:true", "changed:true"),
            events
        )
        assertEquals(true, appliedValue)
    }
}
