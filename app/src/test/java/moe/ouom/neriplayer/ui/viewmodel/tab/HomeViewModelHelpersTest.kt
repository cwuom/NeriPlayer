package moe.ouom.neriplayer.ui.viewmodel.tab

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class HomeViewModelHelpersTest {

    private val context: Context = mock(Context::class.java) { invocation ->
        if (invocation.method.name == "getString") invocation.arguments.joinToString("|") else null
    }

    @Test
    fun `recommend falls back only for redirect and login codes`() {
        assertTrue(shouldFallbackRecommend(301))
        assertTrue(shouldFallbackRecommend(50000005))
        assertFalse(shouldFallbackRecommend(200))
    }

    @Test
    fun `retry returns the first successful attempt`() = runTest {
        var attempts = 0

        val result = fetchWithRetry("section", maxAttempts = 3) {
            attempts++
            if (attempts < 2) throw IOException("flaky")
            listOf("a", "b")
        }

        assertEquals(RetryLoadResult.Success(listOf("a", "b")), result)
        assertEquals(2, attempts)
    }

    @Test
    fun `retry reports the last failure after all attempts`() = runTest {
        var attempts = 0

        val result = fetchWithRetry<String>("section", maxAttempts = 2) {
            attempts++
            throw IOException("failure $attempts")
        }

        assertEquals(2, attempts)
        assertEquals("failure 2", (result as RetryLoadResult.Failure).throwable.message)
    }

    @Test
    fun `retry uses the default attempt budget and succeeds immediately`() = runTest {
        var attempts = 0

        val result = fetchWithRetry("section") {
            attempts++
            listOf(1)
        }

        assertEquals(RetryLoadResult.Success(listOf(1)), result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retry rethrows cancellation and rejects an empty budget`() = runTest {
        var attempts = 0
        val cancellation = runCatching {
            fetchWithRetry<String>("section", maxAttempts = 3) {
                attempts++
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        val emptyBudget = runCatching {
            fetchWithRetry<String>("section", maxAttempts = 0) { emptyList() }
        }.exceptionOrNull()

        assertTrue(cancellation is CancellationException)
        assertEquals(1, attempts)
        assertTrue(emptyBudget is IllegalArgumentException)
    }

    @Test
    fun `home errors map network api and unknown failures`() {
        assertEquals(
            "${CoreCommonR.string.home_error_network}|offline",
            buildHomeErrorMessage(IOException("offline"), context)
        )
        assertEquals(
            "${CoreCommonR.string.home_error_network}|IOException",
            buildHomeErrorMessage(IOException(), context)
        )
        assertEquals(
            "${CoreCommonR.string.home_login_required}",
            buildHomeErrorMessage(ApiCodeException(50000005), context)
        )
        assertEquals(
            "${CoreCommonR.string.error_api_code}|405",
            buildHomeErrorMessage(ApiCodeException(405), context)
        )
        assertEquals(
            "${CoreCommonR.string.home_error_unknown}|IllegalStateException",
            buildHomeErrorMessage(IllegalStateException(), context)
        )
        assertEquals(
            "${CoreCommonR.string.home_error_unknown}|bad state",
            buildHomeErrorMessage(IllegalStateException("bad state"), context)
        )
    }
}
