package moe.ouom.neriplayer.core.api.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeBootstrapStoreTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val fingerprint = "fingerprint-a"
    private val nowMs = 1_700_000_000_000L

    private fun bootstrap(
        fetchedAtMs: Long = nowMs,
        authFingerprint: String = fingerprint,
        apiKey: String = "api-key",
        playerJsUrl: String = "https://www.youtube.com/s/player/abc/player_ias.vflset/base.js",
        version: Int = BOOTSTRAP_SNAPSHOT_VERSION_CURRENT
    ) = YouTubePlaybackBootstrap(
        apiKey = apiKey,
        webRemixClientVersion = "1.20260701.00.00",
        visitorData = "visitor-data",
        playerJsUrl = playerJsUrl,
        cookieHeader = "SID=secret; HSID=secret",
        authFingerprint = authFingerprint,
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
        fetchedAtMs = fetchedAtMs,
        version = version
    )

    @Test
    fun neverWritesTheCookieHeaderToDisk() {
        val encoded = json.encodeToString(bootstrap())

        // 整串登录 cookie 落盘等于把凭据抄了一份到明文文件里
        assertFalse(encoded.contains("cookieHeader"))
        assertFalse(encoded.contains("secret"))
    }

    @Test
    fun restoresEveryFieldThatDrivesAPlayerRequest() {
        val restored = json.decodeFromString<YouTubePlaybackBootstrap>(
            json.encodeToString(bootstrap())
        )

        assertEquals("api-key", restored.apiKey)
        assertEquals("1.20260701.00.00", restored.webRemixClientVersion)
        assertEquals("visitor-data", restored.visitorData)
        assertEquals(20345, restored.signatureTimestamp)
        assertEquals("datasync", restored.dataSyncId)
        assertTrue(restored.loggedIn)
        // 没落盘的那一项恢复成空串, 由调用方按当前 auth 补齐
        assertEquals("", restored.cookieHeader)
    }

    @Test
    fun toleratesFieldsAddedByALaterBuild() {
        val withUnknownField = """{"apiKey":"api-key","webRemixClientVersion":"1.0",""" +
            """"visitorData":"v","playerJsUrl":"https://player","authFingerprint":"fingerprint-a",""" +
            """"sessionIndex":"0","userAgent":"ua","remoteHost":"","signatureTimestamp":1,""" +
            """"appInstallData":"","coldConfigData":"","coldHashData":"","hotHashData":"",""" +
            """"deviceExperimentId":"","rolloutToken":"","dataSyncId":"","delegatedSessionId":"",""" +
            """"userSessionId":"","loggedIn":false,"fetchedAtMs":1,"version":1,"futureField":"x"}"""

        assertNotNull(json.decodeFromString<YouTubePlaybackBootstrap>(withUnknownField))
    }

    @Test
    fun acceptsAFreshSnapshotForTheSameIdentity() {
        assertTrue(
            isYouTubeBootstrapSnapshotUsable(
                snapshot = bootstrap(fetchedAtMs = nowMs - 60_000L),
                authFingerprint = fingerprint,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun rejectsASnapshotBelongingToAnotherIdentity() {
        assertFalse(
            isYouTubeBootstrapSnapshotUsable(
                snapshot = bootstrap(authFingerprint = "fingerprint-b"),
                authFingerprint = fingerprint,
                nowMs = nowMs
            )
        )
        // 指纹为空说明存档就没记住是谁的, 同样不能用
        assertFalse(
            isYouTubeBootstrapSnapshotUsable(
                snapshot = bootstrap(authFingerprint = ""),
                authFingerprint = "",
                nowMs = nowMs
            )
        )
    }

    @Test
    fun rejectsASnapshotWrittenByADifferentSchema() {
        assertFalse(
            isYouTubeBootstrapSnapshotUsable(
                snapshot = bootstrap(version = BOOTSTRAP_SNAPSHOT_VERSION_CURRENT + 1),
                authFingerprint = fingerprint,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun rejectsASnapshotThatOutlivedItsMaxAge() {
        assertTrue(
            isYouTubeBootstrapSnapshotUsable(
                snapshot = bootstrap(fetchedAtMs = nowMs - BOOTSTRAP_SNAPSHOT_MAX_AGE_MS + 1L),
                authFingerprint = fingerprint,
                nowMs = nowMs
            )
        )
        assertFalse(
            isYouTubeBootstrapSnapshotUsable(
                snapshot = bootstrap(fetchedAtMs = nowMs - BOOTSTRAP_SNAPSHOT_MAX_AGE_MS),
                authFingerprint = fingerprint,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun rejectsASnapshotStampedInTheFuture() {
        // 用户改过系统时间时存档会显得来自未来, 那种情况下年龄算不出来, 老实重拉
        assertFalse(
            isYouTubeBootstrapSnapshotUsable(
                snapshot = bootstrap(fetchedAtMs = nowMs + 60_000L),
                authFingerprint = fingerprint,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun rejectsASnapshotMissingWhatAPlayerRequestNeeds() {
        assertFalse(
            isYouTubeBootstrapSnapshotUsable(
                snapshot = bootstrap(apiKey = ""),
                authFingerprint = fingerprint,
                nowMs = nowMs
            )
        )
        assertFalse(
            isYouTubeBootstrapSnapshotUsable(
                snapshot = bootstrap(playerJsUrl = ""),
                authFingerprint = fingerprint,
                nowMs = nowMs
            )
        )
        assertFalse(
            isYouTubeBootstrapSnapshotUsable(
                snapshot = null,
                authFingerprint = fingerprint,
                nowMs = nowMs
            )
        )
    }
}
