package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.player.resolver.youtube.ChunkRequestIOException
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.Timeout
import okio.Buffer
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class AudioDownloadManagerTest {

    @Test
    fun `wifi to mobile default transition requests protection only once`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertTrue(
            tracker.onDefaultNetworkObserved(
                networkKey = "mobile",
                networkType = TrafficNetworkType.MOBILE
            )
        )
        tracker.markWifiLossHandled()

        assertFalse(tracker.onDefaultNetworkLost(networkKey = "wifi"))
        assertFalse(
            tracker.onDefaultNetworkObserved(
                networkKey = "mobile",
                networkType = TrafficNetworkType.MOBILE
            )
        )
    }

    @Test
    fun `wifi loss before mobile replacement requests protection only once`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertTrue(tracker.onDefaultNetworkLost(networkKey = "wifi"))
        tracker.markWifiLossHandled()

        assertFalse(
            tracker.onDefaultNetworkObserved(
                networkKey = "mobile",
                networkType = TrafficNetworkType.MOBILE
            )
        )
        assertFalse(tracker.onDefaultNetworkLost(networkKey = "wifi"))
    }

    @Test
    fun `network policy pause aborts only the Wi-Fi bound song work`() {
        assertTrue(
            shouldAbortDownloadWork(
                allDownloadsCancelled = false,
                batchSessionCurrent = true,
                songCancelled = false,
                networkPolicyPaused = true,
                attemptAllowsWork = true
            )
        )
        assertFalse(
            shouldAbortDownloadWork(
                allDownloadsCancelled = false,
                batchSessionCurrent = true,
                songCancelled = false,
                networkPolicyPaused = false,
                attemptAllowsWork = true
            )
        )
    }

    @Test
    fun `network policy pause keeps unrelated batch state and gates working file mutations`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val pauseBody = methodBody(source, "pauseDownloadsForNetworkPolicy")
        val executionBody = methodBody(source, "executeDownloadSong")
        val hlsBody = methodBody(source, "singleThreadHlsDownload")

        assertFalse(pauseBody.contains("_isCancelled.value = true"))
        assertFalse(pauseBody.contains("invalidateBatchSession()"))
        assertFalse(pauseBody.contains("_batchProgressFlow.value = null"))
        assertTrue(pauseBody.contains("snapshotActiveCalls(songKey)"))
        assertTrue(source.contains("clearVisibleProgressForSong(songKey)"))
        assertTrue(executionBody.contains("stage = \"source_resolved\""))
        assertTrue(executionBody.contains("stage = \"prepare_working_file\""))
        assertTrue(hlsBody.contains("stage = \"hls_resume_reset\""))
        assertTrue(hlsBody.contains("stage = \"hls_open_working_file\""))
        assertFalse(hlsBody.contains("clearHlsResumeState(destFile)\n\n        NPLogger.d"))
        assertTrue(source.contains("onSongPausedForNetworkPolicy"))
        assertTrue(source.contains("!completionDispatched && !pausedForNetworkPolicy"))
    }

    @Test
    fun `stale wifi callback after cellular replacement cannot change policy`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertTrue(
            tracker.onDefaultNetworkObserved(
                networkKey = "cellular",
                networkType = TrafficNetworkType.MOBILE,
                activeNetworkKey = "cellular"
            )
        )
        tracker.markWifiLossHandled()

        assertFalse(
            tracker.onDefaultNetworkObserved(
                networkKey = "wifi",
                networkType = TrafficNetworkType.WIFI,
                activeNetworkKey = "cellular"
            )
        )
        assertEquals(1L, tracker.currentGeneration())
    }

    @Test
    fun `unknown active snapshot does not mutate network policy`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertFalse(
            tracker.onDefaultNetworkObserved(
                networkKey = "cellular",
                networkType = TrafficNetworkType.MOBILE,
                activeNetworkKey = null,
                activeNetworkKnown = false
            )
        )
        assertEquals(0L, tracker.currentGeneration())
    }

    @Test
    fun `duplicate confirmed callback does not advance network generation`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertFalse(
            tracker.onDefaultNetworkObserved(
                networkKey = "wifi",
                networkType = TrafficNetworkType.WIFI,
                activeNetworkKey = "wifi"
            )
        )
        assertEquals(0L, tracker.currentGeneration())
    }

    @Test
    fun `network observation emits Wi-Fi recovery only for a new confirmed snapshot`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "cellular", networkType = TrafficNetworkType.MOBILE)

        val first = tracker.observeDefaultNetwork(
            networkKey = "wifi",
            networkType = TrafficNetworkType.WIFI,
            activeNetworkKey = "wifi"
        )
        val duplicate = tracker.observeDefaultNetwork(
            networkKey = "wifi",
            networkType = TrafficNetworkType.WIFI,
            activeNetworkKey = "wifi"
        )

        assertTrue(first.changed)
        assertTrue(first.becameWifi)
        assertFalse(first.shouldPause)
        assertEquals(1L, first.generation)
        assertFalse(duplicate.changed)
        assertFalse(duplicate.becameWifi)
        assertEquals(first.generation, duplicate.generation)
    }

    @Test
    fun `unknown active snapshot after network loss does not pause Wi-Fi work`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertFalse(
            tracker.onDefaultNetworkLost(
                networkKey = "wifi",
                activeNetworkKey = null,
                activeNetworkKnown = false
            )
        )
        assertEquals(0L, tracker.currentGeneration())
    }

    @Test
    fun `confirmed absence of an active network pauses Wi-Fi work conservatively`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi", networkType = TrafficNetworkType.WIFI)

        assertTrue(
            tracker.onDefaultNetworkLost(
                networkKey = "wifi",
                activeNetworkKey = null,
                activeNetworkKnown = true
            )
        )
        assertEquals(1L, tracker.currentGeneration())
    }

    @Test
    fun `new Wi-Fi network after loss emits a recovery transition`() {
        val tracker = DownloadNetworkPolicyTracker()
        tracker.seed(networkKey = "wifi-old", networkType = TrafficNetworkType.WIFI)

        assertTrue(
            tracker.onDefaultNetworkLost(
                networkKey = "wifi-old",
                activeNetworkKey = null,
                activeNetworkKnown = true
            )
        )
        val recovery = tracker.observeDefaultNetwork(
            networkKey = "wifi-new",
            networkType = TrafficNetworkType.WIFI,
            activeNetworkKey = "wifi-new"
        )

        assertTrue(recovery.changed)
        assertTrue(recovery.becameWifi)
    }

    @Test
    fun `managed local references require a strictly verified replacement`() {
        assertEquals(
            "/Music/local.mp3",
            selectPermittedLocalPlaybackReference(
                rawLocalReference = "/Music/local.mp3",
                isManagedDownload = false,
                verifiedManagedReference = null
            )
        )
        assertEquals(
            "content://downloads/finalized.mp3",
            selectPermittedLocalPlaybackReference(
                rawLocalReference = "content://downloads/unfinalized.mp3",
                isManagedDownload = true,
                verifiedManagedReference = "content://downloads/finalized.mp3"
            )
        )
        assertNull(
            selectPermittedLocalPlaybackReference(
                rawLocalReference = "content://downloads/unfinalized.mp3",
                isManagedDownload = true,
                verifiedManagedReference = null
            )
        )
    }

    @Test
    fun `readable file and SAF references stay on local playback`() {
        assertEquals(
            LocalPlaybackReferenceResolution.Playable("file:///Music/local.flac"),
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = "file:///Music/local.flac",
                isManagedDownload = false,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.Present
            )
        )
        assertEquals(
            LocalPlaybackReferenceResolution.Playable(
                "content://provider/current-root/Song.flac"
            ),
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = "content://provider/old-root/Song.flac",
                isManagedDownload = true,
                verifiedManagedReference = "content://provider/current-root/Song.flac",
                rawEvidence = ManagedDownloadReferenceLookup.Result.Missing
            )
        )
    }

    @Test
    fun `readable managed SAF reference bypasses an incomplete snapshot`() {
        val rawReference = "content://provider/old-root/Song.flac"
        val incompleteSnapshot = ManagedDownloadStorage
            .emptyDownloadLibrarySnapshot()
            .copy(rootEntriesComplete = false)
        assertFalse(incompleteSnapshot.rootEntriesComplete)

        assertEquals(
            LocalPlaybackReferenceResolution.Playable(rawReference),
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = rawReference,
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.Present
            )
        )
        assertTrue(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = incompleteSnapshot.metadataByAudioName["Song.flac"]
            )
        )
        assertTrue(
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = rawReference,
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.Present,
                managedReferenceIsExplicitlyIncomplete = false
        ) is LocalPlaybackReferenceResolution.Playable
        )
    }

    @Test
    fun `present formal managed audio bypasses stale completion metadata`() {
        val present = ManagedDownloadReferenceLookup.Result.Present

        assertTrue(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                evidence = present
            )
        )
        assertTrue(
            shouldUseDirectPresentLocalPlayback(
                reference = "/storage/emulated/0/NeriPlayer/Song.flac",
                isManagedDownload = true,
                evidence = present
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/downloads/Song.flac.npdl_pending",
                isManagedDownload = true,
                evidence = present
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/download_staging/Song.flac",
                isManagedDownload = true,
                evidence = present
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/downloads/npdl_song.flac.download",
                isManagedDownload = true,
                evidence = present
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                evidence = ManagedDownloadReferenceLookup.Result.Missing
            )
        )
        assertFalse(
            shouldUseDirectPresentLocalPlayback(
                reference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                evidence = present,
                downloadCancelled = true
            )
        )
    }

    @Test
    fun `completed bridge allows pending reference without synchronous provider probe`() {
        assertTrue(
            shouldUseCompletedAudioReferenceDirectly(
                reference = "content://provider/downloads/Song.flac.npdl_pending"
            )
        )
        assertTrue(
            shouldUseCompletedAudioReferenceDirectly(
                reference = "/storage/emulated/0/NeriPlayer/Song.flac"
            )
        )
        assertFalse(
            shouldUseCompletedAudioReferenceDirectly(
                reference = "content://provider/download_staging/Song.flac"
            )
        )
        assertFalse(
            shouldUseCompletedAudioReferenceDirectly(
                reference = "content://provider/downloads/npdl_song.flac.download"
            )
        )
        assertFalse(
            shouldUseCompletedAudioReferenceDirectly(
                reference = "content://provider/downloads/Song.flac",
                downloadCancelled = true
            )
        )
    }

    @Test
    fun `managed raw reference remains blocked for an active replacement`() {
        val rawReference = "content://provider/downloads/Song.flac"
        assertTrue(
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = rawReference,
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.Present,
                managedReferenceIsExplicitlyIncomplete = true
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = false,
                    operationId = "op-1",
                    artifactState = "COMMITTING"
                )
            )
        )
        assertTrue(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = false,
                    artifactState = "CORE_COMMITTED"
                )
            )
        )
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = true,
                downloadActive = false,
                downloadCancelled = false,
                metadata = null
            )
        )
        assertTrue(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = true,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = false,
                    artifactState = "CORE_COMMITTED"
                )
            )
        )
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = true,
                    artifactState = "STAGING"
                )
            )
        )
    }

    @Test
    fun `core commit seeds durable playable metadata before enrichment`() {
        val seeded = coreCommittedSeedMetadataJson(
            """{"downloadFinalized":false,"artifactState":"COMMITTING","stableKey":"song"}"""
        )

        assertTrue(seeded != null)
        val json = JSONObject(requireNotNull(seeded))
        assertFalse(json.optBoolean("downloadFinalized", true))
        assertEquals("CORE_COMMITTED", json.optString("artifactState"))
        assertEquals("song", json.optString("stableKey"))
        assertNull(coreCommittedSeedMetadataJson("not-json"))
    }

    @Test
    fun `explicit unfinished metadata without artifact state remains blocked`() {
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = false
                )
            )
        )
    }

    @Test
    fun `present legacy audio remains playable while repair metadata is pending`() {
        val repairMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            downloadFinalized = false,
            artifactState = "REPAIR_REQUIRED"
        )

        assertTrue(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = repairMetadata,
                allowLegacyPublishedAudio = true
            )
        )
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = false,
                downloadActive = false,
                downloadCancelled = false,
                metadata = repairMetadata,
                allowLegacyPublishedAudio = false
            )
        )
        assertFalse(
            isReadableManagedAudioPlaybackAllowed(
                audioIsPending = true,
                downloadActive = false,
                downloadCancelled = false,
                metadata = repairMetadata,
                allowLegacyPublishedAudio = true
            )
        )
    }

    @Test
    fun `only typed missing permits a downloaded song to use remote fallback`() {
        assertEquals(
            LocalPlaybackReferenceResolution.Playable(
                "content://provider/downloads/Song.flac"
            ),
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.Present
            )
        )
        assertTrue(
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.Present,
                indexedReferenceIsExplicitlyIncomplete = true
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertEquals(
            LocalPlaybackReferenceResolution.Missing,
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.Missing
            )
        )
        assertTrue(
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.npdl_pending.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.Missing,
                missingIsTransient = true
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertTrue(
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.Missing,
                missingIsTransient = true
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertTrue(
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.PermissionLost(
                    SecurityException("grant revoked")
                )
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertTrue(
            selectIndexedLocalPlaybackResolution(
                verifiedReference = null,
                indexedReference = "content://provider/downloads/Song.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.ProviderFailure(
                    IllegalStateException("provider busy")
                )
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertTrue(
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.PermissionLost(
                    SecurityException("grant revoked")
                )
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
        assertTrue(
            selectPermittedLocalPlaybackResolution(
                rawLocalReference = "content://provider/downloads/Song.flac",
                isManagedDownload = true,
                verifiedManagedReference = null,
                rawEvidence = ManagedDownloadReferenceLookup.Result.ProviderFailure(
                    IllegalStateException("provider busy")
                )
            ) is LocalPlaybackReferenceResolution.TemporarilyUnavailable
        )
    }

    @Test
    fun `stale downloaded URI uses rebound current root reference`() {
        assertEquals(
            LocalPlaybackReferenceResolution.Playable(
                "content://provider/current-root/Rebound.flac"
            ),
            selectIndexedLocalPlaybackResolution(
                verifiedReference = "content://provider/current-root/Rebound.flac",
                indexedReference = "content://provider/old-root/Rebound.flac",
                indexedEvidence = ManagedDownloadReferenceLookup.Result.Missing
            )
        )
    }

    @Test
    fun `stale SAF document URI rebinds by file name only after finalization`() {
        val reboundAudio = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Rebound.flac",
            reference = "content://provider/current-root/Artist%20-%20Rebound.flac",
            mediaUri = "content://provider/current-root/Artist%20-%20Rebound.flac",
            localFilePath = null,
            sizeBytes = 1024L,
            lastModifiedMs = 2L
        )
        val finalizedSnapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(reboundAudio),
            metadataByAudioName = mapOf(
                reboundAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = true,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
                )
            )
        )
        val staleReference =
            "content://provider/tree/old/document/old%2FArtist%20-%20Rebound.flac"

        assertEquals(
            reboundAudio,
            findReboundFinalizedManagedAudio(finalizedSnapshot, staleReference)
        )
        assertNull(
            findReboundFinalizedManagedAudio(
                finalizedSnapshot.copy(
                    metadataByAudioName = mapOf(
                        reboundAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                            downloadFinalized = false
                        )
                    )
                ),
                staleReference
            )
        )
    }

    @Test
    fun `only strictly finalized managed audio is exposed for local playback`() {
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3",
            reference = "content://downloads/Artist%20-%20Song.mp3",
            mediaUri = "content://downloads/Artist%20-%20Song.mp3",
            localFilePath = null,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
        fun snapshot(
            finalized: Boolean,
            embeddingState: DownloadedAudioEmbeddingState?
        ) = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(audio),
            metadataByAudioName = mapOf(
                audio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = finalized,
                    metadataEmbeddingState = embeddingState
                )
            )
        )

        assertTrue(
            canExposeManagedDownloadForPlayback(
                snapshot(true, DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED),
                audio
            )
        )
        assertTrue(
            canExposeManagedDownloadForPlayback(
                snapshot(true, DownloadedAudioEmbeddingState.USER_DISABLED),
                audio
            )
        )
        assertFalse(canExposeManagedDownloadForPlayback(snapshot(true, null), audio))
        assertFalse(
            canExposeManagedDownloadForPlayback(
                snapshot(true, DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER),
                audio
            )
        )
        assertFalse(canExposeManagedDownloadForPlayback(snapshot(false, null), audio))
    }

    @Test
    fun `cover response reader rejects an oversized declared length`() {
        assertThrows(IOException::class.java) {
            AudioDownloadManager.readCoverResponseBytes(
                input = ByteArrayInputStream(byteArrayOf(1)),
                declaredLength = AudioDownloadManager.MAX_COVER_RESPONSE_BYTES + 1L
            )
        }
    }

    @Test
    fun `cover response reader rejects an oversized chunked body`() {
        assertThrows(IOException::class.java) {
            AudioDownloadManager.readCoverResponseBytes(
                input = ByteArrayInputStream(
                    ByteArray(AudioDownloadManager.MAX_COVER_RESPONSE_BYTES.toInt() + 1)
                ),
                declaredLength = -1L
            )
        }
    }

    @Test
    fun `download song keeps transfer and core commit in named suspend stages`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val downloadSongBody = methodBody(source, "downloadSong")
        val executionBody = methodBody(source, "executeDownloadSong")

        assertTrue(downloadSongBody.contains("downloadSongOnIo("))
        assertTrue(executionBody.contains("downloadPayloadForTransport("))
        assertTrue(executionBody.contains("finalizeDownloadedAudio("))
        assertFalse(executionBody.contains("ManagedDownloadStorage.saveAudioFromTemp("))
        assertTrue(source.contains("private suspend fun downloadPayloadForTransport("))
        assertTrue(source.contains("private suspend fun finalizeDownloadedAudio("))
    }

    @Test
    fun `completed bridge is retained until a real transport starts`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val executionBody = methodBody(source, "executeDownloadSong")
        val cachedLookupIndex = executionBody.indexOf(
            "hasFastCachedManagedDownloadForStart(context, song)"
        )
        val bridgeClearIndex = executionBody.indexOf(
            "clearCompletedAudioReference(songKey)"
        )
        val transportIndex = executionBody.indexOf("downloadPayloadForTransport(")

        assertTrue(cachedLookupIndex >= 0)
        assertTrue(bridgeClearIndex > cachedLookupIndex)
        assertTrue(transportIndex > bridgeClearIndex)
    }

    @Test
    fun `recent completed bridge is checked before SAF inspection`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val playbackBody = methodBody(source, "resolvePermittedLocalPlayback")
        val bridgeIndex = playbackBody.indexOf(
            "resolveRecentlyCommittedAudioReference("
        )
        val providerIndex = playbackBody.indexOf(
            "val rawEvidence = ManagedDownloadReferenceLookup.inspect"
        )

        assertTrue(bridgeIndex >= 0)
        assertTrue(providerIndex > bridgeIndex)
    }

    @Test
    fun `cancelYouTubeCalls cancels only trusted YouTube hosts`() {
        val youtubeCall = FakeCall("https://rr1---sn.example.googlevideo.com/audio")
        val innertubeCall = FakeCall("https://youtubei.googleapis.com/youtubei/v1/player")
        val unrelatedCall = FakeCall("https://example.com/audio")

        val canceled = AudioDownloadManager.cancelYouTubeCalls(
            listOf(youtubeCall, innertubeCall, unrelatedCall)
        )

        assertEquals(2, canceled)
        assertTrue(youtubeCall.isCanceled())
        assertTrue(innertubeCall.isCanceled())
        assertFalse(unrelatedCall.isCanceled())
    }

    @Test
    fun `batch download parallelism keeps default six and caps at eight workers`() {
        assertEquals(6, AudioDownloadManager.DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        assertEquals(8, AudioDownloadManager.MAX_CONCURRENT_DOWNLOADS_LIMIT)
        assertEquals(1, AudioDownloadManager.clampBatchDownloadParallelism(0))
        assertEquals(4, AudioDownloadManager.clampBatchDownloadParallelism(4))
        assertEquals(8, AudioDownloadManager.clampBatchDownloadParallelism(9))
        assertEquals(0, AudioDownloadManager.resolveBatchDownloadWorkerCount(0, 6))
        assertEquals(2, AudioDownloadManager.resolveBatchDownloadWorkerCount(2, 6))
        assertEquals(8, AudioDownloadManager.resolveBatchDownloadWorkerCount(20, 9))
    }

    @Test
    fun `shouldFetchRemoteLyricForDownload only fetches when local override is absent`() {
        assertEquals(true, AudioDownloadManager.shouldFetchRemoteLyricForDownload(null))
        assertEquals(false, AudioDownloadManager.shouldFetchRemoteLyricForDownload(""))
        assertEquals(false, AudioDownloadManager.shouldFetchRemoteLyricForDownload("   "))
        assertEquals(
            false,
            AudioDownloadManager.shouldFetchRemoteLyricForDownload("[00:00.00]local lyric")
        )
    }

    @Test
    fun `romanized lyric download only piggybacks on an existing lyric request`() {
        assertFalse(
            AudioDownloadManager.shouldFetchRomanizedLyricForDownload(
                shouldFetchPrimaryLyric = false,
                shouldFetchTranslatedLyric = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldFetchRomanizedLyricForDownload(
                shouldFetchPrimaryLyric = true,
                shouldFetchTranslatedLyric = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldFetchRomanizedLyricForDownload(
                shouldFetchPrimaryLyric = false,
                shouldFetchTranslatedLyric = true
            )
        )
    }

    @Test
    fun `sidecar merge preserves expected lyric artifacts across retries`() {
        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(
            existing = AudioDownloadManager.DownloadedSidecarReferences(
                expectedLyric = true
            ),
            incoming = AudioDownloadManager.DownloadedSidecarReferences(
                expectedTranslatedLyric = true,
                expectedRomanizedLyric = true
            )
        )

        assertTrue(merged.expectedLyric)
        assertTrue(merged.expectedTranslatedLyric)
        assertTrue(merged.expectedRomanizedLyric)
        assertFalse(merged.isEmpty)
    }

    @Test
    fun `resolveLocalLyricForDownload keeps explicit lyrics and preserves cleared state separately`() {
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload(null))
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload(""))
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload("   "))
        assertEquals(
            "[00:00.00]translated",
            AudioDownloadManager.resolveLocalLyricForDownload("[00:00.00]translated")
        )
    }

    @Test
    fun `transient download retry delay grows and stays capped`() {
        assertEquals(1_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(1))
        assertEquals(2_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(2))
        assertEquals(4_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(3))
        assertEquals(5_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(4))
        assertEquals(5_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(9))
    }

    @Test
    fun `transient download failure detection only retries unstable network failures`() {
        assertTrue(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                UnknownHostException("Unable to resolve host")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                SocketException("Software caused connection abort")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                IllegalStateException("HTTP 503")
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                IllegalStateException("HTTP 403")
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                IOException("磁盘写入失败")
            )
        )
    }

    @Test
    fun `youtube download failures refresh source for signed url status codes`() {
        assertTrue(
            AudioDownloadManager.shouldRefreshYouTubeDownloadSourceOnFailure(
                IllegalStateException("HTTP 403")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRefreshYouTubeDownloadSourceOnFailure(
                IllegalStateException("HTTP 416")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRetryDownloadFailureForSource(
                IllegalStateException("HTTP 403"),
                isYouTubeMusic = true
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRetryDownloadFailureForSource(
                IllegalStateException("HTTP 403"),
                isYouTubeMusic = false
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRefreshYouTubeDownloadSourceOnFailure(
                IOException("磁盘写入失败")
            )
        )
    }

    @Test
    fun `forbidden download failure is detected for 403 only`() {
        // 403(含 ChunkRequestIOException)才触发"改走 HLS", 其余可刷新码不触发
        assertTrue(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(IllegalStateException("HTTP 403")))
        assertTrue(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(ChunkRequestIOException(403, "forbidden")))
        assertFalse(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(ChunkRequestIOException(429, "rate")))
        assertFalse(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(IllegalStateException("HTTP 416")))
        assertFalse(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(IOException("磁盘写入失败")))
    }

    @Test
    fun `youtube download resolve plan starts with short shared probe then isolates refresh`() {
        val attempts = AudioDownloadManager.resolveYouTubeDownloadResolveAttempts(forceRefresh = false)

        assertEquals(4, attempts.size)
        assertEquals("shared_direct", attempts[0].logLabel)
        assertEquals(false, attempts[0].forceRefresh)
        assertEquals(true, attempts[0].requireDirect)
        assertEquals(true, attempts[0].shareInFlight)
        assertEquals("fresh_direct", attempts[1].logLabel)
        assertEquals(true, attempts[1].forceRefresh)
        assertEquals(true, attempts[1].requireDirect)
        assertEquals(false, attempts[1].shareInFlight)
        assertEquals("shared_playable", attempts[2].logLabel)
        assertEquals(false, attempts[2].forceRefresh)
        assertEquals(false, attempts[2].requireDirect)
        assertEquals(true, attempts[2].shareInFlight)
        assertEquals("fresh_playable", attempts[3].logLabel)
        assertEquals(true, attempts[3].forceRefresh)
        assertEquals(false, attempts[3].requireDirect)
        assertEquals(false, attempts[3].shareInFlight)
        assertTrue(attempts[0].timeoutMs < attempts[1].timeoutMs)
        assertTrue(attempts[2].timeoutMs < attempts[3].timeoutMs)
    }

    @Test
    fun `youtube download resolve plan skips shared probes after forced refresh`() {
        val attempts = AudioDownloadManager.resolveYouTubeDownloadResolveAttempts(forceRefresh = true)

        assertEquals(2, attempts.size)
        assertEquals(listOf("fresh_direct", "fresh_playable"), attempts.map { it.logLabel })
        assertTrue(attempts.all { it.forceRefresh })
        assertTrue(attempts.none { it.shareInFlight })
    }

    @Test
    fun `cover download candidates keep stable fallback order and de duplicate urls`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "https://example.com/cover.jpg",
            customCoverUrl = "https://example.com/custom.jpg",
            originalCoverUrl = "https://example.com/original.jpg",
            mediaUri = "https://example.com/audio.m4a"
        )

        assertEquals(
            listOf(
                "https://example.com/custom.jpg",
                "https://example.com/cover.jpg",
                "https://example.com/original.jpg"
            ),
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song)
        )
    }

    @Test
    fun `cover download candidates exclude device local references`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "file:/data/user/0/moe.ouom.neriplayer/files/local_audio_covers/cover.jpg",
            customCoverUrl = "content://media/external/images/media/1",
            originalCoverUrl = "https://example.com/original.jpg"
        )

        assertEquals(
            listOf("https://example.com/original.jpg"),
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song)
        )
    }

    @Test
    fun `cover download candidates trim before de duplicating urls`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "https://example.com/cover.jpg",
            customCoverUrl = " https://example.com/cover.jpg ",
            originalCoverUrl = "https://example.com/original.jpg"
        )

        assertEquals(
            listOf(
                "https://example.com/cover.jpg",
                "https://example.com/original.jpg"
            ),
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song)
        )
    }

    @Test
    fun `cover sidecar file names include stable song identity`() {
        val firstCoverName = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song",
            songKey = "1|netease|"
        )
        val sameNameSecondCoverName = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song",
            songKey = "2|netease|"
        )
        val numberedDuplicateCoverName = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song (1)",
            songKey = "2|netease|"
        )

        assertNotEquals(firstCoverName, sameNameSecondCoverName)
        assertNotEquals(firstCoverName, numberedDuplicateCoverName)
    }

    @Test
    fun `cover sidecar identity uses a collision resistant digest`() {
        val first = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song",
            songKey = "FB"
        )
        val second = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song",
            songKey = "Ea"
        )

        assertNotEquals(first, second)
        assertTrue(Regex("-[0-9a-f]{8}\\.jpg$").containsMatchIn(first))
    }

    @Test
    fun `transfer size completeness rejects short payloads while allowing bounded provider drift`() {
        assertTrue(AudioDownloadManager.isTransferSizeComplete(null, 128L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(0L, 128L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(256L, 256L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(256L, 257L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 999_000L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 1_001_000L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(null, 0L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 258L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 998_999L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 1_001_001L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 512L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 128L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(3_758_751L, 5_129_657L))
    }

    @Test
    fun `commit size validation drops transfer expectation after metadata changes file`() {
        assertNull(
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = 5_129_657L,
                bytesBeforeMetadata = 5_129_657L,
                bytesAtCommit = 4_551_323L
            )
        )
        assertEquals(
            3_758_751L,
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = 3_758_751L,
                bytesBeforeMetadata = 3_758_751L,
                bytesAtCommit = 3_758_751L
            )
        )
        assertNull(
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = null,
                bytesBeforeMetadata = 1_024L,
                bytesAtCommit = 1_024L
            )
        )
        assertNull(
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = 3_758_751L,
                bytesBeforeMetadata = 5_129_657L,
                bytesAtCommit = 4_551_323L
            )
        )
    }

    @Test
    fun `resume range header starts from completed bytes`() {
        assertEquals(null, AudioDownloadManager.buildResumeRangeHeader(0L))
        assertEquals("bytes=1024-", AudioDownloadManager.buildResumeRangeHeader(1_024L))
    }

    @Test
    fun `resume request includes if range validator when fingerprint is available`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = request.url.toString(),
            etag = "\"abc123\"",
            lastModified = "Wed, 15 Jul 2026 12:00:00 GMT",
            expectedContentLength = 4_096L
        )

        val resumedRequest = AudioDownloadManager.buildResumeRequest(
            request = request,
            completedBytes = 1_024L,
            fingerprint = fingerprint
        )

        assertEquals("bytes=1024-", resumedRequest.header("Range"))
        assertEquals("\"abc123\"", resumedRequest.header("If-Range"))
    }

    @Test
    fun `resume request does not use last modified without strong etag`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = request.url.toString(),
            etag = null,
            lastModified = "Wed, 15 Jul 2026 12:00:00 GMT",
            expectedContentLength = 4_096L
        )

        val resumedRequest = AudioDownloadManager.buildResumeRequest(
            request = request,
            completedBytes = 1_024L,
            fingerprint = fingerprint
        )

        assertEquals("bytes=1024-", resumedRequest.header("Range"))
        assertNull(resumedRequest.header("If-Range"))
        assertEquals("identity", resumedRequest.header("Accept-Encoding"))
    }

    @Test
    fun `resume request rejects weak etag for if range`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val weakWithDate = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = request.url.toString(),
            etag = "W/\"weak\"",
            lastModified = "Wed, 15 Jul 2026 12:00:00 GMT"
        )
        val weakOnly = weakWithDate.copy(lastModified = null)

        assertNull(
            AudioDownloadManager.buildResumeRequest(request, 1_024L, weakWithDate)
                .header("If-Range")
        )
        assertNull(
            AudioDownloadManager.buildResumeRequest(request, 1_024L, weakOnly)
                .header("If-Range")
        )
    }

    @Test
    fun `resume request removes an unsafe inherited if range header`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .header("If-Range", "W/\"weak\"")
            .build()

        val resumedRequest = AudioDownloadManager.buildResumeRequest(
            request = request,
            completedBytes = 1_024L,
            fingerprint = null
        )

        assertNull(resumedRequest.header("If-Range"))
    }

    @Test
    fun `chunk resume request removes unsafe inherited if range header`() {
        val request = Request.Builder()
            .url("https://rr1---sn-abcd.googlevideo.com/videoplayback?source=youtube")
            .header("If-Range", "Wed, 15 Jul 2026 12:00:00 GMT")
            .build()

        val chunkRequest = AudioDownloadManager.buildChunkResumeRequest(
            request = request,
            start = 1_024L,
            length = 4_096L,
            fingerprint = null
        )

        assertEquals("bytes=1024-5119", chunkRequest.header("Range"))
        assertEquals("identity", chunkRequest.header("Accept-Encoding"))
        assertNull(chunkRequest.header("If-Range"))
    }

    @Test
    fun `chunk resume prefers the latest persisted fingerprint`() {
        val fallback = ManagedDownloadStorage.WorkingResumeFingerprint(
            etag = "\"old\""
        )
        val latest = ManagedDownloadStorage.WorkingResumeFingerprint(
            etag = "\"new\""
        )

        assertEquals(
            latest,
            AudioDownloadManager.resolveLatestResumeFingerprint(
                fallback = fallback,
                latest = latest
            )
        )
        assertEquals(
            fallback,
            AudioDownloadManager.resolveLatestResumeFingerprint(
                fallback = fallback,
                latest = null
            )
        )
    }

    @Test
    fun `resume fingerprint exposes only a strong etag as validator`() {
        assertEquals(
            "\"strong\"",
            ManagedDownloadStorage.WorkingResumeFingerprint(
                etag = "\"strong\"",
                lastModified = "Wed, 15 Jul 2026 12:00:00 GMT"
            ).validator
        )
        assertNull(
            ManagedDownloadStorage.WorkingResumeFingerprint(
                etag = "W/\"weak\"",
                lastModified = "Wed, 15 Jul 2026 12:00:00 GMT"
            ).validator
        )
        assertNull(
            ManagedDownloadStorage.WorkingResumeFingerprint(
                etag = null,
                lastModified = "Wed, 15 Jul 2026 12:00:00 GMT"
            ).validator
        )
    }

    @Test
    fun `signed url rotation does not discard a resumable file`() {
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = "https://example.com/audio.m4a?token=old",
            etag = "\"same-validator\"",
            lastModified = null,
            expectedContentLength = 4_096L
        )

        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?token=new",
                fingerprint = fingerprint
            )
        )
        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?token=new",
                fingerprint = fingerprint.copy(etag = null, lastModified = null)
            )
        )
        assertTrue(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/other.m4a?token=new",
                fingerprint = fingerprint.copy(etag = null, lastModified = null)
            )
        )
        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?token=old",
                fingerprint = fingerprint
            )
        )
        assertTrue(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?quality=low",
                fingerprint = fingerprint.copy(etag = null, lastModified = null)
            )
        )
        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a",
                fingerprint = null
            )
        )
    }

    @Test
    fun `response expected bytes keeps full size when resuming partial payload`() {
        val headers = mapOf("Content-Range" to listOf("bytes 1024-4095/4096"))

        assertEquals(
            4_096L,
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://example.com/audio.m4a",
                headers = headers,
                bodyLength = 3_072L,
                resumedBytes = 1_024L,
                isPartialResponse = true
            )
        )
    }

    @Test
    fun `content range validation requires exact start total and body length`() {
        val validHeaders = mapOf("Content-Range" to listOf("bytes 1024-4095/4096"))
        assertEquals(
            4_096L,
            AudioDownloadManager.validatePartialContentRange(
                headers = validHeaders,
                expectedStart = 1_024L,
                bodyLength = 3_072L
            ).total
        )
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = validHeaders,
                expectedStart = 1_025L,
                bodyLength = 3_072L
            )
        }
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = mapOf("Content-Range" to listOf("bytes 1024-4095/*")),
                expectedStart = 1_024L,
                bodyLength = 3_072L
            )
        }
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = validHeaders,
                expectedStart = 1_024L,
                bodyLength = 3_071L
            )
        }
        assertEquals(
            4_096L,
            AudioDownloadManager.validatePartialContentRange(
                headers = mapOf("Content-Range" to listOf("bytes 0-4095/4096")),
                expectedStart = 0L,
                bodyLength = 4_096L
            ).total
        )
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = mapOf("Content-Range" to listOf("bytes 1-4095/4096")),
                expectedStart = 0L,
                bodyLength = 4_095L
            )
        }
    }

    @Test
    fun `resume response requires the same strong etag and total`() {
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            etag = "\"stable\"",
            expectedContentLength = 4_096L
        )
        val headers = mapOf("ETag" to listOf("\"stable\""))

        assertTrue(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                headers,
                4_096L
            )
        )
        assertFalse(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                mapOf("ETag" to listOf("\"rotated\"")),
                4_096L
            )
        )
        assertFalse(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                headers,
                4_097L
            )
        )
        assertFalse(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                mapOf("ETag" to listOf("W/\"stable\"")),
                4_096L
            )
        )
    }

    @Test
    fun `range not satisfiable is accepted only at exact total`() {
        val headers = mapOf("Content-Range" to listOf("bytes */4096"))
        assertTrue(AudioDownloadManager.isExactRangeEnd(headers, 4_096L))
        assertFalse(AudioDownloadManager.isExactRangeEnd(headers, 4_095L))
        assertTrue(
            AudioDownloadManager.isExactRangeEnd(
                mapOf("Content-Range" to listOf("bytes */0")),
                0L
            )
        )
        assertFalse(
            AudioDownloadManager.isExactRangeEnd(
                mapOf("Content-Range" to listOf("bytes */4096")),
                4_097L
            )
        )
    }

    @Test
    fun `response content length wins over stale non youtube query length`() {
        val headers = mapOf("Content-Length" to listOf("5129657"))

        assertEquals(
            5_129_657L,
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://m801.music.126.net/audio.mp3?clen=3758751",
                headers = headers,
                bodyLength = 5_129_657L,
                resumedBytes = 0L,
                isPartialResponse = false
            )
        )
    }

    @Test
    fun `non youtube query length is ignored when response length is unavailable`() {
        assertNull(
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://m801.music.126.net/audio.mp3?clen=3758751",
                headers = emptyMap(),
                bodyLength = -1L,
                resumedBytes = 0L,
                isPartialResponse = false
            )
        )
    }

    @Test
    fun `google video query length remains a fallback when response length is unavailable`() {
        assertEquals(
            3_758_751L,
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://rr1---sn-abcd.googlevideo.com/videoplayback?clen=3758751&source=youtube",
                headers = emptyMap(),
                bodyLength = -1L,
                resumedBytes = 0L,
                isPartialResponse = false
            )
        )
    }

    @Test
    fun `download transport kind falls back to chunked range only for googlevideo without explicit range`() {
        val chunkedRequest = Request.Builder()
            .url("https://rr1---sn-abcd.googlevideo.com/videoplayback?source=youtube")
            .build()
        val directRequest = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val explicitRangeRequest = chunkedRequest.newBuilder()
            .header("Range", "bytes=0-4095")
            .build()

        assertEquals(
            AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                chunkedRequest
            )
        )
        assertEquals(
            AudioDownloadManager.DownloadTransportKind.DIRECT,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                directRequest
            )
        )
        assertEquals(
            AudioDownloadManager.DownloadTransportKind.DIRECT,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                explicitRangeRequest
            )
        )
    }

    @Test
    fun `download transport chunks seekable web remix direct url instead of full range`() {
        // 回归: 已解析(n+sig+clen)的 WEB_REMIX 直链此前被判为 DIRECT(整档下载)导致 403
        // 现应统一走 CHUNKED_RANGE, 避免整档 GET 触发 googlevideo 全量下载风控
        val seekableWebRemixRequest = Request.Builder()
            .url(
                "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback" +
                    "?source=youtube&id=audio-demo&n=resolved-n&sig=resolved-signature&mime=audio%2Fmp4&clen=3611036"
            )
            .build()

        assertEquals(
            AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                seekableWebRemixRequest
            )
        )
    }

    @Test
    fun `partial download preservation requires bytes and hls checkpoint when needed`() {
        assertTrue(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.DIRECT,
                existingBytes = 4_096L,
                hasHlsResumeState = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE,
                existingBytes = 4_096L,
                hasHlsResumeState = false
            )
        )
        assertFalse(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.HLS,
                existingBytes = 4_096L,
                hasHlsResumeState = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.HLS,
                existingBytes = 4_096L,
                hasHlsResumeState = true
            )
        )
    }

    @Test
    fun `hls resume state serialization round trips`() {
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = "a".repeat(64),
            nextSegmentIndex = 12,
            downloadedBytes = 34_567L,
            durablePrefixSha256 = "b".repeat(64),
            operationId = "operation-1",
            mediaSequence = 42L
        )

        val restored = AudioDownloadManager.deserializeHlsResumeState(
            AudioDownloadManager.serializeHlsResumeState(state)
        )

        assertEquals(state, restored)
        assertTrue(
            AudioDownloadManager.serializeHlsResumeState(state)
                .contains("playlistDigestSha256")
        )
        assertTrue(
            AudioDownloadManager.serializeHlsResumeState(state)
                .contains("operationId")
        )
        assertEquals(null, AudioDownloadManager.deserializeHlsResumeState("{"))
        assertEquals(
            null,
            AudioDownloadManager.deserializeHlsResumeState(
                """{"playlistFingerprint":1,"nextSegmentIndex":2,"downloadedBytes":3}"""
            )
        )
        assertEquals(
            null,
            AudioDownloadManager.deserializeHlsResumeState(
                """{"playlistDigestSha256":"${"a".repeat(64)}","nextSegmentIndex":-1,"downloadedBytes":3}"""
            )
        )
    }

    @Test
    fun `hls checkpoint requires a durable prefix digest`() {
        val digest = "a".repeat(64)
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = digest,
            nextSegmentIndex = 1,
            downloadedBytes = 3L
        )

        assertTrue(
            "checkpoint must bind the durable output prefix",
            AudioDownloadManager.serializeHlsResumeState(state)
                .contains("durablePrefixSha256")
        )
        assertEquals(
            null,
            AudioDownloadManager.deserializeHlsResumeState(
                """{"format":"hls-resume-v2","playlistDigestSha256":"$digest","nextSegmentIndex":1,"downloadedBytes":3}"""
            )
        )
    }

    @Test
    fun `hls resume rejects a same length file with a different durable prefix`() {
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = "a".repeat(64),
            nextSegmentIndex = 2,
            downloadedBytes = 8L,
            durablePrefixSha256 = "b".repeat(64)
        )

        assertFalse(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state,
                actualFileLength = 8L,
                actualPrefixSha256 = "c".repeat(64),
                segmentCount = 3
            )
        )
        assertTrue(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state,
                actualFileLength = 8L,
                actualPrefixSha256 = "b".repeat(64),
                segmentCount = 3
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state,
                actualFileLength = 7L,
                actualPrefixSha256 = "b".repeat(64),
                segmentCount = 3
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state.copy(playlistFingerprint = "legacy-int"),
                actualFileLength = 8L,
                actualPrefixSha256 = "b".repeat(64),
                segmentCount = 3
            )
        )
    }

    @Test
    fun `hls playlist fingerprint is stable sha256 structured summary`() {
        val urls = listOf(
            "https://example.com/seg-1.ts",
            "https://example.com/seg-2.ts"
        )
        val fingerprint = AudioDownloadManager.buildHlsPlaylistFingerprint(urls)
        assertEquals(64, fingerprint.length)
        assertEquals(fingerprint, AudioDownloadManager.buildHlsPlaylistFingerprint(urls))
        assertNotEquals(
            fingerprint,
            AudioDownloadManager.buildHlsPlaylistFingerprint(urls + "https://example.com/seg-3.ts")
        )
        assertEquals(
            fingerprint,
            AudioDownloadManager.buildHlsPlaylistFingerprint(
                listOf(
                    "https://example.com/seg-1.ts?sig=rotated&expire=2",
                    "https://example.com/seg-2.ts?token=new"
                )
            )
        )
    }

    @Test
    fun `hls playlist fingerprint keeps byte range identity`() {
        val first = AudioDownloadManager.buildHlsPlaylistFingerprint(
            listOf("https://example.com/seg.ts?range=0-99")
        )
        val second = AudioDownloadManager.buildHlsPlaylistFingerprint(
            listOf("https://example.com/seg.ts?range=100-199")
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `hls resume accepts only durable prefix and can discard an extra tail`() {
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = "a".repeat(64),
            nextSegmentIndex = 2,
            downloadedBytes = 8L,
            durablePrefixSha256 = "b".repeat(64)
        )

        assertTrue(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state,
                actualFileLength = 12L,
                actualPrefixSha256 = "b".repeat(64),
                segmentCount = 3
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state.copy(nextSegmentIndex = 0),
                actualFileLength = 12L,
                actualPrefixSha256 = "b".repeat(64),
                segmentCount = 3
            )
        )
    }

    @Test
    fun `hls checkpoint cannot be reused by a different operation`() {
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = "a".repeat(64),
            nextSegmentIndex = 1,
            downloadedBytes = 8L,
            durablePrefixSha256 = "b".repeat(64),
            operationId = "operation-a"
        )

        assertTrue(
            AudioDownloadManager.isHlsResumeStateOwnedByOperation(
                state = state,
                operationId = "operation-a"
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateOwnedByOperation(
                state = state,
                operationId = "operation-b"
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateOwnedByOperation(
                state = state.copy(operationId = ""),
                operationId = "operation-a"
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateOwnedByOperation(
                state = state,
                operationId = ""
            )
        )
    }

    @Test
    fun `hls segment copy streams and strips only leading id3`() {
        val id3TagPayload = byteArrayOf(1, 2, 3, 4)
        val id3Header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            4, 0, 0, 0, 0, 0, id3TagPayload.size.toByte()
        )
        val payload = ByteArray(128 * 1024) { index -> (index and 0x7f).toByte() }
        val source = Buffer()
            .write(id3Header)
            .write(id3TagPayload)
            .write(payload)
        val sink = Buffer()
        val traffic = TrafficByteAccumulator(Long.MAX_VALUE) {}

        val copied = AudioDownloadManager.copyHlsSegment(source, sink, traffic)

        assertEquals(payload.size.toLong(), copied)
        assertTrue(sink.readByteArray().contentEquals(payload))
    }

    @Test
    fun `hls segment copy rejects a truncated response when length is known`() {
        val source = Buffer().write(byteArrayOf(1, 2, 3))
        val sink = Buffer()
        val traffic = TrafficByteAccumulator(Long.MAX_VALUE) {}

        assertThrows(IllegalStateException::class.java) {
            AudioDownloadManager.copyHlsSegment(
                source = source,
                sink = sink,
                trafficAccumulator = traffic,
                expectedRawBytes = 4L
            )
        }
    }

    @Test
    fun `retry wake signal version advances and wraps safely`() {
        assertEquals(2L, AudioDownloadManager.advanceRetryWakeSignalVersion(1L))
        assertEquals(0L, AudioDownloadManager.advanceRetryWakeSignalVersion(Long.MAX_VALUE))
    }

    private fun methodBody(source: String, methodName: String): String {
        val signatureStart = Regex(
            "(?:private|internal|public)?\\s*(?:suspend\\s+)?fun\\s+$methodName\\b"
        ).find(source)?.range?.first
            ?: error("method not found: $methodName")
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "method body not found: $methodName" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("unterminated method body: $methodName")
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }

    private class FakeCall(url: String) : Call {
        private val request = Request.Builder().url(url).build()
        private val canceled = AtomicBoolean(false)

        override fun request(): Request = request

        override fun execute(): Response {
            throw UnsupportedOperationException("execution is not used")
        }

        override fun enqueue(responseCallback: Callback) {
            throw UnsupportedOperationException("enqueue is not used")
        }

        override fun cancel() {
            canceled.set(true)
        }

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = canceled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(request.url.toString())

        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()
    }
}
