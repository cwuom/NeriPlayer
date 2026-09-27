package moe.ouom.neriplayer.core.api.youtube

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.buildBootstrapAuthFingerprint
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class YouTubePlaybackBootstrapOwnerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `restores same identity snapshot with fresh cookie header`() = runBlocking {
        val auth = YouTubeAuthBundle(cookieHeader = "SAPISID=current; SID=current", userAgent = "test-agent")
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        val stored = bootstrap(auth).copy(cookieHeader = "SAPISID=old")
        YouTubeBootstrapStore(context).save(stored)
        val owner = owner(auth, context)

        val restored = owner.bootstrap(auth)

        assertEquals(stored.apiKey, restored.apiKey)
        assertEquals(stored.authFingerprint, restored.authFingerprint)
        assertTrue(restored.cookieHeader.contains("SAPISID=current"))
        assertSame(restored, owner.current)
    }

    @Test
    fun `auth generation prevents late snapshot from replacing current cache`() = runBlocking {
        val auth = YouTubeAuthBundle(cookieHeader = "SAPISID=current; SID=current")
        val owner = owner(auth)
        val snapshot = bootstrap(auth)

        owner.clear()
        owner.publishRestoredSnapshot(snapshot, generation = 0L)
        assertNull(owner.current)
        owner.publishRestoredSnapshot(snapshot, generation = 1L)
        assertSame(snapshot, owner.current)
        owner.publishRestoredSnapshot(snapshot.copy(apiKey = "other"), generation = 1L)
        assertSame(snapshot, owner.current)
    }

    @Test
    fun `player script timestamp accepts signatureTimestamp and sts fields`() {
        val owner = owner(YouTubeAuthBundle())
        assertEquals(20655, owner.parsePlayerSignatureTimestamp("var config={signatureTimestamp:20655};"))
        assertEquals(21001, owner.parsePlayerSignatureTimestamp("var config={sts:21001};"))
        assertNull(owner.parsePlayerSignatureTimestamp("var config={sts:'missing'};"))
    }

    @Test
    fun `stale bootstrap serves immediately while one refresh is in flight`() = runBlocking {
        val auth = YouTubeAuthBundle(cookieHeader = "SAPISID=current; SID=current")
        val stale = bootstrap(auth).copy(fetchedAtMs = System.currentTimeMillis() - 11L * 60L * 1000L)
        val coordinator = YouTubePlaybackBootstrapCoordinator().also { it.cache = stale }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requests.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("busy")
                .body("".toResponseBody())
                .build()
        }).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val owner = YouTubePlaybackBootstrapOwner(
                okHttpClient = client,
                authProvider = { auth },
                authAutoRefreshManager = null,
                ejsChallengeSolver = null,
                applicationContext = null,
                coordinator = coordinator,
                scope = scope
            )
            assertSame(stale, owner.bootstrap(auth))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertSame(stale, owner.bootstrap(auth))
            assertEquals(1, requests.get())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    private fun owner(auth: YouTubeAuthBundle, context: Context? = null) =
        YouTubePlaybackBootstrapOwner(
            okHttpClient = OkHttpClient(),
            authProvider = { auth },
            authAutoRefreshManager = null,
            ejsChallengeSolver = null,
            applicationContext = context,
            coordinator = YouTubePlaybackBootstrapCoordinator(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )

    private fun bootstrap(auth: YouTubeAuthBundle) = YouTubePlaybackBootstrap(
        apiKey = "api-key",
        webRemixClientVersion = "1.0",
        visitorData = "visitor",
        playerJsUrl = "https://player/base.js",
        cookieHeader = "",
        authFingerprint = auth.buildBootstrapAuthFingerprint(YOUTUBE_MUSIC_ORIGIN),
        sessionIndex = "0",
        userAgent = "test-agent",
        remoteHost = "",
        signatureTimestamp = 20655,
        appInstallData = "",
        coldConfigData = "",
        coldHashData = "",
        hotHashData = "",
        deviceExperimentId = "",
        rolloutToken = "",
        dataSyncId = "",
        delegatedSessionId = "",
        userSessionId = "",
        loggedIn = true,
        fetchedAtMs = System.currentTimeMillis()
    )
}
