package moe.ouom.neriplayer.data.local.media.metadata

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import moe.ouom.neriplayer.data.local.media.EDITABLE_METADATA_WRITE_BUDGET_MS
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.MockedStatic
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never

class LocalMediaMetadataWriteTimingLogTest {
    private val source: Uri = mock(Uri::class.java).also { uri ->
        doReturn(SOURCE).`when`(uri).toString()
    }

    @Test
    fun `successful writes reaching the budget are reported at info level`() {
        withElapsed(EDITABLE_METADATA_WRITE_BUDGET_MS) { log ->
            LocalMediaSupport.logEditableMetadataWriteTiming(
                source, STARTED_AT, LocalMediaMetadataWriteOutcome.SUCCESS, "direct"
            )
            LocalMediaSupport.logEditableMetadataWriteTiming(
                source, STARTED_AT, LocalMediaMetadataWriteOutcome.SIDECAR_ONLY, "sidecar"
            )

            log.verify { Log.i(anyString(), eq(message("direct", "SUCCESS", 3_000, true)), isNull()) }
            log.verify { Log.i(anyString(), eq(message("sidecar", "SIDECAR_ONLY", 3_000, true)), isNull()) }
            log.verify({ Log.w(anyString(), anyString(), any()) }, never())
        }
    }

    @Test
    fun `failed writes over budget are reported as warnings`() {
        withElapsed(4_500L) { log ->
            LocalMediaSupport.logEditableMetadataWriteTiming(
                source, STARTED_AT, LocalMediaMetadataWriteOutcome.FAILED, "staged"
            )

            log.verify { Log.w(anyString(), eq(message("staged", "FAILED", 4_500, true)), isNull()) }
            log.verify({ Log.i(anyString(), anyString(), any()) }, never())
        }
    }

    @Test
    fun `writes within budget are only debug logged`() {
        withElapsed(EDITABLE_METADATA_WRITE_BUDGET_MS - 1) { log ->
            LocalMediaSupport.logEditableMetadataWriteTiming(
                source, STARTED_AT, LocalMediaMetadataWriteOutcome.NOT_WRITABLE, "direct"
            )

            log.verify { Log.d(anyString(), eq(message("direct", "NOT_WRITABLE", 2_999, false)), isNull()) }
            log.verify({ Log.i(anyString(), anyString(), any()) }, never())
            log.verify({ Log.w(anyString(), anyString(), any()) }, never())
        }
    }

    private fun withElapsed(elapsedMs: Long, block: (MockedStatic<Log>) -> Unit) {
        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(STARTED_AT + elapsedMs)
            mockStatic(Log::class.java).use(block)
        }
    }

    private fun message(mode: String, outcome: String, elapsedMs: Long, overBudget: Boolean): String =
        "local metadata write finished: uri=$SOURCE, mode=$mode, outcome=$outcome, " +
            "elapsedMs=$elapsedMs, overBudget=$overBudget"

    private companion object {
        const val SOURCE = "content://media/external/audio/media/42"
        const val STARTED_AT = 50_000L
    }
}
