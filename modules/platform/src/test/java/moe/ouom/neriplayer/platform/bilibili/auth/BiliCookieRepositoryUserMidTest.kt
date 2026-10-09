package moe.ouom.neriplayer.platform.bilibili.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliCookieRepositoryUserMidTest {
    private lateinit var repository: BiliCookieRepository

    @Before
    fun setUp() {
        repository = BiliCookieRepository(ApplicationProvider.getApplicationContext())
        repository.clear()
    }

    @After
    fun tearDown() = repository.clear()

    @Test
    fun `mid resolved for the cookies still stored is written back with the original save time`() {
        repository.saveCookies(mapOf("SESSDATA" to "a"), savedAt = 1_000L)
        val requestedWith = repository.getCookiesOnce()

        assertTrue(repository.saveUserMid(42L, requestedWith))

        assertEquals("42", repository.getCookiesOnce()["DedeUserID"])
        assertEquals("a", repository.getCookiesOnce()["SESSDATA"])
        assertEquals(1_000L, repository.getAuthHealthOnce().savedAt)
    }

    @Test
    fun `mid resolved before a relogin or logout never lands on the newer cookies`() {
        repository.saveCookies(mapOf("SESSDATA" to "old"), savedAt = 1_000L)
        val requestedWith = repository.getCookiesOnce()

        repository.saveCookies(mapOf("SESSDATA" to "new"), savedAt = 2_000L)
        assertFalse(repository.saveUserMid(42L, requestedWith))
        assertNull(repository.getCookiesOnce()["DedeUserID"])
        assertEquals("new", repository.getCookiesOnce()["SESSDATA"])

        repository.clear()
        assertFalse(repository.saveUserMid(42L, requestedWith))
        assertTrue(repository.getCookiesOnce()["SESSDATA"].isNullOrBlank())
    }
}
