package moe.ouom.neriplayer.core.download.catalog.preview

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPreviewClipChecksTest {

    @Test
    fun `short legacy audio against a full catalog length is a preview clip`() {
        assertTrue(isLegacyPreviewClipDuration(catalogDurationMs = 233_000L, audioDurationMs = 30_000L))
        assertTrue(isLegacyPreviewClipDuration(catalogDurationMs = 126_000L, audioDurationMs = 45_000L))
        assertFalse(isLegacyPreviewClipDuration(catalogDurationMs = 233_000L, audioDurationMs = 232_400L))
        assertFalse(isLegacyPreviewClipDuration(catalogDurationMs = 40_000L, audioDurationMs = 40_000L))
        assertFalse(isLegacyPreviewClipDuration(catalogDurationMs = 600_000L, audioDurationMs = 120_000L))
        assertFalse(isLegacyPreviewClipDuration(catalogDurationMs = 233_000L, audioDurationMs = null))
        assertFalse(isLegacyPreviewClipDuration(catalogDurationMs = 0L, audioDurationMs = 30_000L))
    }

    @Test
    fun `only finalized legacy netease downloads are checked`() {
        val legacy = DownloadedAudioMetadata(
            identityAlbum = "netease",
            durationMs = 233_000L,
            downloadFinalized = true,
            createdAtSource = "LEGACY_V15"
        )

        assertEquals(
            LegacyPreviewClipCandidate("ref", 1_024L, 233_000L),
            legacyPreviewClipCandidate("ref", 1_024L, legacy)
        )
        assertEquals(
            LegacyPreviewClipCandidate("ref", 1_024L, 233_000L),
            legacyPreviewClipCandidate("ref", 1_024L, legacy.copy(identityAlbum = null, channelId = "netease"))
        )
        assertNull(legacyPreviewClipCandidate("ref", 1_024L, legacy.copy(createdAtSource = "CORE_COMMIT")))
        assertNull(legacyPreviewClipCandidate("ref", 1_024L, legacy.copy(downloadFinalized = null)))
        assertNull(legacyPreviewClipCandidate("ref", 1_024L, legacy.copy(durationMs = 0L)))
        assertNull(legacyPreviewClipCandidate("ref", 1_024L, legacy.copy(channelId = "bilibili")))
        assertNull(legacyPreviewClipCandidate("ref", 1_024L, legacy.copy(identityAlbum = null)))
        listOf(" ", "local").forEach { placeholderChannel ->
            assertEquals(
                "channel=$placeholderChannel falls back to the identity album",
                LegacyPreviewClipCandidate("ref", 1_024L, 233_000L),
                legacyPreviewClipCandidate("ref", 1_024L, legacy.copy(channelId = placeholderChannel))
            )
        }
    }

    @Test
    fun `incomplete enumeration keeps stored checks without probing`() = runBlocking {
        val stored = mapOf("kept" to LegacyPreviewClipCheck(1_024L, 30_000L, 233_000L))

        val result = runLegacyPreviewClipPass(
            stored = stored,
            candidates = null,
            probeDurationMs = { error("must not probe while the library listing is incomplete") }
        )

        assertEquals(LegacyPreviewClipPassResult(stored, changed = false, probedCount = 0, remainingCount = 0), result)
    }

    @Test
    fun `each pass probes a bounded batch and reports whether records changed`() = runBlocking {
        val candidates = (1..5).map { index -> LegacyPreviewClipCandidate("ref-$index", 1_000L + index, 233_000L) }
        val probed = mutableListOf<String>()

        val first = runLegacyPreviewClipPass(
            stored = emptyMap(),
            candidates = candidates,
            probeDurationMs = { reference -> probed += reference; 30_000L },
            probesPerPass = 2
        )
        val settled = runLegacyPreviewClipPass(
            stored = first.checks,
            candidates = candidates.take(2),
            probeDurationMs = { error("measured files must not be probed again") },
            probesPerPass = 2
        )

        assertEquals(listOf("ref-1", "ref-2"), probed)
        assertEquals(setOf("ref-1", "ref-2"), first.checks.keys)
        assertTrue(first.changed)
        assertEquals(2, first.probedCount)
        assertEquals(3, first.remainingCount)
        assertFalse(settled.changed)
        assertEquals(first.checks, settled.checks)
    }

    @Test
    fun `stored checks are reused until the file or catalog length changes`() {
        val measured = LegacyPreviewClipCheck(sizeBytes = 1_024L, audioDurationMs = 30_000L, catalogDurationMs = 233_000L)
        val unreadable = LegacyPreviewClipCheck(sizeBytes = 2_048L, audioDurationMs = null, catalogDurationMs = 180_000L)
        val stored = mapOf("same" to measured, "redownloaded" to measured, "unreadable" to unreadable, "deleted" to measured)

        val plan = planLegacyPreviewClipChecks(
            stored = stored,
            candidates = listOf(
                LegacyPreviewClipCandidate("same", 1_024L, 233_000L),
                LegacyPreviewClipCandidate("redownloaded", 9_999_999L, 233_000L),
                LegacyPreviewClipCandidate("unreadable", 2_048L, 180_000L),
                LegacyPreviewClipCandidate("new", 4_096L, 200_000L)
            )
        )

        assertEquals(mapOf("same" to measured), plan.retained)
        assertEquals(listOf("redownloaded", "unreadable", "new"), plan.pending.map { it.reference })
    }

    @Test
    fun `only preview clips are published with their file size`() {
        val checks = mapOf(
            "preview" to LegacyPreviewClipCheck(1_024L, 30_000L, 233_000L),
            "complete" to LegacyPreviewClipCheck(8_000_000L, 232_900L, 233_000L),
            "unknown" to LegacyPreviewClipCheck(2_048L, null, 180_000L)
        )

        assertEquals(mapOf("preview" to 1_024L), legacyPreviewClipSizes(checks))
    }

    @Test
    fun `checks survive the persisted json round trip and ignore corrupt payloads`() {
        val checks = mapOf(
            "content://tree/a.mp3" to LegacyPreviewClipCheck(1_024L, 30_000L, 233_000L),
            "/data/b.flac" to LegacyPreviewClipCheck(2_048L, null, 180_000L)
        )

        assertEquals(checks, LegacyPreviewClipCheckCodec.decode(LegacyPreviewClipCheckCodec.encode(checks)))
        listOf(null, "", "not json", """{"short":[1]}""", """{"scalar":5}""").forEach { corrupt ->
            assertEquals(
                "payload=$corrupt",
                emptyMap<String, LegacyPreviewClipCheck>(),
                LegacyPreviewClipCheckCodec.decode(corrupt)
            )
        }
        assertEquals(
            mapOf("zero" to LegacyPreviewClipCheck(1L, null, 2L)),
            LegacyPreviewClipCheckCodec.decode("""{"zero":[1,0,2]}""")
        )
    }
}
