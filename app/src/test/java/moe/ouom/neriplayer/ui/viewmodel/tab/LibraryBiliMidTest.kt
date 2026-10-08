package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBiliMidTest {
    private var navCalls = 0
    private val savedMids = mutableListOf<Long>()

    @Test
    fun `stored DedeUserID is used without asking nav`() = runTest {
        val result = resolve(mapOf("SESSDATA" to "s", "DedeUserID" to "7"), navMid = 42L)

        assertEquals(LibraryBiliMid.Ready(7L), result)
        assertEquals(0, navCalls)
        assertTrue(savedMids.isEmpty())
    }

    @Test
    fun `sessdata only cookie resolves the mid through nav and persists it`() = runTest {
        val result = resolve(mapOf("SESSDATA" to "s"), navMid = 42L)

        assertEquals(LibraryBiliMid.Persisted, result)
        assertEquals(1, navCalls)
        assertEquals(listOf(42L), savedMids)
    }

    @Test
    fun `unusable DedeUserID also falls back to nav`() = runTest {
        assertEquals(LibraryBiliMid.Persisted, resolve(mapOf("SESSDATA" to "s", "DedeUserID" to "abc"), navMid = 42L))
        assertEquals(LibraryBiliMid.Persisted, resolve(mapOf("SESSDATA" to "s", "DedeUserID" to "0"), navMid = 42L))
        assertEquals(listOf(42L, 42L), savedMids)
    }

    @Test
    fun `missing DedeUserID that nav cannot resolve is reported without saving`() = runTest {
        val result = resolve(mapOf("SESSDATA" to "s"), navMid = null)

        assertEquals(LibraryBiliMid.Missing, result)
        assertEquals(1, navCalls)
        assertTrue(savedMids.isEmpty())
    }

    private suspend fun resolve(cookies: Map<String, String>, navMid: Long?) = resolveLibraryBiliMid(
        cookies = cookies,
        fetchLoginMid = {
            navCalls += 1
            navMid
        },
        saveUserMid = { savedMids += it }
    )
}
