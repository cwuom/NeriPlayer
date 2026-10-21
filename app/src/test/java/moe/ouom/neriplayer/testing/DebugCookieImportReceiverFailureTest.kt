package moe.ouom.neriplayer.testing

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.validateAndSanitizeNeteaseCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DebugCookieImportReceiverFailureTest {
    @Test
    fun `bili import storage failure returns cancellation without exposing the cookie`() {
        val repository = mock(BiliCookieRepository::class.java)
        doAnswer { throw IOException("Cannot persist $SYNTHETIC_COOKIE") }
            .`when`(repository).saveCookies(anyMap(), anyLong())

        withRepository("biliCookieRepo", repository) {
            assertStorageFailure(send(DebugCookieImportReceiver.ACTION_IMPORT_AUTH, "bili", SYNTHETIC_COOKIE))
        }
    }

    @Test
    fun `bili clear storage failure returns cancellation without escaping the receiver`() {
        val repository = mock(BiliCookieRepository::class.java)
        doAnswer { throw IOException("Cannot clear $SYNTHETIC_COOKIE") }.`when`(repository).clear()

        withRepository("biliCookieRepo", repository) {
            assertStorageFailure(send(DebugCookieImportReceiver.ACTION_CLEAR_AUTH, "bili"))
        }
    }

    @Test
    fun `accepted netease cookie with failed persistence does not report successful import`() {
        val repository = mock(NeteaseCookieRepository::class.java)
        `when`(repository.validateCookies(anyMap())).thenAnswer {
            validateAndSanitizeNeteaseCookies(it.getArgument<Map<String, String>>(0))
        }
        `when`(repository.saveCookies(anyMap(), anyLong())).thenReturn(false)

        withRepository("neteaseCookieRepo", repository) {
            assertStorageFailure(send(DebugCookieImportReceiver.ACTION_IMPORT_AUTH, "netease", "MUSIC_U=synthetic"))
        }
    }

    @Test
    fun `successful bili import still returns success and only cookie names`() {
        withRepository("biliCookieRepo", mock(BiliCookieRepository::class.java)) {
            val result = send(DebugCookieImportReceiver.ACTION_IMPORT_AUTH, "bili", SYNTHETIC_COOKIE)

            assertEquals(Activity.RESULT_OK, result.code)
            assertEquals("imported bili cookies: SESSDATA", result.data)
            assertFalse(result.data.contains("synthetic-private-cookie"))
        }
    }

    private fun assertStorageFailure(result: BroadcastResult) {
        assertEquals(Activity.RESULT_CANCELED, result.code)
        assertEquals("failed to persist auth", result.data)
        assertFalse(result.data.contains("synthetic-private-cookie"))
    }

    private fun send(action: String, platform: String, cookie: String? = null): BroadcastResult {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = DebugCookieImportReceiver()
        val filter = IntentFilter(action)
        var result: BroadcastResult? = null
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val intent = Intent(action).apply {
                putExtra(DebugCookieImportReceiver.EXTRA_PLATFORM, platform)
                putExtra(DebugCookieImportReceiver.EXTRA_COOKIE, cookie)
            }
            context.sendOrderedBroadcast(
                intent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        result = BroadcastResult(resultCode, resultData.orEmpty())
                    }
                },
                null,
                Activity.RESULT_FIRST_USER,
                "unchanged",
                null
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("ordered broadcast must complete", result)
            return checkNotNull(result)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun withRepository(name: String, repository: Any, block: () -> Unit) {
        val delegate = AppContainer::class.java.getDeclaredField("$name\$delegate")
            .apply { isAccessible = true }.get(null)
        val value = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val previous = value.get(delegate)
        try {
            value.set(delegate, repository)
            block()
        } finally {
            value.set(delegate, previous)
        }
    }

    private data class BroadcastResult(val code: Int, val data: String)

    private companion object {
        const val SYNTHETIC_COOKIE = "SESSDATA=synthetic-private-cookie"
    }
}
