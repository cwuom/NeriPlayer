package moe.ouom.neriplayer.data.auth.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCookieRotatorTest {

    private val validSession = mapOf(
        "__Secure-1PSID" to "1psid-value",
        "__Secure-3PSID" to "3psid-value",
        "SAPISID" to "sapisid-value",
        "APISID" to "apisid-value"
    )

    @Test
    fun requiresBothASessionCookieAndABindingCookie() {
        assertTrue(hasYouTubeRotationPrerequisites(validSession))
        // 只有会话主体, 服务端认不出是同一个客户端
        assertFalse(hasYouTubeRotationPrerequisites(mapOf("__Secure-1PSID" to "1psid-value")))
        // 只有绑定项, 没有会话主体
        assertFalse(hasYouTubeRotationPrerequisites(mapOf("SAPISID" to "sapisid-value")))
        assertFalse(hasYouTubeRotationPrerequisites(emptyMap()))
    }

    @Test
    fun treatsBlankValuesAsMissing() {
        assertFalse(
            hasYouTubeRotationPrerequisites(
                mapOf("__Secure-1PSID" to "", "SAPISID" to "sapisid-value")
            )
        )
    }

    @Test
    fun buildsTheCookieHeaderInAStableOrderAndSkipsBlanks() {
        val header = buildYouTubeRotationCookieHeader(
            mapOf(
                "SAPISID" to "sapisid-value",
                "__Secure-1PSID" to "1psid-value",
                "VISITOR_INFO1_LIVE" to "visitor-value",
                "HSID" to ""
            )
        )

        // 顺序按固定清单走, 无关 cookie 不带上, 空值不占位
        assertEquals("SAPISID=sapisid-value; __Secure-1PSID=1psid-value", header)
    }

    @Test
    fun readsTheRotationIntervalGoogleDeclares() {
        assertEquals(
            600_000L,
            parseYouTubeRotationIntervalMs(""")]}'[["identity.hfcr",600],["di",42]]""")
        )
        assertEquals(
            300_000L,
            parseYouTubeRotationIntervalMs(""")]}'[["identity.hfcr", 300],["di",7]]""")
        )
    }

    @Test
    fun fallsBackToTheDocumentedIntervalWhenTheBodyIsUnparseable() {
        assertEquals(ROTATION_DEFAULT_INTERVAL_MS, parseYouTubeRotationIntervalMs(""))
        assertEquals(ROTATION_DEFAULT_INTERVAL_MS, parseYouTubeRotationIntervalMs("<html>nope</html>"))
        assertEquals(
            ROTATION_DEFAULT_INTERVAL_MS,
            parseYouTubeRotationIntervalMs(""")]}'[["identity.hfcr",0]]""")
        )
    }

    @Test
    fun collectsOnlyTheTwoRotatedCookies() {
        val rotated = collectRotatedYouTubeCookies(
            listOf(
                "__Secure-1PSIDTS=sidts-1; Domain=.google.com; Path=/; Secure; HttpOnly",
                "__Secure-3PSIDTS=sidts-3; Domain=.google.com; Path=/; Secure; HttpOnly",
                "NID=nid-value; Domain=.google.com; Path=/",
                "SIDCC=sidcc-value; Domain=.google.com; Path=/"
            )
        )

        assertEquals(
            mapOf("__Secure-1PSIDTS" to "sidts-1", "__Secure-3PSIDTS" to "sidts-3"),
            rotated
        )
    }

    @Test
    fun neverTreatsAnExpiryInstructionAsAFreshCredential() {
        val rotated = collectRotatedYouTubeCookies(
            listOf(
                "__Secure-1PSIDTS=; Max-Age=0; Domain=.google.com; Path=/",
                "__Secure-3PSIDTS=dead; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
            )
        )

        assertTrue(rotated.isEmpty())
    }

    @Test
    fun throttlesRepeatRotationsButAlwaysAllowsTheFirstOne() {
        assertTrue(shouldRotateYouTubeCookies(lastRotatedAtMs = 0L, nowMs = 1_000L))
        assertFalse(shouldRotateYouTubeCookies(lastRotatedAtMs = 1_000L, nowMs = 30_000L))
        assertTrue(shouldRotateYouTubeCookies(lastRotatedAtMs = 1_000L, nowMs = 61_000L))
    }

    @Test
    fun reportsOnlyCookiesWhoseValueActuallyChanged() {
        val changed = collectChangedRotatedCookies(
            currentCookies = mapOf(
                "__Secure-1PSIDTS" to "same",
                "__Secure-3PSIDTS" to "old"
            ),
            rotatedCookies = mapOf(
                "__Secure-1PSIDTS" to "same",
                "__Secure-3PSIDTS" to "new"
            )
        )

        // 服务端原样回旧值时不该当成一次成功轮换
        assertEquals(mapOf("__Secure-3PSIDTS" to "new"), changed)
    }

    @Test
    fun retriesAtTheNormalCadenceWhileFailuresLookLikeNoise() {
        // 前几次失败当抖动处理, 不该因此拉长正常轮换
        for (rejections in 0 until ROTATION_REJECTIONS_BEFORE_BACKOFF) {
            assertEquals(ROTATION_MIN_INTERVAL_MS, nextYouTubeRotationBackoffMs(rejections))
        }
    }

    @Test
    fun backsOffExponentiallyOnceFailuresLookPersistent() {
        assertEquals(
            ROTATION_MIN_INTERVAL_MS * 2,
            nextYouTubeRotationBackoffMs(ROTATION_REJECTIONS_BEFORE_BACKOFF)
        )
        assertEquals(
            ROTATION_MIN_INTERVAL_MS * 4,
            nextYouTubeRotationBackoffMs(ROTATION_REJECTIONS_BEFORE_BACKOFF + 1)
        )
        assertEquals(
            ROTATION_MIN_INTERVAL_MS * 8,
            nextYouTubeRotationBackoffMs(ROTATION_REJECTIONS_BEFORE_BACKOFF + 2)
        )
    }

    @Test
    fun neverBacksOffPastTheCapEvenAfterALongDeadStreak() {
        // 端点对某些账号恒回 403, 退避要收敛而不是越滚越大直到溢出
        assertEquals(ROTATION_MAX_BACKOFF_MS, nextYouTubeRotationBackoffMs(100))
        assertEquals(ROTATION_MAX_BACKOFF_MS, nextYouTubeRotationBackoffMs(Int.MAX_VALUE))
    }

    @Test
    fun honoursACallerSuppliedIntervalWhenDecidingToRotate() {
        val backoffMs = nextYouTubeRotationBackoffMs(ROTATION_REJECTIONS_BEFORE_BACKOFF)

        assertFalse(
            shouldRotateYouTubeCookies(
                lastRotatedAtMs = 1_000L,
                nowMs = 1_000L + backoffMs - 1L,
                minIntervalMs = backoffMs
            )
        )
        assertTrue(
            shouldRotateYouTubeCookies(
                lastRotatedAtMs = 1_000L,
                nowMs = 1_000L + backoffMs,
                minIntervalMs = backoffMs
            )
        )
    }

    @Test
    fun countsAFirstTimeCookieAsChanged() {
        val changed = collectChangedRotatedCookies(
            currentCookies = emptyMap(),
            rotatedCookies = mapOf("__Secure-1PSIDTS" to "fresh")
        )

        assertEquals(mapOf("__Secure-1PSIDTS" to "fresh"), changed)
    }
}
