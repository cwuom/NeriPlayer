package moe.ouom.neriplayer.data.local.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalStorageRootGenerationTest {
    @After
    fun resetState() {
        LocalStorageRootGeneration.resetForTest()
    }

    @Test
    fun `changing the root identity advances generation`() {
        val initial = LocalStorageRootGeneration.current()

        assertEquals(initial, LocalStorageRootGeneration.update(null))
        val firstRoot = LocalStorageRootGeneration.update("content://provider/tree/root-a")
        val sameRoot = LocalStorageRootGeneration.update("content://provider/tree/root-a")
        val secondRoot = LocalStorageRootGeneration.update("content://provider/tree/root-b")

        assertNotEquals(initial, firstRoot)
        assertEquals(firstRoot, sameRoot)
        assertNotEquals(firstRoot, secondRoot)
    }

    @Test
    fun `root identity comparison preserves document id case`() {
        val upper = LocalStorageRootGeneration.update("content://provider/tree/Primary%3ARoot")
        val lower = LocalStorageRootGeneration.update("content://provider/tree/primary%3Aroot")

        assertNotEquals(upper, lower)
    }
}
