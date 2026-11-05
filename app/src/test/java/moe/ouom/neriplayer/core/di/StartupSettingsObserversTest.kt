package moe.ouom.neriplayer.core.di

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupSettingsObserversTest {
    @Test
    fun `matching cached proxy value does not apply or invalidate connections`() {
        for (enabled in listOf(false, true)) {
            val events = mutableListOf<String>()

            applyBypassProxyChange(
                enabled = enabled,
                currentValue = enabled,
                applyValue = { events += "apply:$it" },
                onChanged = { events += "changed" }
            )

            assertEquals(emptyList<String>(), events)
        }
    }

    @Test
    fun `different initial proxy value is applied before connections are invalidated`() {
        var appliedValue = true
        val events = mutableListOf<String>()

        applyBypassProxyChange(
            enabled = false,
            currentValue = appliedValue,
            applyValue = {
                appliedValue = it
                events += "apply:$it"
            },
            onChanged = { events += "changed:$appliedValue" }
        )

        assertEquals(listOf("apply:false", "changed:false"), events)
        assertEquals(false, appliedValue)
    }

    @Test
    fun `proxy changes invalidate once per effective transition including returning to the cached value`() {
        var appliedValue = true
        val events = mutableListOf<String>()

        for (enabled in listOf(true, true, false, false, true, true)) {
            applyBypassProxyChange(
                enabled = enabled,
                currentValue = appliedValue,
                applyValue = {
                    appliedValue = it
                    events += "apply:$it"
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
