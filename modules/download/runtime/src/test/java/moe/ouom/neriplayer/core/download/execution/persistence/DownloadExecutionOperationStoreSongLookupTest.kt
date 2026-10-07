package moe.ouom.neriplayer.core.download.execution.persistence

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions

class DownloadExecutionOperationStoreSongLookupTest {
    private val context = mock(Context::class.java).also { context ->
        `when`(context.applicationContext).thenReturn(context)
    }
    private val journal = mock(DownloadExecutionOperationJournal::class.java)
    private val store = DownloadExecutionOperationStore { journal }

    @Test
    fun `song lookups trim the key and skip the journal for blank keys`() {
        `when`(journal.findOperationIdForSong(context, "song-key")).thenReturn("op-1")
        `when`(journal.findOperationIdsForSong(context, "song-key")).thenReturn(listOf("op-1", "op-2", "op-1"))

        assertEquals("op-1", store.findOperationIdForSong(context, " song-key "))
        assertEquals(listOf("op-1", "op-2"), store.findOperationIdsForSong(context, " song-key "))
        assertNull(store.findOperationIdForSong(context, "   "))
        assertEquals(emptyList<String>(), store.findOperationIdsForSong(context, ""))

        verify(journal).findOperationIdForSong(context, "song-key")
        verify(journal).findOperationIdsForSong(context, "song-key")
        verifyNoMoreInteractions(journal)
    }

    @Test
    fun `clearing user stops without usable stable keys does nothing`() {
        assertFalse(store.clearUserStopForStableKeys(context, listOf(" ", "")))
        assertFalse(store.clearUserStopForStableKeys(context, emptyList()))

        verifyNoInteractions(journal)
    }
}
