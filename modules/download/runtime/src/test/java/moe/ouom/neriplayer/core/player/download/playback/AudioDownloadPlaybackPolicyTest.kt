package moe.ouom.neriplayer.core.player.download.playback

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownloadPlaybackPolicyTest {
    @Test
    fun `download work aborts when any cancellation or ownership guard fails`() {
        fun abort(
            allDownloadsCancelled: Boolean = false,
            batchSessionCurrent: Boolean = true,
            songCancelled: Boolean = false,
            networkPolicyPaused: Boolean = false,
            attemptAllowsWork: Boolean = true,
            operationAllowsWork: Boolean = true
        ) = shouldAbortDownloadWork(
            allDownloadsCancelled = allDownloadsCancelled,
            batchSessionCurrent = batchSessionCurrent,
            songCancelled = songCancelled,
            networkPolicyPaused = networkPolicyPaused,
            attemptAllowsWork = attemptAllowsWork,
            operationAllowsWork = operationAllowsWork
        )

        assertFalse(abort())
        assertTrue(abort(allDownloadsCancelled = true))
        assertTrue(abort(batchSessionCurrent = false))
        assertTrue(abort(songCancelled = true))
        assertTrue(abort(networkPolicyPaused = true))
        assertTrue(abort(attemptAllowsWork = false))
        assertTrue(abort(operationAllowsWork = false))
    }

    @Test
    fun `direct present playback accepts unmanaged references only when they are not blank`() {
        val present = ManagedDownloadReferenceLookup.Result.Present

        assertTrue(shouldUseDirectPresentLocalPlayback(" /music/song.mp3 ", false, present))
        assertFalse(shouldUseDirectPresentLocalPlayback(null, false, present))
        assertFalse(shouldUseDirectPresentLocalPlayback("   ", false, present))
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                "/music/song.mp3",
                false,
                ManagedDownloadReferenceLookup.Result.Missing
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback("/music/song.mp3", false, present, downloadCancelled = true)
        )
    }

    @Test
    fun `direct present playback for managed downloads requires a formal audio name`() {
        val present = ManagedDownloadReferenceLookup.Result.Present

        assertTrue(shouldUseDirectPresentLocalPlayback("/music/Artist - Song.flac", true, present))
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                "/music/Artist - Song.flac.npdl_pending.123.pending",
                true,
                present
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                "/data/download_staging/npdl_abc_song.flac.download",
                true,
                present
            )
        )
        assertFalse(shouldUseDirectPresentLocalPlayback("/music/npdl_abc_song.flac.download", true, present))
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                "/music/Artist - Song.flac",
                true,
                ManagedDownloadReferenceLookup.Result.OutOfScope
            )
        )
    }

    @Test
    fun `rebinding requires a complete root and one unique finalized audio with the same name`() {
        val audio = audio("Artist - Song.flac", "content://provider/root/Artist%20-%20Song.flac")
        val finalized = DownloadedAudioMetadata(
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
        )
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(audio),
            metadataByAudioName = mapOf(audio.name to finalized)
        )
        val staleReference = "/old-root/Artist - Song.flac"

        assertEquals(audio, findReboundFinalizedManagedAudio(snapshot, staleReference))
        assertNull(
            findReboundFinalizedManagedAudio(
                snapshot.copy(metadataByAudioName = mapOf(audio.name to finalized.copy(downloadFinalized = false))),
                staleReference
            )
        )
        assertNull(findReboundFinalizedManagedAudio(snapshot.copy(rootEntriesComplete = false), staleReference))
        assertNull(findReboundFinalizedManagedAudio(snapshot, "   "))
        assertNull(findReboundFinalizedManagedAudio(snapshot, "/old-root/Other.flac"))
        assertNull(
            findReboundFinalizedManagedAudio(
                snapshot.copy(
                    audioEntries = listOf(
                        audio,
                        audio.copy(reference = "content://provider/root/duplicate", mediaUri = "duplicate")
                    )
                ),
                staleReference
            )
        )
    }

    @Test
    fun `playback exposure rejects missing snapshots incomplete roots and pending writes`() {
        val audio = audio("Artist - Song.flac", "content://provider/root/Artist%20-%20Song.flac")
        val pendingAudio = audio(
            "Artist - Song.flac.npdl_pending.123e4567-e89b-12d3-a456-426614174000.pending",
            "content://provider/root/pending"
        )
        val finalized = DownloadedAudioMetadata(
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED
        )
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(audio),
            metadataByAudioName = mapOf(audio.name to finalized)
        )

        assertTrue(canExposeManagedDownloadForPlayback(snapshot, audio))
        assertFalse(canExposeManagedDownloadForPlayback(null, audio))
        assertFalse(canExposeManagedDownloadForPlayback(snapshot, null))
        assertFalse(canExposeManagedDownloadForPlayback(snapshot.copy(rootEntriesComplete = false), audio))
        assertFalse(
            canExposeManagedDownloadForPlayback(
                snapshot.copy(pendingMetadataByAudioName = mapOf(audio.name to finalized)),
                pendingAudio
            )
        )
        assertFalse(
            canExposeManagedDownloadForPlayback(
                snapshot.copy(
                    metadataByAudioName = mapOf(audio.name to finalized.copy(audioPublicationPending = true))
                ),
                audio
            )
        )
    }

    private fun audio(name: String, reference: String): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = 1024L,
            lastModifiedMs = 1L
        )
    }
}
