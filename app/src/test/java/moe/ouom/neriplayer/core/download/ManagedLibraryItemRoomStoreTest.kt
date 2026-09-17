package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.catalog.preferredManagedLibraryRestoreReference
import moe.ouom.neriplayer.core.download.catalog.shouldRestoreManagedLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLibraryItemRoomStoreTest {
    @Test
    fun `only finalized managed library states restore previews`() {
        listOf("CORE_COMMITTED", "ASSETS_ENRICHING", "DEGRADED_COMPLETE").forEach { state ->
            assertFalse(state, shouldRestoreManagedLibraryItem(state))
        }
        listOf("FINALIZED", "COMPLETE", "COMPLETED").forEach { state ->
            assertTrue(state, shouldRestoreManagedLibraryItem(state))
        }
    }

    @Test
    fun `restore prefers the latest locator hint over a stale canonical uri`() {
        assertEquals(
            "content://new-tree/audio/song.mp3",
            preferredManagedLibraryRestoreReference(
                audioReference = "content://old-tree/audio/song.mp3",
                locatorHint = " content://new-tree/audio/song.mp3 "
            )
        )
    }

    @Test
    fun `restore falls back to canonical uri when locator hint is blank`() {
        assertEquals(
            "/private/downloads/song.mp3",
            preferredManagedLibraryRestoreReference(
                audioReference = " /private/downloads/song.mp3 ",
                locatorHint = "  "
            )
        )
    }

    @Test
    fun `restore returns no uri when both references are blank`() {
        assertEquals(
            null,
            preferredManagedLibraryRestoreReference(
                audioReference = " ",
                locatorHint = null
            )
        )
    }
}
