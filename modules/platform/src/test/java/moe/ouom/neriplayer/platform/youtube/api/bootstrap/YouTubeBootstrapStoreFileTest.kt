package moe.ouom.neriplayer.platform.youtube.api.bootstrap

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.data.model.youtube.playback.BOOTSTRAP_SNAPSHOT_VERSION_CURRENT
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlaybackBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class YouTubeBootstrapStoreFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val storeFile: File
        get() = File(temporaryFolder.root, "youtube/playback_bootstrap.json")

    private val bootstrap = YouTubePlaybackBootstrap(
        apiKey = "api-key",
        webRemixClientVersion = "1.20260701.00.00",
        visitorData = "visitor-data",
        playerJsUrl = "https://www.youtube.com/s/player/abc/player_ias.vflset/base.js",
        cookieHeader = "SID=secret",
        authFingerprint = "fingerprint-a",
        sessionIndex = "0",
        userAgent = "Mozilla/5.0",
        remoteHost = "203.0.113.7",
        signatureTimestamp = 20345,
        appInstallData = "install-data",
        coldConfigData = "cold-config",
        coldHashData = "cold-hash",
        hotHashData = "hot-hash",
        deviceExperimentId = "device-experiment",
        rolloutToken = "rollout",
        dataSyncId = "datasync",
        delegatedSessionId = "delegated",
        userSessionId = "user-session",
        loggedIn = true,
        fetchedAtMs = 1_700_000_000_000L
    )

    @Before
    fun setUp() {
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
    }

    @Test
    fun `saved bootstraps reload without the transient cookie header until cleared`() {
        val store = YouTubeBootstrapStore(context)

        assertNull(store.load())
        store.save(bootstrap)

        assertEquals(bootstrap.copy(cookieHeader = ""), YouTubeBootstrapStore(context).load())

        store.clear()
        assertFalse(storeFile.exists())
        assertNull(store.load())
    }

    @Test
    fun `snapshots from another schema are ignored and corrupt ones deleted`() {
        val store = YouTubeBootstrapStore(context)
        storeFile.parentFile!!.mkdirs()

        storeFile.writeText(json.encodeToString(bootstrap.copy(version = BOOTSTRAP_SNAPSHOT_VERSION_CURRENT + 1)))
        assertNull(store.load())
        assertTrue(storeFile.exists())

        storeFile.writeText("{broken")
        assertNull(store.load())
        assertFalse(storeFile.exists())
    }

    @Test
    fun `save failures are swallowed`() {
        File(temporaryFolder.root, "youtube").writeText("not a directory")
        val store = YouTubeBootstrapStore(context)

        store.save(bootstrap)

        assertNull(store.load())
    }
}
