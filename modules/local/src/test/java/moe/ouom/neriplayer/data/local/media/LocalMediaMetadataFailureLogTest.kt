package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import android.util.Log
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.io.IOException

class LocalMediaMetadataFailureLogTest {
    private val source: Uri = mock(Uri::class.java).also { uri ->
        doReturn(SOURCE).`when`(uri).toString()
    }

    @Test
    fun `failure logs list every cause with truncated or missing messages and the metrics`() {
        val longMessage = "d".repeat(300)
        val error = IllegalStateException("write failed", IOException(null, RuntimeException(longMessage)))
        val causes = listOf(
            "IllegalStateException:write failed",
            "IOException:<no-message>",
            "RuntimeException:${longMessage.take(240)}"
        ).joinToString(" <- ")

        mockStatic(Log::class.java).use { log ->
            logEditableMetadataFailure("taglib", source, error, metrics = "elapsedMs=12")

            log.verify { Log.w(anyString(), eq(expected("taglib", causes, ", elapsedMs=12")), same(error)) }
        }
    }

    @Test
    fun `failure logs stop after six causes and omit absent metrics`() {
        val error = (7 downTo 1).fold<Int, Throwable>(RuntimeException("level8")) { cause, level ->
            RuntimeException("level$level", cause)
        }
        val causes = (1..6).joinToString(" <- ") { level -> "RuntimeException:level$level" }

        mockStatic(Log::class.java).use { log ->
            logEditableMetadataFailure("staged-copy", source, error)

            log.verify { Log.w(anyString(), eq(expected("staged-copy", causes, "")), same(error)) }
        }
    }

    private fun expected(stage: String, causes: String, metricSuffix: String): String =
        "本地音频元数据回写失败: stage=$stage, uri=$SOURCE, causes=$causes$metricSuffix"

    private companion object {
        const val SOURCE = "content://media/external/audio/media/42"
    }
}
