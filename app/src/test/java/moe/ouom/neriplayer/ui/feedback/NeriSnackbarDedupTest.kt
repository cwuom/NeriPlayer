package moe.ouom.neriplayer.ui.feedback

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NeriSnackbarDedupTest {
    private val host = SnackbarHostState()

    @Test
    fun `blank messages are not shown`() = runTest {
        assertEquals(SnackbarResult.Dismissed, host.showNeriSnackbar("   "))
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `messages are trimmed and use short duration without an action`() = runTest {
        val shown = backgroundScope.async { host.showNeriSnackbar("  Saved  ") }
        runCurrent()

        val visuals = host.currentSnackbarData!!.visuals
        assertEquals("Saved", visuals.message)
        assertNull(visuals.actionLabel)
        assertEquals(SnackbarDuration.Short, visuals.duration)

        host.currentSnackbarData!!.dismiss()
        assertEquals(SnackbarResult.Dismissed, shown.await())
    }

    @Test
    fun `an action keeps the snackbar until the user responds`() = runTest {
        val shown = backgroundScope.async { host.showNeriSnackbar("Removed", actionLabel = "Undo") }
        runCurrent()

        assertEquals(SnackbarDuration.Indefinite, host.currentSnackbarData!!.visuals.duration)

        host.currentSnackbarData!!.performAction()
        assertEquals(SnackbarResult.ActionPerformed, shown.await())
    }

    @Test
    fun `the currently visible message is not queued again`() = runTest {
        backgroundScope.async { host.showNeriSnackbar("Saved") }
        runCurrent()

        assertEquals(SnackbarResult.Dismissed, host.showNeriSnackbar(" Saved "))
        assertEquals("Saved", host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `a repeated message within the dedup window is skipped`() = runTest {
        val first = backgroundScope.async { host.showNeriSnackbar("Saved") }
        runCurrent()
        host.currentSnackbarData!!.dismiss()
        first.await()

        assertEquals(SnackbarResult.Dismissed, host.showNeriSnackbar("Saved"))
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `a different message is shown after the previous one`() = runTest {
        val first = backgroundScope.async { host.showNeriSnackbar("Saved") }
        runCurrent()
        host.currentSnackbarData!!.dismiss()
        first.await()

        backgroundScope.async { host.showNeriSnackbar("Deleted") }
        runCurrent()

        assertEquals("Deleted", host.currentSnackbarData?.visuals?.message)
    }
}
