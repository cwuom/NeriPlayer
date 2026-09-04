package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertFalse
import org.junit.Test

class ManagedLibraryItemRestorePolicyTest {

    @Test
    fun `unknown and in-progress library states never restore into the catalog`() {
        assertFalse(shouldRestoreManagedLibraryItem(""))
        assertFalse(shouldRestoreManagedLibraryItem("UNKNOWN"))
        assertFalse(shouldRestoreManagedLibraryItem("QUEUED"))
        assertFalse(shouldRestoreManagedLibraryItem("DOWNLOADING"))
    }
}
