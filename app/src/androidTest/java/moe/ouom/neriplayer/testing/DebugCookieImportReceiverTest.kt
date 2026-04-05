package moe.ouom.neriplayer.testing

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.core.di.AppContainer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Base64

@RunWith(AndroidJUnit4::class)
class DebugCookieImportReceiverTest {
    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearAuthBeforeEach() {
        clearAllAuth()
    }

    @After
    fun clearAuthAfterEach() {
        clearAllAuth()
    }

    @Test
    fun importBiliCookies_savesIntoRepository() {
        val result = sendOrderedImport(
            platform = DebugCookieImportReceiver.PLATFORM_BILI,
            cookie = "SESSDATA=sess-cookie; bili_jct=csrf-token; DedeUserID=123"
        )

        assertEquals(Activity.RESULT_OK, result.code)
        assertEquals("sess-cookie", AppContainer.biliCookieRepo.getCookiesOnce()["SESSDATA"])
    }

    @Test
    fun importNeteaseCookies_savesSanitizedCookies() {
        val result = sendOrderedImport(
            platform = DebugCookieImportReceiver.PLATFORM_NETEASE,
            cookie = "MUSIC_U=music-cookie\n__csrf=csrf-token"
        )

        assertEquals(Activity.RESULT_OK, result.code)
        assertEquals("music-cookie", AppContainer.neteaseCookieRepo.getCookiesOnce()["MUSIC_U"])
        assertEquals("pc", AppContainer.neteaseCookieRepo.getCookiesOnce()["os"])
    }

    @Test
    fun importYouTubeCookies_savesAuthBundle() {
        val result = sendOrderedImport(
            platform = DebugCookieImportReceiver.PLATFORM_YOUTUBE,
            cookie = "SAPISID=sapisid-cookie; SID=sid-cookie; __Secure-1PSIDTS=session-token"
        )

        assertEquals(Activity.RESULT_OK, result.code)
        assertEquals(
            "sapisid-cookie",
            AppContainer.youtubeAuthRepo.getAuthOnce().cookies["SAPISID"]
        )
        assertTrue(AppContainer.youtubeAuthRepo.getAuthOnce().hasLoginCookies())
    }

    @Test
    fun importBiliCookies_acceptsBase64Payload() {
        val encodedCookie = Base64.encodeToString(
            "SESSDATA=encoded-cookie; bili_jct=csrf-token".toByteArray(),
            Base64.NO_WRAP
        )
        val latch = CountDownLatch(1)
        var broadcastResultCode = Activity.RESULT_CANCELED
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                broadcastResultCode = resultCode
                latch.countDown()
            }
        }

        val intent = Intent(DebugCookieImportReceiver.ACTION_IMPORT_AUTH).apply {
            setClass(targetContext, DebugCookieImportReceiver::class.java)
            putExtra(
                DebugCookieImportReceiver.EXTRA_PLATFORM,
                DebugCookieImportReceiver.PLATFORM_BILI
            )
            putExtra(DebugCookieImportReceiver.EXTRA_COOKIE_BASE64, encodedCookie)
        }

        targetContext.sendOrderedBroadcast(
            intent,
            null,
            receiver,
            null,
            Activity.RESULT_CANCELED,
            null,
            null
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(Activity.RESULT_OK, broadcastResultCode)
        assertEquals("encoded-cookie", AppContainer.biliCookieRepo.getCookiesOnce()["SESSDATA"])
    }

    private fun sendOrderedImport(
        platform: String,
        cookie: String
    ): BroadcastResult {
        val latch = CountDownLatch(1)
        var broadcastResultCode = Activity.RESULT_CANCELED
        var broadcastResultData = ""
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                broadcastResultCode = resultCode
                broadcastResultData = resultData.orEmpty()
                latch.countDown()
            }
        }

        val intent = Intent(DebugCookieImportReceiver.ACTION_IMPORT_AUTH).apply {
            setClass(targetContext, DebugCookieImportReceiver::class.java)
            putExtra(DebugCookieImportReceiver.EXTRA_PLATFORM, platform)
            putExtra(DebugCookieImportReceiver.EXTRA_COOKIE, cookie)
        }

        targetContext.sendOrderedBroadcast(
            intent,
            null,
            receiver,
            null,
            Activity.RESULT_CANCELED,
            null,
            null
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return BroadcastResult(code = broadcastResultCode, data = broadcastResultData)
    }

    private fun clearAllAuth() {
        AppContainer.biliCookieRepo.clear()
        AppContainer.neteaseCookieRepo.clear()
        AppContainer.youtubeAuthRepo.clear()
    }

    private data class BroadcastResult(
        val code: Int,
        val data: String
    )
}
