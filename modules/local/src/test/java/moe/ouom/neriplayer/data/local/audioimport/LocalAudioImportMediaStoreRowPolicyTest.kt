package moe.ouom.neriplayer.data.local.audioimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioImportMediaStoreRowPolicyTest {
    @Test
    fun `media store rows survive when any audio reference is usable`() {
        assertFalse(shouldKeepMediaStoreAudioRow(false, false, false))
        assertTrue(shouldKeepMediaStoreAudioRow(true, false, false))
        assertTrue(shouldKeepMediaStoreAudioRow(false, true, false))
        assertTrue(shouldKeepMediaStoreAudioRow(false, false, true))
    }

    @Test
    fun `content probes stay inside the leading row window unless a file is resolved`() {
        assertTrue(shouldProbeMediaStoreContentReference(rowOrdinal = 1, hasResolvedFile = false))
        assertTrue(shouldProbeMediaStoreContentReference(rowOrdinal = 256, hasResolvedFile = false))
        assertFalse(shouldProbeMediaStoreContentReference(rowOrdinal = 257, hasResolvedFile = false))
        assertFalse(shouldProbeMediaStoreContentReference(rowOrdinal = 0, hasResolvedFile = false))
        assertTrue(shouldProbeMediaStoreContentReference(rowOrdinal = 0, hasResolvedFile = true))
        assertFalse(
            shouldProbeMediaStoreContentReference(rowOrdinal = 3, hasResolvedFile = false, probeLimit = 2)
        )
    }

    @Test
    fun `rows outside the probe window are trusted while probed rows need a successful probe`() {
        assertTrue(
            hasUsableMediaStoreContentReference(rowOrdinal = 1, hasResolvedFile = true, probeSucceeded = false)
        )
        assertTrue(
            hasUsableMediaStoreContentReference(rowOrdinal = 300, hasResolvedFile = false, probeSucceeded = false)
        )
        assertFalse(
            hasUsableMediaStoreContentReference(rowOrdinal = 10, hasResolvedFile = false, probeSucceeded = false)
        )
        assertTrue(
            hasUsableMediaStoreContentReference(rowOrdinal = 10, hasResolvedFile = false, probeSucceeded = true)
        )
        assertTrue(
            hasUsableMediaStoreContentReference(
                rowOrdinal = 3,
                hasResolvedFile = false,
                probeSucceeded = false,
                probeLimit = 2
            )
        )
    }

    @Test
    fun `media store source references are recognised after trimming and case folding`() {
        assertTrue(isMediaStoreSourceReference(" CONTENT://media/external/audio/media/12 "))
        assertTrue(
            isMediaStoreSourceReference("content://com.android.providers.media.documents/document/audio%3A12")
        )
        assertFalse(
            isMediaStoreSourceReference(
                "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.mp3"
            )
        )
        assertFalse(isMediaStoreSourceReference("/storage/emulated/0/Music/a.mp3"))
        assertFalse(isMediaStoreSourceReference(null))
    }

    @Test
    fun `media store added time prefers date added and falls back to date modified`() {
        assertEquals(1_700_000_000_000L, resolveMediaStoreSourceAddedAt(1_700_000_000L, 1_600_000_000L))
        assertEquals(1_600_000_000_000L, resolveMediaStoreSourceAddedAt(0L, 1_600_000_000L))
        assertEquals(1_600_000_000_000L, resolveMediaStoreSourceAddedAt(null, 1_600_000_000L))
        assertEquals(0L, resolveMediaStoreSourceAddedAt(null, -5L))
        assertEquals(0L, resolveMediaStoreSourceAddedAt(null, null))
    }

    @Test
    fun `scanned added time uses the first positive timestamp`() {
        assertEquals(5L, resolveScannedSourceAddedAt(5L, 7L))
        assertEquals(5L, resolveScannedSourceAddedAt(5L, null))
        assertEquals(7L, resolveScannedSourceAddedAt(0L, 7L))
        assertEquals(7L, resolveScannedSourceAddedAt(null, 7L))
        assertEquals(0L, resolveScannedSourceAddedAt(-1L, 0L))
        assertEquals(0L, resolveScannedSourceAddedAt(0L, null))
        assertEquals(0L, resolveScannedSourceAddedAt(null, -3L))
        assertEquals(0L, resolveScannedSourceAddedAt(null, null))
    }

    @Test
    fun `epoch seconds become millis only when positive and not beyond the future tolerance`() {
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val twoDaysSeconds = 2L * 24L * 60L * 60L

        assertNull((null as Long?).toEpochMillisOrNull())
        assertNull(0L.toEpochMillisOrNull())
        assertEquals(1_700_000_000_000L, 1_700_000_000L.toEpochMillisOrNull())
        assertNull((nowSeconds + twoDaysSeconds).toEpochMillisOrNull())
        assertNull(Long.MAX_VALUE.toEpochMillisOrNull())
    }

    @Test
    fun `millisecond timestamps must be positive and not beyond the future tolerance`() {
        val now = System.currentTimeMillis()

        assertNull((null as Long?).toValidTimestampMsOrNull())
        assertNull((-1L).toValidTimestampMsOrNull())
        assertEquals(1_700_000_000_000L, 1_700_000_000_000L.toValidTimestampMsOrNull())
        assertEquals(now + 60_000L, (now + 60_000L).toValidTimestampMsOrNull())
        assertNull((now + 2 * CREATION_TIME_FUTURE_TOLERANCE_MS).toValidTimestampMsOrNull())
    }
}
