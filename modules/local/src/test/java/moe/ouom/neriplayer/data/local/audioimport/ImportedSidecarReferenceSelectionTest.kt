package moe.ouom.neriplayer.data.local.audioimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedSidecarReferenceSelectionTest {
    @Test
    fun `merged cover falls back to the other scan when one side is missing or album art`() {
        assertEquals(DETAILED_FILE, selectMergedImportedCoverReference(null, DETAILED_FILE))
        assertEquals(DETAILED_FILE, selectMergedImportedCoverReference("  ", DETAILED_FILE))
        assertEquals(DETAILED_FILE, selectMergedImportedCoverReference(ALBUM_ART, DETAILED_FILE))
        assertEquals(QUICK_FILE, selectMergedImportedCoverReference(" $QUICK_FILE ", null))
        assertNull(selectMergedImportedCoverReference(null, ALBUM_ART))
    }

    @Test
    fun `stale quick SAF covers yield to a different detailed cover`() {
        assertEquals(DETAILED_FILE, selectMergedImportedCoverReference(SAF_COVER, DETAILED_FILE))
        assertEquals(SAF_COVER, selectMergedImportedCoverReference(SAF_COVER, SAF_COVER))
        assertEquals(QUICK_FILE, selectMergedImportedCoverReference(QUICK_FILE, DETAILED_FILE))
        assertEquals(
            MEDIA_IMAGE,
            selectMergedImportedCoverReference(MEDIA_IMAGE, DETAILED_FILE)
        )
    }

    @Test
    fun `only non media store content covers are potentially stale`() {
        assertTrue(isPotentiallyStaleSafCoverReference(SAF_COVER))
        assertTrue(isPotentiallyStaleSafCoverReference("CONTENT://com.example.provider/cover/1"))
        assertFalse(isPotentiallyStaleSafCoverReference(MEDIA_IMAGE))
        assertFalse(isPotentiallyStaleSafCoverReference(QUICK_FILE))
    }

    @Test
    fun `imported cover references match only when both are present and equal`() {
        assertFalse(sameImportedCoverReference(null, SAF_COVER))
        assertFalse(sameImportedCoverReference(SAF_COVER, null))
        assertFalse(sameImportedCoverReference(SAF_COVER, QUICK_FILE))
        assertTrue(sameImportedCoverReference(SAF_COVER, SAF_COVER))
    }

    @Test
    fun `an indexed metadata reference wins over name matching`() {
        val references = mapOf("song.mp3.npmeta.json" to "content://tree/meta-0")

        assertEquals(
            "content://tree/indexed",
            selectMetadataSidecarReference(references, "song.mp3", indexedReference = "content://tree/indexed")
        )
        assertEquals(
            "content://tree/meta-0",
            selectMetadataSidecarReference(references, "song.mp3", indexedReference = " ")
        )
    }

    @Test
    fun `metadata sidecar with the lowest provider ordinal is selected`() {
        val references = linkedMapOf(
            "song.mp3.npmeta (2).json" to "content://tree/meta-2",
            "song.mp3.npmeta.pending.json" to "content://tree/meta-pending",
            "other.mp3.npmeta.json" to "content://tree/other",
            "cover.jpg" to "content://tree/cover"
        )

        assertEquals(
            "content://tree/meta-pending",
            selectMetadataSidecarReference(references, "song.mp3")
        )
        assertEquals(
            "content://tree/meta-0",
            selectMetadataSidecarReference(
                references + ("Song.MP3.npmeta.json" to "content://tree/meta-0"),
                "song.mp3"
            )
        )
        assertNull(selectMetadataSidecarReference(references, "missing.mp3"))
        assertNull(selectMetadataSidecarReference(emptyMap(), "song.mp3"))
    }

    @Test
    fun `equal metadata ordinals are broken by file name`() {
        val references = linkedMapOf(
            "song.mp3.npmeta.json" to "content://tree/lower",
            "SONG.mp3.npmeta.json" to "content://tree/upper"
        )

        assertEquals("content://tree/upper", selectMetadataSidecarReference(references, "song.mp3"))
    }

    @Test
    fun `local sidecar metadata index keeps the best reference per audio name`() {
        val index = buildLocalSidecarMetadataIndex(
            linkedMapOf(
                "song.mp3.npmeta (2).json" to "/music/b-numbered",
                "Song.mp3.npmeta.json" to "/music/z-exact",
                "song.mp3.npmeta.json" to "/music/a-exact",
                "SONG.mp3.npmeta.json" to "/music/y-exact",
                "song.mp3.npmeta (3).json" to "/music/0-numbered",
                "live.flac.npmeta.pending.json" to "/music/live-pending",
                "cover.jpg" to "/music/cover.jpg"
            )
        )

        assertEquals(
            mapOf(
                "song.mp3" to "/music/a-exact",
                "live.flac" to "/music/live-pending"
            ),
            index
        )
    }

    private companion object {
        const val QUICK_FILE = "file:///music/Covers/quick.jpg"
        const val DETAILED_FILE = "file:///music/Covers/detailed.jpg"
        const val ALBUM_ART = "content://media/external/audio/albumart/17"
        const val MEDIA_IMAGE = "content://media/external/images/media/5"
        const val SAF_COVER =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fcover.jpg"
    }
}
