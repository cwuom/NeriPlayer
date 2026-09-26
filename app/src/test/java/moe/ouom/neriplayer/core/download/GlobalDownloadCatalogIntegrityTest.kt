package moe.ouom.neriplayer.core.download

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.download.manager.runtime.readDownloadedCatalogReferenceSize
import moe.ouom.neriplayer.core.download.policy.matchesDownloadedCatalogFileSize
import moe.ouom.neriplayer.core.download.policy.shouldTrustDirectPresentDownloadedSongReference
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.player.download.isReadableManagedAudioPlaybackAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

class GlobalDownloadCatalogIntegrityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `an openable empty formal file is not a completed catalog download`() {
        val file = temporaryFolder.newFile("empty.flac")
        val context = Mockito.mock(Context::class.java)
        val evidence = ManagedDownloadReferenceLookup.inspect(context, file.absolutePath)
        assertEquals(ManagedDownloadReferenceLookup.Result.Present, evidence)

        assertFalse(shouldTrustDirectPresentDownloadedSongReference(
            reference = file.absolutePath,
            evidence = evidence,
            snapshot = null,
            cachedAudio = audio(file)
        ))
    }

    @Test
    fun `present without size evidence cannot mark an incomplete snapshot complete`() {
        val file = temporaryFolder.newFile("uncached.flac").apply { writeBytes(ByteArray(128)) }
        val context = Mockito.mock(Context::class.java)

        assertFalse(shouldTrustDirectPresentDownloadedSongReference(
            reference = file.absolutePath,
            evidence = ManagedDownloadReferenceLookup.inspect(context, file.absolutePath),
            snapshot = null,
            cachedAudio = null
        ))
    }

    @Test
    fun `a formal file awaiting audio publication cannot use legacy playback permission`() {
        assertFalse(isReadableManagedAudioPlaybackAllowed(
            audioIsPending = false,
            downloadActive = false,
            downloadCancelled = false,
            metadata = metadata(publicationPending = true),
            allowLegacyPublishedAudio = true
        ))
    }

    @Test
    fun `a formal file awaiting publication cannot use finalized playback permission`() {
        assertFalse(isReadableManagedAudioPlaybackAllowed(
            audioIsPending = false,
            downloadActive = false,
            downloadCancelled = false,
            metadata = metadata(publicationPending = true),
            allowLegacyPublishedAudio = false
        ))
    }

    @Test
    fun `a pending file with durable core proof remains playable while publication is pending`() {
        assertTrue(isReadableManagedAudioPlaybackAllowed(
            audioIsPending = true,
            downloadActive = false,
            downloadCancelled = false,
            metadata = metadata(publicationPending = true).copy(artifactState = "CORE_COMMITTED")
        ))
    }

    @Test
    fun `legacy formal audio without a publication marker remains playable`() {
        assertTrue(isReadableManagedAudioPlaybackAllowed(
            audioIsPending = false,
            downloadActive = false,
            downloadCancelled = false,
            metadata = metadata(publicationPending = false).copy(
                artifactState = null,
                downloadFinalized = false
            ),
            allowLegacyPublishedAudio = true
        ))
    }

    @Test
    fun `fresh file size rejects a truncated or enlarged previously recorded file`() {
        val file = temporaryFolder.newFile("changed.flac").apply { writeBytes(ByteArray(128)) }
        val context = Mockito.mock(Context::class.java)
        val recordedSize = file.length()
        assertTrue(matchesDownloadedCatalogFileSize(recordedSize, readDownloadedCatalogReferenceSize(context, file.absolutePath)))

        for (changedSize in listOf(0, 32, 256)) {
            file.writeBytes(ByteArray(changedSize))
            assertFalse(matchesDownloadedCatalogFileSize(recordedSize, readDownloadedCatalogReferenceSize(context, file.absolutePath)))
        }
    }

    @Test
    fun `file uri and private path observe the same fresh size`() {
        val file = temporaryFolder.newFile("percent %20 name.flac").apply { writeBytes(ByteArray(128)) }
        val context = Mockito.mock(Context::class.java)
        assertEquals(128L, readDownloadedCatalogReferenceSize(context, file.absolutePath))
        assertEquals(128L, readDownloadedCatalogReferenceSize(context, file.toURI().toString()))
    }

    @Test
    fun `unknown size evidence cannot become completion or missing evidence`() {
        for ((recorded, observed) in listOf(null to 128L, 0L to 128L, 128L to null, 128L to -1L)) {
            assertFalse(matchesDownloadedCatalogFileSize(recorded, observed))
        }
        val context = Mockito.mock(Context::class.java)
        val missingFile = File(temporaryFolder.root, "never-created.flac")
        assertEquals(null, readDownloadedCatalogReferenceSize(context, missingFile.absolutePath))
    }

    @Test
    fun `size validation does not claim to detect a same length content replacement`() {
        val file = temporaryFolder.newFile("same-length.flac").apply { writeBytes(ByteArray(128) { 1 }) }
        val context = Mockito.mock(Context::class.java)
        val recordedSize = file.length()
        file.writeBytes(ByteArray(128) { 2 })

        assertTrue(matchesDownloadedCatalogFileSize(recordedSize, readDownloadedCatalogReferenceSize(context, file.absolutePath)))
    }

    private fun metadata(publicationPending: Boolean) = ManagedDownloadStorage.DownloadedAudioMetadata(
        downloadFinalized = true,
        artifactState = "FINALIZED",
        audioPublicationPending = publicationPending
    )

    private fun audio(file: File) = ManagedDownloadStorage.StoredEntry(
        name = file.name,
        reference = file.absolutePath,
        mediaUri = file.toURI().toString(),
        localFilePath = file.absolutePath,
        sizeBytes = file.length(),
        lastModifiedMs = file.lastModified()
    )
}
