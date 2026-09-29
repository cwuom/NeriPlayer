package moe.ouom.neriplayer.ui.viewmodel

import moe.ouom.neriplayer.core.model.music.MusicPlatform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSearchRequestCoordinatorTest {

    @Test
    fun `duplicate active request is ignored`() {
        val coordinator = ManualSearchRequestCoordinator()
        val request = ManualSearchRequest("夜曲", MusicPlatform.QQ_MUSIC)

        assertNotNull(coordinator.begin(request))
        assertNull(coordinator.begin(request))
    }

    @Test
    fun `new request invalidates older result`() {
        val coordinator = ManualSearchRequestCoordinator()
        val older = requireNotNull(
            coordinator.begin(ManualSearchRequest("夜曲", MusicPlatform.CLOUD_MUSIC))
        )
        val newer = requireNotNull(
            coordinator.begin(ManualSearchRequest("夜曲", MusicPlatform.QQ_MUSIC))
        )

        assertFalse(coordinator.isLatest(older))
        assertTrue(coordinator.isLatest(newer))
    }

    @Test
    fun `completing stale request keeps newer request active`() {
        val coordinator = ManualSearchRequestCoordinator()
        val older = requireNotNull(
            coordinator.begin(ManualSearchRequest("夜曲", MusicPlatform.CLOUD_MUSIC))
        )
        val newer = requireNotNull(
            coordinator.begin(ManualSearchRequest("夜曲", MusicPlatform.QQ_MUSIC))
        )

        coordinator.complete(older)

        assertTrue(coordinator.isLatest(newer))
    }

    @Test
    fun `invalidating request rejects late result`() {
        val coordinator = ManualSearchRequestCoordinator()
        val token = requireNotNull(
            coordinator.begin(ManualSearchRequest("晴天", MusicPlatform.QQ_MUSIC))
        )

        coordinator.invalidate()

        assertFalse(coordinator.isLatest(token))
    }
}
