package moe.ouom.neriplayer.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class BackupImportWithoutPlaylistsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val uri = mock(Uri::class.java)
    private val resolver = mock(ContentResolver::class.java)
    private val storage = mock(SecureTokenStorage::class.java)
    private val context = mock(Context::class.java) { invocation ->
        if (invocation.method.name == "getString") "message" else RETURNS_DEFAULTS.answer(invocation)
    }
    private lateinit var cache: File

    @Before
    fun setUp() {
        cache = temporary.newFolder("cache")
        `when`(context.cacheDir).thenReturn(cache)
        `when`(context.contentResolver).thenReturn(resolver)
    }

    @Test
    fun `backups without playlist data are rejected before any local state is touched`() = runTest {
        listOf(
            """{"version":"2.3","playlists":[]}""",
            """{"version":"2.2","playlists":null,"recentPlays":[]}""",
            """{"version":"2.0","timestamp":5}"""
        ).forEach { json ->
            `when`(resolver.openInputStream(uri)).thenAnswer { ByteArrayInputStream(json.toByteArray()) }

            val failure = BackupManager(context, storageFactory = { storage }).importPlaylists(uri).exceptionOrNull()

            assertTrue(json, failure is IllegalArgumentException)
            assertEquals(json, "No playlist data in backup file", failure?.message)
        }
        verifyNoInteractions(storage)
        assertTrue(cache.walkTopDown().none { it.isFile })
    }

    @Test
    fun `unreadable backup inputs fail without touching local state`() = runTest {
        `when`(resolver.openInputStream(uri)).thenReturn(null)

        val failure = BackupManager(context, storageFactory = { storage }).importPlaylists(uri).exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("Cannot open backup input", failure?.message)
        verifyNoInteractions(storage)
    }
}
