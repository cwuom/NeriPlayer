package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerPersistenceReferenceTest {
    @Test
    fun `metadata writes accept only accessible managed references`() {
        assertTrue(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.Accessible
            )
        )
        assertFalse(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.Missing
            )
        )
        assertFalse(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.PermissionLost
            )
        )
        assertFalse(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.ProviderFailure(
                    IllegalStateException("provider unavailable")
                )
            )
        )
    }
}
