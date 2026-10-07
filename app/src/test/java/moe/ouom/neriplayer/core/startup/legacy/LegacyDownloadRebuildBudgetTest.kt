package moe.ouom.neriplayer.core.startup.legacy

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class LegacyDownloadRebuildBudgetTest {
    private val rows = linkedMapOf<String, MigrationMetadataEntity>()
    private val dao = mock(SyncMetadataDao::class.java) { call ->
        when (call.method.name) {
            "getMigrationMetadata" -> rows[call.arguments[0] as String]
            "upsertMigrationMetadata" -> {
                val entity = call.arguments[0] as MigrationMetadataEntity
                rows[entity.key] = entity
                Unit
            }
            "deleteMigrationMetadata" -> {
                @Suppress("UNCHECKED_CAST")
                (call.arguments[0] as List<String>).forEach(rows::remove)
                Unit
            }
            else -> null
        }
    }

    private fun launch(token: String) = LegacyDownloadRebuildBudget(dao, LegacyUpgradeLaunchCounter(token))

    @Test
    fun `the upgrade finishes on the third launch that cannot publish`() = runTest {
        repeat(3) { assertFalse(launch("launch-1").recordFailureAndCheckExhausted("operation")) }
        assertFalse(launch("launch-2").recordFailureAndCheckExhausted("operation"))

        assertTrue(launch("launch-3").recordFailureAndCheckExhausted("operation"))
    }

    @Test
    fun `operations keep separate budgets and a publish resets them`() = runTest {
        launch("launch-1").recordFailureAndCheckExhausted("first")
        launch("launch-2").recordFailureAndCheckExhausted("first")
        launch("launch-2").recordFailureAndCheckExhausted("second")

        assertFalse(launch("launch-3").recordFailureAndCheckExhausted("second"))
        launch("launch-3").forget("first")

        assertFalse(launch("launch-4").recordFailureAndCheckExhausted("first"))
        assertEquals(setOf("second", "first"), rows.keys.map { it.substringAfterLast(':') }.toSet())
    }
}
