package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import android.os.SystemClock
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaLyricTextReadbackTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = mock(Context::class.java)

    @Test
    fun `matching sidecar text is accepted without waiting`() {
        val sidecar = temporaryFolder.newFile("song.lrc").apply { writeText(LYRICS) }

        mockStatic(SystemClock::class.java).use { clock ->
            assertTrue(LocalMediaSupport.readTextContentMatchesWithRetry(context, sidecar.absolutePath, LYRICS))
            clock.verifyNoInteractions()
        }
    }

    @Test
    fun `text that lands during the first backoff is accepted on the next attempt`() {
        val sidecar = temporaryFolder.newFile("song.lrc")

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Unit> { SystemClock.sleep(8L) }.thenAnswer {
                sidecar.writeText(LYRICS)
                null
            }

            assertTrue(LocalMediaSupport.readTextContentMatchesWithRetry(context, sidecar.absolutePath, LYRICS))
            clock.verify { SystemClock.sleep(8L) }
            clock.verifyNoMoreInteractions()
        }
    }

    @Test
    fun `stale sidecar text is retried with growing backoff before failing`() {
        val sidecar = temporaryFolder.newFile("song.lrc").apply { writeText("[00:01.00]stale\n") }

        mockStatic(SystemClock::class.java).use { clock ->
            assertFalse(LocalMediaSupport.readTextContentMatchesWithRetry(context, sidecar.absolutePath, LYRICS))
            clock.verify { SystemClock.sleep(8L) }
            clock.verify { SystemClock.sleep(24L) }
            clock.verifyNoMoreInteractions()
        }
    }

    private companion object {
        const val LYRICS = "[00:01.00]你好\n[00:02.00]world\n"
    }
}
