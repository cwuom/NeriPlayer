package moe.ouom.neriplayer.data.local.audioimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HydratedLocalCoverSelectionTest {
    @Test
    fun `a directly usable sidecar cover outranks every other candidate`() {
        assertEquals(
            "file:///music/Covers/A.jpg",
            select(sidecar = " file:///music/Covers/A.jpg ", existing = EXISTING, rebound = REBOUND, fallback = FALLBACK)
        )
        assertEquals(
            "content://media/external/images/media/4",
            select(sidecar = "content://media/external/images/media/4", rebound = REBOUND)
        )
    }

    @Test
    fun `a stale saf sidecar yields to the rebound and then to a rescanned cover`() {
        assertEquals(REBOUND, select(sidecar = STALE_SAF, existing = EXISTING, rebound = REBOUND, fallback = FALLBACK))
        assertEquals(EXISTING, select(sidecar = STALE_SAF, existing = EXISTING, fallback = FALLBACK))
    }

    @Test
    fun `an existing cover equal to the stale sidecar yields to the metadata fallback`() {
        assertEquals(FALLBACK, select(sidecar = STALE_SAF, existing = STALE_SAF, fallback = FALLBACK))
        assertEquals(STALE_FALLBACK, select(sidecar = STALE_SAF, existing = STALE_SAF, fallback = STALE_FALLBACK))
    }

    @Test
    fun `without better candidates the stale references are kept`() {
        assertEquals(STALE_SAF, select(sidecar = STALE_SAF, existing = STALE_SAF))
        assertEquals(STALE_SAF, select(sidecar = STALE_SAF))
        assertNull(select(sidecar = " ", existing = "content://media/external/audio/albumart/2", fallback = null))
    }

    @Test
    fun `timestamp sources distinguish modification times from media store added times`() {
        listOf(" mtime ", "saf_last_modified", "MTIME_FALLBACK", "mediastore_date_modified").forEach { source ->
            assertTrue(source, isModificationTimestampSource(source))
            assertTrue(source, isNonCreationTimestampSource(source))
        }
        assertFalse(isModificationTimestampSource("MEDIASTORE_DATE_ADDED"))
        assertTrue(isNonCreationTimestampSource(" mediastore_date_added "))
        listOf(null, "", "EXIF", "MEDIASTORE_DATE_TAKEN").forEach { source ->
            assertFalse(source.toString(), isModificationTimestampSource(source))
            assertFalse(source.toString(), isNonCreationTimestampSource(source))
        }
    }

    private fun select(
        sidecar: String? = null,
        existing: String? = null,
        rebound: String? = null,
        fallback: String? = null
    ) = selectHydratedLocalCoverReference(
        sidecarCover = sidecar,
        existingCover = existing,
        reboundCover = rebound,
        metadataFallbackCover = fallback
    )

    private companion object {
        const val STALE_SAF = "content://com.android.externalstorage.documents/document/primary%3AA.jpg"
        const val STALE_FALLBACK = "content://com.example.provider/cover/1"
        const val REBOUND = "content://com.android.externalstorage.documents/document/primary%3AB.jpg"
        const val EXISTING = "file:///data/local_audio_covers/A.jpg"
        const val FALLBACK = "https://img.example.com/a.jpg"
    }
}
