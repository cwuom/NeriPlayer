package moe.ouom.neriplayer.core.player.download.ownership

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioDownloadReferenceRegistryTest {

    @Test
    fun `release keeps references retained for playback or owned by another audio`() {
        val registry = AudioDownloadReferenceRegistry(retentionMs = Long.MAX_VALUE, maxEntries = 8)
        registry.rememberCompletedAudioReference("song", AUDIO)

        registry.releaseCompletedAudioReference("song", retainForPlayback = true)
        registry.releaseCompletedAudioReference("song", expectedAudio = OTHER_AUDIO)
        registry.releaseCompletedAudioReference("missing")
        assertEquals(AUDIO, registry.peekCompletedAudioReference("song"))
        assertEquals(AUDIO, registry.peekCompletedAudioReferenceByRawReference(" ${AUDIO.reference} "))

        registry.releaseCompletedAudioReference("song", expectedAudio = AUDIO)
        assertNull(registry.peekCompletedAudioReference("song"))
        assertNull(registry.peekCompletedAudioReferenceByRawReference(AUDIO.reference))
    }

    @Test
    fun `release without an expected audio drops every alias`() {
        val registry = AudioDownloadReferenceRegistry(retentionMs = Long.MAX_VALUE, maxEntries = 8)
        registry.rememberCompletedAudioReference("song", AUDIO)

        registry.releaseCompletedAudioReference("song")

        assertNull(registry.consumeCompletedAudioReference("song"))
        assertNull(registry.peekCompletedAudioReferenceByRawReference(AUDIO.mediaUri))
    }

    @Test
    fun `expired references are dropped by song key lookups`() {
        val registry = AudioDownloadReferenceRegistry(retentionMs = 0L, maxEntries = 8)

        registry.rememberCompletedAudioReference("song", AUDIO)
        assertNull(registry.peekCompletedAudioReference("song"))
        assertNull(registry.peekCompletedAudioReferenceByRawReference(AUDIO.reference))

        registry.rememberCompletedAudioReference("song", AUDIO)
        registry.releaseCompletedAudioReference("song")
        assertNull(registry.consumeCompletedAudioReference("song"))
    }

    @Test
    fun `expired references stay unavailable through every remaining alias`() {
        val registry = AudioDownloadReferenceRegistry(retentionMs = 0L, maxEntries = 8)
        registry.rememberCompletedAudioReference("song", AUDIO)

        assertNull(registry.peekCompletedAudioReferenceByRawReference(AUDIO.reference))
        assertNull(registry.peekCompletedAudioReferenceByRawReference(AUDIO.reference))
        assertNull(registry.consumeCompletedAudioReference("song"))
    }

    private companion object {
        val AUDIO = ManagedDownloadStorage.StoredEntry(
            name = "Song.flac",
            reference = "content://tree/song",
            mediaUri = "content://media/song",
            localFilePath = null,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
        val OTHER_AUDIO = AUDIO.copy(reference = "content://tree/other", mediaUri = "content://media/other")
    }
}
