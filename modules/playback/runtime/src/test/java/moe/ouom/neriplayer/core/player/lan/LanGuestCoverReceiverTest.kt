package moe.ouom.neriplayer.core.player.lan

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.lan.LanFileResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LanGuestCoverReceiverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun supersededDownloadNeverReplacesTheNewCoverAndDeletesItsFile() = runTest {
        val receiver = LanGuestCoverReceiver(backgroundScope)
        val pending = CompletableDeferred<Unit>()
        val old = temporary.newFile("old.png")
        val latest = temporary.newFile("latest.png")
        val applied = mutableListOf<File>()
        receiver.show(resource("old"), fetch = { withContext(NonCancellable) { pending.await(); old } }, applied::add)
        runCurrent()
        receiver.show(resource("latest"), fetch = { latest }, applied::add)
        runCurrent()
        pending.complete(Unit)
        runCurrent()

        assertEquals(listOf(latest), applied)
        assertFalse(old.exists())
        assertTrue(latest.exists())
        assertEquals(latest, receiver.clear())
        assertFalse(latest.exists())
    }

    @Test fun retriesATransferFailureAndReusesTheReceivedFileForTheSameCover() = runTest {
        val receiver = LanGuestCoverReceiver(backgroundScope)
        val descriptor = resource("cover")
        val file = temporary.newFile("cover.png")
        val applied = mutableListOf<File>()
        var fetches = 0
        receiver.show(descriptor, fetch = {
            if (++fetches == 1) throw IOException("temporarily unavailable")
            file
        }, applied::add)
        runCurrent()
        assertTrue(applied.isEmpty())
        advanceTimeBy(500)
        runCurrent()

        receiver.show(descriptor, fetch = { error("duplicate cover must reuse the file") }, applied::add)
        assertEquals(2, fetches)
        assertEquals(listOf(file, file), applied)
        receiver.clear()
    }

    private fun resource(id: String) = LanFileResource(id, "a".repeat(64), 16, "image/png")
}
