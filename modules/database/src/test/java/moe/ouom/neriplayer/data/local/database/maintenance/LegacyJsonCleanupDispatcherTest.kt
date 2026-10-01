package moe.ouom.neriplayer.data.local.database.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyJsonCleanupDispatcherTest {
    @Test
    fun requestBeforeHostBindingIsDeliveredOnce() {
        val dispatcher = LegacyJsonCleanupDispatcher<Any>()
        val context = Any()
        val requests = mutableListOf<Pair<Any, String>>()
        dispatcher.schedule(context, "loaded")

        dispatcher.bind { app, reason -> requests += app to reason }
        dispatcher.bind { app, reason -> requests += app to reason }

        assertEquals(listOf(context to "loaded"), requests)
    }

    @Test
    fun requestsBeforeBindingCoalesceToTheMostRecentReason() {
        val dispatcher = LegacyJsonCleanupDispatcher<Any>()
        val context = Any()
        val requests = mutableListOf<Pair<Any, String>>()
        dispatcher.schedule(context, "loaded")
        dispatcher.schedule(context, "imported")

        dispatcher.bind { app, reason -> requests += app to reason }
        dispatcher.schedule(context, "updated")

        assertEquals(listOf(context to "imported", context to "updated"), requests)
    }
}
