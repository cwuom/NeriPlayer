package moe.ouom.neriplayer.data.sync.store.secure

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock

class SyncPreferenceRecoveryTest {
    @Test
    fun `sync metadata corruption cannot clear epochs or file generation markers`() {
        val events = mutableListOf<String>()
        val original = IllegalStateException("corrupt")
        val failure = assertThrows(IllegalStateException::class.java) {
            SyncPreferenceRecovery.open(
                create = { events += "create"; throw original },
                delete = { throw AssertionError("sync metadata must not be deleted") },
                onOpenFailure = { events += "open failure" },
                onDeleteFailure = { throw AssertionError("unexpected delete failure") },
                recoverOnFailure = false
            )
        }
        assertSame(original, failure)
        assertEquals(listOf("create", "open failure"), events)
    }

    @Test
    fun `healthy preferences never clear storage`() {
        val preferences = mock(SharedPreferences::class.java)
        assertSame(preferences, SyncPreferenceRecovery.open(
            create = { preferences },
            delete = { throw AssertionError("unexpected delete") },
            onOpenFailure = { throw AssertionError("unexpected open failure", it) },
            onDeleteFailure = { throw AssertionError("unexpected delete failure", it) }
        ))
    }

    @Test
    fun `corrupt preferences clear once and retry exactly once`() {
        val preferences = mock(SharedPreferences::class.java)
        val events = mutableListOf<String>()
        var attempts = 0
        assertSame(preferences, SyncPreferenceRecovery.open(
            create = { events += "create"; if (attempts++ == 0) error("corrupt"); preferences },
            delete = { events += "delete" },
            onOpenFailure = { events += "open:${it.message}" },
            onDeleteFailure = { throw AssertionError("unexpected delete failure", it) }
        ))
        assertEquals(listOf("create", "open:corrupt", "delete", "create"), events)
    }

    @Test
    fun `delete failure is reported and failed recreation reaches caller`() {
        val events = mutableListOf<String>()
        var attempts = 0
        val error = assertThrows(IllegalStateException::class.java) {
            SyncPreferenceRecovery.open(
                create = { events += "create"; error(if (attempts++ == 0) "corrupt" else "unavailable") },
                delete = { events += "delete"; error("denied") },
                onOpenFailure = { events += "open:${it.message}" },
                onDeleteFailure = { events += "delete:${it.message}" }
            )
        }
        assertEquals("unavailable", error.message)
        assertEquals(listOf("create", "open:corrupt", "delete", "delete:denied", "create"), events)
    }
}
