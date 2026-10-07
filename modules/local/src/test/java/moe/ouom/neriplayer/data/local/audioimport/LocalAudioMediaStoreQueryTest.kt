package moe.ouom.neriplayer.data.local.audioimport

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.local.LocalAudioScanPhase
import moe.ouom.neriplayer.data.model.local.LocalAudioScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.CopyOnWriteArrayList

class LocalAudioMediaStoreQueryTest {
    private val uri = mock(Uri::class.java)
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { `when`(it.contentResolver).thenReturn(resolver) }
    private val projection = arrayOf("_id", "title")
    private val selectionArgs = arrayOf("1")
    private val reported = CopyOnWriteArrayList<LocalAudioScanProgress>()
    private val progress = LocalAudioScanProgressEmitter(scanId = 4L, startedAt = 0L) { reported += it }

    @Test
    fun `media store cursors are returned open with their row count`() = runTest {
        val cursor = mock(Cursor::class.java)
        doReturn(5).`when`(cursor).count
        doReturn(cursor).`when`(resolver).query(
            any(), any(), any(), any(), any(), any()
        )

        val result = query()

        assertSame(cursor, result?.cursor)
        assertEquals(5, result?.totalCount)
        verify(resolver).query(
            same(uri),
            same(projection),
            eq("is_music != 0"),
            same(selectionArgs),
            isNull(),
            any(CancellationSignal::class.java)
        )
        verify(cursor, never()).close()
        assertMediaStoreHeartbeat()
    }

    @Test
    fun `providers without a cursor yield no result`() = runTest {
        doReturn(null).`when`(resolver).query(
            any(), any(), any(), any(), any(), any()
        )

        assertNull(query())
        assertMediaStoreHeartbeat()
    }

    @Test
    fun `provider failures are propagated to the scan`() = runTest {
        doThrow(SecurityException("grant revoked")).`when`(resolver).query(
            any(), any(), any(), any(), any(), any()
        )

        val failure = runCatching { query() }.exceptionOrNull()

        val root = generateSequence(failure) { it.cause }.last()
        assertTrue(root is SecurityException)
        assertEquals("grant revoked", root.message)
        assertMediaStoreHeartbeat()
    }

    private suspend fun query(): MediaStoreQueryResult? =
        LocalAudioImportManager.queryMediaStoreWithProgress(
            context,
            uri,
            projection,
            "is_music != 0",
            selectionArgs,
            progress
        )

    private fun assertMediaStoreHeartbeat() {
        assertTrue(reported.isNotEmpty())
        assertTrue(reported.all { it.phase == LocalAudioScanPhase.QUERYING_MEDIA_STORE && it.waitingForProvider })
    }
}
