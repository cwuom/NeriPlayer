package moe.ouom.neriplayer.data.listentogether

import android.content.Context
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.ltw.http.DEFAULT_LISTEN_TOGETHER_BASE_URL
import moe.ouom.neriplayer.api.ltw.http.configuredListenTogetherBaseUrlOrNull
import moe.ouom.neriplayer.data.model.config.ListenTogetherConfigSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class ListenTogetherPreferencesTest {
    private val preferences = ListenTogetherPreferences(sharedContext)

    @Before
    fun resetPreferences() = runTest {
        preferences.restore(ListenTogetherConfigSnapshot())
    }

    @Test
    fun `restored defaults read back as the default snapshot`() = runTest {
        assertEquals(ListenTogetherConfigSnapshot(), preferences.snapshot())
        assertEquals("", preferences.workerBaseUrlFlow.first())
        assertEquals("", preferences.workerBaseUrlInputFlow.first())
        assertTrue(preferences.allowMemberControlFlow.first())
        assertTrue(preferences.autoPauseOnMemberChangeFlow.first())
        assertTrue(preferences.shareAudioLinksFlow.first())
    }

    @Test
    fun `custom servers are stored normalized and the default server is never stored`() = runTest {
        preferences.setWorkerBaseUrl(" $SERVER_A ")
        assertEquals(normalizedA, preferences.workerBaseUrlFlow.first())
        assertEquals(normalizedA, preferences.workerBaseUrlInputFlow.first())

        preferences.setWorkerBaseUrl(DEFAULT_LISTEN_TOGETHER_BASE_URL)
        assertEquals("", preferences.workerBaseUrlFlow.first())
        assertEquals(normalizedA, preferences.workerBaseUrlInputFlow.first())

        preferences.setWorkerBaseUrl(SERVER_B)
        assertEquals(configuredListenTogetherBaseUrlOrNull(SERVER_B), preferences.snapshot().workerBaseUrl)
        assertEquals(normalizedA, preferences.snapshot().workerBaseUrlInput)

        preferences.setWorkerBaseUrl("not a url")
        assertEquals("", preferences.workerBaseUrlFlow.first())
    }

    @Test
    fun `server input keeps raw custom text but drops blank and default entries`() = runTest {
        preferences.setWorkerBaseUrlInput("  http://192.168.1.2:8787  ")
        assertEquals("http://192.168.1.2:8787", preferences.workerBaseUrlInputFlow.first())

        preferences.setWorkerBaseUrlInput("half typed")
        assertEquals("half typed", preferences.workerBaseUrlInputFlow.first())

        preferences.setWorkerBaseUrlInput(DEFAULT_LISTEN_TOGETHER_BASE_URL)
        assertEquals("", preferences.workerBaseUrlInputFlow.first())

        preferences.setWorkerBaseUrlInput("half typed")
        preferences.setWorkerBaseUrlInput("   ")
        assertEquals("", preferences.snapshot().workerBaseUrlInput)
    }

    @Test
    fun `user ids and nicknames are created once and then reused`() = runTest {
        val created = preferences.getOrCreateUserUuid()
        assertTrue(created.isNotBlank())
        assertEquals(created, preferences.getOrCreateUserUuid())
        assertEquals(created, preferences.userUuidFlow.first())

        preferences.setUserUuid("  fixed-id  ")
        assertEquals("fixed-id", preferences.getOrCreateUserUuid())

        val nickname = preferences.getOrCreateNickname()
        assertTrue(nickname, nickname.matches(Regex("Neri[0-9A-F]{6}")))
        assertEquals(nickname, preferences.getOrCreateNickname())

        preferences.setNickname("  Listener  ")
        assertEquals("Listener", preferences.getOrCreateNickname())
        assertEquals("Listener", preferences.nicknameFlow.first())
    }

    @Test
    fun `restore normalizes servers inputs and profile fields`() = runTest {
        preferences.restore(
            ListenTogetherConfigSnapshot(
                workerBaseUrl = SERVER_A,
                workerBaseUrlInput = " $normalizedA ",
                userUuid = "  restored-id ",
                nickname = "x".repeat(40),
                allowMemberControl = false,
                autoPauseOnMemberChange = false,
                shareAudioLinks = false
            )
        )

        val restored = preferences.snapshot()
        assertEquals(normalizedA, restored.workerBaseUrl)
        assertEquals(normalizedA, restored.workerBaseUrlInput)
        assertEquals("restored-id", restored.userUuid)
        assertEquals("", restored.nickname)
        assertFalse(restored.allowMemberControl)
        assertFalse(restored.autoPauseOnMemberChange)
        assertFalse(restored.shareAudioLinks)

        preferences.restore(ListenTogetherConfigSnapshot(workerBaseUrlInput = DEFAULT_LISTEN_TOGETHER_BASE_URL))
        assertEquals("", preferences.snapshot().workerBaseUrlInput)

        preferences.restore(
            ListenTogetherConfigSnapshot(workerBaseUrl = DEFAULT_LISTEN_TOGETHER_BASE_URL, workerBaseUrlInput = SERVER_B)
        )
        assertEquals("", preferences.snapshot().workerBaseUrl)
        assertEquals(SERVER_B, preferences.snapshot().workerBaseUrlInput)
    }

    private val normalizedA: String get() = checkNotNull(configuredListenTogetherBaseUrlOrNull(SERVER_A))

    private companion object {
        const val SERVER_A = "https://ltw-a.example.com/"
        const val SERVER_B = "https://ltw-b.example.com/room/"

        // The DataStore delegate keeps its first file for the whole JVM, so every test shares one directory
        val sharedContext: Context by lazy {
            val filesDir: File = Files.createTempDirectory("listen-together-prefs").toFile().apply { deleteOnExit() }
            mock(Context::class.java).also { context ->
                doReturn(filesDir).`when`(context).filesDir
                doReturn(context).`when`(context).applicationContext
            }
        }
    }
}
