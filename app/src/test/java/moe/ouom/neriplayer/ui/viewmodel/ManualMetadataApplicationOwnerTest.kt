package moe.ouom.neriplayer.ui.viewmodel

import kotlinx.coroutines.Job
import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualMetadataApplicationOwnerTest {
    @Test
    fun `cancelling preparation rejects late completion and progress`() {
        val owner = ManualMetadataApplicationOwner()
        val job = Job()
        var progress: (SongMetadataApplyStage) -> Unit = {}
        var complete: (Boolean) -> Unit = {}
        var applied = 0
        owner.start(onComplete = { if (it) applied++ }) { onProgress, onComplete ->
            progress = onProgress
            complete = onComplete
            job
        }

        assertTrue(owner.cancel())
        progress(SongMetadataApplyStage.APPLYING)
        complete(true)

        assertTrue(job.isCancelled)
        assertFalse(owner.state.value.isRunning)
        assertFalse(owner.state.value.completed)
        assertFalse(owner.state.value.failed)
        assertEquals(0, applied)
    }

    @Test
    fun `committing cannot be cancelled and success becomes visible before dismissal`() {
        val owner = ManualMetadataApplicationOwner()
        val job = Job()
        var complete: (Boolean) -> Unit = {}
        owner.start { onProgress, onComplete ->
            onProgress(SongMetadataApplyStage.APPLYING)
            complete = onComplete
            job
        }

        assertFalse(owner.cancel())
        assertFalse(job.isCancelled)
        assertTrue(owner.state.value.isRunning)
        complete(true)

        assertTrue(owner.state.value.completed)
        assertFalse(owner.state.value.failed)
        assertFalse(owner.state.value.isRunning)
    }

    @Test
    fun `failure keeps the operation available for retry`() {
        val owner = ManualMetadataApplicationOwner()
        var complete: (Boolean) -> Unit = {}
        owner.start { _, onComplete -> complete = onComplete; Job() }
        complete(false)

        assertTrue(owner.state.value.failed)
        assertFalse(owner.state.value.completed)
        assertTrue(owner.start { _, _ -> Job() })
        assertTrue(owner.state.value.isRunning)
        assertFalse(owner.state.value.failed)
    }

    @Test
    fun `cancelled request cannot finish a newer application`() {
        val owner = ManualMetadataApplicationOwner()
        var staleComplete: (Boolean) -> Unit = {}
        owner.start { _, onComplete -> staleComplete = onComplete; Job() }
        owner.cancel()
        owner.start { onProgress, _ ->
            onProgress(SongMetadataApplyStage.FETCHING_LYRICS)
            Job()
        }

        staleComplete(true)

        assertTrue(owner.state.value.isRunning)
        assertEquals(SongMetadataApplyStage.FETCHING_LYRICS, owner.state.value.stage)
        assertFalse(owner.state.value.completed)
    }

    @Test
    fun `duplicate selection does not launch a second application`() {
        val owner = ManualMetadataApplicationOwner()
        var launches = 0
        val launch: ((SongMetadataApplyStage) -> Unit, (Boolean) -> Unit) -> Job = { _, _ ->
            launches++
            Job()
        }

        assertTrue(owner.start(launch = launch))
        assertFalse(owner.start(launch = launch))
        assertEquals(1, launches)
    }
}
