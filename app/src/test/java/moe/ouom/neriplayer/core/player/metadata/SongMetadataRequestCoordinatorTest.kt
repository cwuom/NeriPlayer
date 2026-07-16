package moe.ouom.neriplayer.core.player.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMetadataRequestCoordinatorTest {

    @Test
    fun `new manual request supersedes older manual request`() {
        val coordinator = SongMetadataRequestCoordinator()
        val older = requireNotNull(coordinator.begin("song", isAuto = false))
        val newer = requireNotNull(coordinator.begin("song", isAuto = false))

        assertFalse(coordinator.isLatest(older))
        assertTrue(coordinator.isLatest(newer))
    }

    @Test
    fun `automatic request cannot supersede active manual request`() {
        val coordinator = SongMetadataRequestCoordinator()
        val manual = requireNotNull(coordinator.begin("song", isAuto = false))

        assertNull(coordinator.begin("song", isAuto = true))
        assertTrue(coordinator.isLatest(manual))
    }

    @Test
    fun `manual request supersedes automatic request`() {
        val coordinator = SongMetadataRequestCoordinator()
        val automatic = requireNotNull(coordinator.begin("song", isAuto = true))
        val manual = requireNotNull(coordinator.begin("song", isAuto = false))

        assertFalse(coordinator.isLatest(automatic))
        assertTrue(coordinator.isLatest(manual))
    }

    @Test
    fun `completing stale request keeps latest request active`() {
        val coordinator = SongMetadataRequestCoordinator()
        val older = requireNotNull(coordinator.begin("song", isAuto = false))
        val newer = requireNotNull(coordinator.begin("song", isAuto = false))

        coordinator.complete(older)

        assertTrue(coordinator.isLatest(newer))
    }
}
