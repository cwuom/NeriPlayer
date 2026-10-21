package moe.ouom.neriplayer.core.player.download.playback

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.UriPermission
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.player.download.ownership.AudioDownloadReferenceRegistry
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.source.ResolvedLocalMediaSource
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.stableKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioDownloadExternalScannedPlaybackTest {
    @get:Rule
    val directory = TemporaryFolder()

    private val currentRoot = DocumentsContract.buildTreeDocumentUri(AUTHORITY, CURRENT_ROOT_ID)
    private val externalRoot = DocumentsContract.buildTreeDocumentUri(AUTHORITY, EXTERNAL_ROOT_ID)
    private val audioUri = DocumentsContract.buildDocumentUriUsingTree(externalRoot, AUDIO_ID)
    private val metadataUri = DocumentsContract.buildDocumentUriUsingTree(externalRoot, METADATA_ID)
    private val currentAudioUri = DocumentsContract.buildDocumentUriUsingTree(currentRoot, CURRENT_AUDIO_ID)
    private val alternateAudioUri = DocumentsContract.buildDocumentUriUsingTree(externalRoot, ALTERNATE_AUDIO_ID)
    private val pendingAudioUri = DocumentsContract.buildDocumentUriUsingTree(externalRoot, PENDING_AUDIO_ID)
    private val stagingAudioUri = DocumentsContract.buildDocumentUriUsingTree(externalRoot, STAGING_AUDIO_ID)
    private val audioQueries = AtomicInteger()
    private val currentRootQueries = AtomicInteger()
    private val directoryQueries = AtomicInteger()
    private val explicitReferences = mutableSetOf<String>()
    private val unknownExplicitReferences = mutableSetOf<String>()
    private var explicitReferencesKnown = true
    private val mediaAccessField = LocalMediaHostAccess::class.java.getDeclaredField("access")
        .apply { isAccessible = true }
    private var previousMediaAccess: Any? = null
    private var previousDirectoryUri: String? = null
    private var externalReadGranted = true
    private var externalAudioMissing = false
    private var externalAudioFailure: RuntimeException? = null
    private lateinit var context: Context
    private lateinit var audio: File
    private lateinit var metadata: File
    private val previousRefreshJob = GlobalDownloadManager.refreshJob
    private val completedReferences = AudioDownloadReferenceRegistry(30_000L, 16)
    private val coordinator = AudioDownloadPlaybackCoordinator(
        completedReferences,
        isSongDownloadActive = { false },
        directoryMutationWaitMs = 1L,
        explicitLocalFileReference = {
            if (!explicitReferencesKnown || it in unknownExplicitReferences) null else it in explicitReferences
        }
    )

    @Before
    fun setUp() = runBlocking {
        previousMediaAccess = mediaAccessField.get(null)
        previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val resolver = mock(ContentResolver::class.java)
        val files = directory.newFolder("files")
        val cache = directory.newFolder("cache")
        val externalMusic = directory.newFolder("Music")
        val noBackup = directory.newFolder("no-backup")
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = noBackup
            override fun getExternalFilesDir(type: String?): File = externalMusic
            override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int =
                if (isExternal(uri) && !externalReadGranted) PackageManager.PERMISSION_DENIED
                else PackageManager.PERMISSION_GRANTED
        }
        audio = directory.newFile(AUDIO_NAME).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        metadata = directory.newFile(METADATA_NAME).apply {
            writeText("""{"songId":859470,"name":"Scanned Song","artist":"Artist","album":"Album","durationMs":180000,"channelId":"netease","audioId":"859470","stableKey":"859470|netease|"}""")
        }
        doAnswer { invocation ->
            val uri = invocation.getArgument<Uri>(0)
            val projection = invocation.getArgument<Array<String>>(1)
            if (uri.pathSegments.lastOrNull() == "children") directoryQueries.incrementAndGet()
            if (uri == Uri.parse("content://media/external/audio/media/859470")) {
                return@doAnswer MatrixCursor(projection).apply {
                    addRow(projection.map { column ->
                        if (column == "_data") "/storage/emulated/0/NeriPlayer-Download2/$AUDIO_NAME" else null
                    })
                }
            }
            when (uri) {
                audioUri, alternateAudioUri, currentAudioUri, pendingAudioUri, stagingAudioUri -> audioQueries.incrementAndGet()
            }
            if (runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() == CURRENT_ROOT_ID) {
                currentRootQueries.incrementAndGet()
            }
            checkReadable(uri)
            val ids = if (uri == audioUri && externalAudioMissing) {
                emptyList()
            } else if (uri.pathSegments.lastOrNull() == "children") {
                if (DocumentsContract.getDocumentId(uri) == EXTERNAL_ROOT_ID) listOf(AUDIO_ID, METADATA_ID)
                else emptyList()
            } else {
                listOf(DocumentsContract.getDocumentId(uri))
            }
            MatrixCursor(projection).apply {
                ids.forEach { id -> addRow(projection.map { valueFor(id, it) }) }
            }
        }.`when`(resolver).query(any(), any(), any(), any(), any())
        doAnswer { invocation ->
            readableFile(invocation.getArgument(0)).inputStream()
        }.`when`(resolver).openInputStream(any(Uri::class.java))
        doAnswer { invocation ->
            ParcelFileDescriptor.open(readableFile(invocation.getArgument(0)), ParcelFileDescriptor.MODE_READ_ONLY)
        }.`when`(resolver).openFileDescriptor(any(), eq("r"))
        doAnswer { invocation ->
            val file = readableFile(invocation.getArgument(0))
            AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0L, file.length())
        }.`when`(resolver).openAssetFileDescriptor(any(), eq("r"))
        doAnswer {
            listOfNotNull(grant(currentRoot, write = true), grant(externalRoot).takeIf { externalReadGranted })
        }.`when`(resolver).persistedUriPermissions

        ManagedDownloadStorage.settings.updateDirectoryUri(currentRoot.toString())
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot()
        ManagedDownloadStorage.snapshotCacheStore.putSnapshot(
            context,
            ManagedDownloadStorage.currentSnapshotRootKey(context),
            snapshot
        )
        val downloads = mock(LocalMediaDownloadAccess::class.java)
        doReturn(currentRoot.toString()).`when`(downloads).configuredDirectoryUri()
        doReturn(snapshot).`when`(downloads).cachedDownloadLibrarySnapshot(context, true)
        doReturn(emptyList<String>()).`when`(downloads).candidateFileNameTemplates(null)
        LocalMediaHostAccess.bind(downloads, mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { false })
    }

    @After
    fun tearDown() = runBlocking {
        GlobalDownloadManager.refreshJob?.takeUnless { it === previousRefreshJob }?.cancelAndJoin()
        ManagedDownloadStorage.settings.updateDirectoryUri(previousDirectoryUri)
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        mediaAccessField.set(null, previousMediaAccess)
        LocalMediaSupport.clearLyricsLookupCache()
    }

    @Test
    fun `authorized external folder scan stays playable with remote metadata identity`() = runBlocking {
        val scanned = scannedSong()
        val previousAudioQueries = audioQueries.get()

        val result = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

        assertEquals(LocalPlaybackReferenceResolution.Playable(audioUri.toString()), result)
        assertTrue("playback checks the selected document", audioQueries.get() > previousAudioQueries)
        assertRemoteIdentity(scanned)
    }

    @Test
    fun `exact scanned sources in the current root keep the selected file before a song bridge`() = runBlocking {
        assertExactScannedSourceKeepsFile(externalRoot.toString())
    }

    @Test
    fun `exact scanned sources without a configured root keep the selected file before a song bridge`() = runBlocking {
        assertExactScannedSourceKeepsFile(null)
    }

    @Test
    fun `direct preferred source contexts keep the selected scanned file in compatible roots`() = runBlocking {
        val song = scannedSong()
        val reference = requireNotNull(song.mediaUri)
        val source = ResolvedLocalMediaSource.direct(song, reference)
        listOf(externalRoot.toString(), null).forEach { root ->
            configureSelectedRootWithBridge(song, root)

            assertEquals(LocalPlaybackReferenceResolution.Playable(reference),
                coordinator.resolvePermittedLocalPlayback(context, song, reference, source))
        }
    }

    @Test
    fun `missing exact scanned sources in the current root cannot consume a bridge or rebind`() = runBlocking {
        assertExactScannedSourceStaysMissing(externalRoot.toString())
    }

    @Test
    fun `missing exact scanned sources without a configured root cannot consume a bridge or rebind`() = runBlocking {
        assertExactScannedSourceStaysMissing(null)
    }

    @Test
    fun `unknown exact scanned provenance in the current root blocks a bridge before probing`() = runBlocking {
        assertUnknownExactScannedSourceBlocksBridge(externalRoot.toString())
    }

    @Test
    fun `unknown exact scanned provenance without a configured root blocks a bridge before probing`() = runBlocking {
        assertUnknownExactScannedSourceBlocksBridge(null)
    }

    @Test
    fun `exact references with false scanned evidence retain managed bridges in compatible roots`() = runBlocking {
        val song = scannedSong(explicit = false)
        listOf(externalRoot.toString(), null).forEach { root ->
            configureSelectedRootWithBridge(song, root)

            assertEquals(LocalPlaybackReferenceResolution.Playable(alternateAudioUri.toString()),
                coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri))
        }
    }

    @Test
    fun `explicit current root pending and staging sources cannot consume a bridge before probing`() = runBlocking {
        val song = scannedSong()
        configureSelectedRootWithBridge(song)
        listOf(pendingAudioUri, stagingAudioUri).forEach { uri ->
            val reference = uri.toString()
            explicitReferences.add(reference)
            val previousQueries = audioQueries.get()

            assertUnknown(coordinator.resolvePermittedLocalPlayback(context,
                song.copy(mediaUri = reference, localFilePath = null), reference))
            assertEquals(previousQueries, audioQueries.get())
        }
    }

    @Test
    fun `cancelled exact current root scans cannot consume a bridge before probing`() = runBlocking {
        val song = scannedSong()
        configureSelectedRootWithBridge(song)
        val key = song.stableKey()
        val added = GlobalDownloadManager.cancelledSongKeys.add(key)
        val previousQueries = audioQueries.get()
        try {
            assertUnknown(coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri))
            assertEquals(previousQueries, audioQueries.get())
        } finally {
            if (added) GlobalDownloadManager.cancelledSongKeys.remove(key)
        }
    }

    @Test
    fun `exact current root scans wait behind the mutation fence before probing or using a bridge`() = runBlocking {
        val song = scannedSong()
        configureSelectedRootWithBridge(song)
        val previousQueries = audioQueries.get()
        val lease = ManagedDownloadDirectoryMutationFence.closeAndDrain()
        try {
            assertUnknown(coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri))
            assertEquals(previousQueries, audioQueries.get())
        } finally {
            lease.close()
        }
    }

    @Test
    fun `verified folder aliases retain original manual provenance instead of consuming a download bridge`() = runBlocking {
        val (song, source) = recoveredLegacySource()
        rememberCurrentBridge(song)
        val previousRootQueries = currentRootQueries.get()

        val result = coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source)

        assertEquals(LocalPlaybackReferenceResolution.Playable(audioUri.toString()), result)
        assertEquals(previousRootQueries, currentRootQueries.get())
    }

    @Test
    fun `unknown verified alias provenance remains temporarily unavailable`() = runBlocking {
        val (song, source) = recoveredLegacySource()
        explicitReferencesKnown = false

        assertUnknown(coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source))
    }

    @Test
    fun `verified readable declared files retain the preferred manual anchor instead of a completed bridge`() = runBlocking {
        listOf(audio.absolutePath, Uri.fromFile(audio).toString()).forEach { reference ->
            val (song, source) = verifiedDeclaredFileSource(reference)
            val preferred = requireNotNull(song.mediaUri)
            explicitReferences.add(preferred)
            rememberCurrentBridge(song)
            val bridge = requireNotNull(completedReferences.peekCompletedAudioReference(song))
            val previousAudioQueries = audioQueries.get()
            val previousRootQueries = currentRootQueries.get()

            assertUnknown(coordinator.resolvePermittedLocalPlayback(
                context, song, reference, ResolvedLocalMediaSource.direct(song, reference)
            ))

            val result = coordinator.resolvePermittedLocalPlayback(context, song, reference, source)

            assertEquals(LocalPlaybackReferenceResolution.Playable(reference), result)
            assertEquals(preferred, source.anchorFor(song, reference))
            assertSame(bridge, completedReferences.peekCompletedAudioReference(song))
            assertEquals(previousAudioQueries, audioQueries.get())
            assertEquals(previousRootQueries, currentRootQueries.get())
        }
    }

    @Test
    fun `unknown preferred provenance blocks a verified readable declared file before provider probing`() = runBlocking {
        val (song, source) = verifiedDeclaredFileSource()
        unknownExplicitReferences.add(requireNotNull(song.mediaUri))
        explicitReferences.add(source.reference)
        rememberCurrentBridge(song)
        val bridge = requireNotNull(completedReferences.peekCompletedAudioReference(song))
        val previousAudioQueries = audioQueries.get()
        val previousRootQueries = currentRootQueries.get()
        val previousDirectoryQueries = directoryQueries.get()

        val result = coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source)

        assertUnknown(result)
        assertSame(bridge, completedReferences.peekCompletedAudioReference(song))
        assertEquals(previousAudioQueries, audioQueries.get())
        assertEquals(previousRootQueries, currentRootQueries.get())
        assertEquals(previousDirectoryQueries, directoryQueries.get())
    }

    @Test
    fun `verified readable declared files preserve managed bridges when both references are nonmanual`() = runBlocking {
        val (song, source) = verifiedDeclaredFileSource()
        rememberCurrentBridge(song)
        val bridge = requireNotNull(completedReferences.peekCompletedAudioReference(song))

        val result = coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source)

        assertEquals(LocalPlaybackReferenceResolution.Playable(currentAudioUri.toString()), result)
        assertSame(bridge, completedReferences.peekCompletedAudioReference(song))
    }

    @Test
    fun `a history snapshot keeps its selected source after the last playlist membership is removed`() = runBlocking {
        val song = scannedSong(explicit = false).let { it.copy(explicitLocalSourceReference = it.mediaUri) }
        rememberCurrentBridge(song)

        val result = coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri)

        assertEquals(LocalPlaybackReferenceResolution.Playable(audioUri.toString()), result)
    }

    @Test
    fun `a history snapshot can play its exact source while global playlist membership is unknown`() = runBlocking {
        val song = scannedSong(explicit = false).let { it.copy(explicitLocalSourceReference = it.mediaUri) }
        explicitReferencesKnown = false
        rememberCurrentBridge(song)

        val result = coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri)

        assertEquals(LocalPlaybackReferenceResolution.Playable(audioUri.toString()), result)
    }

    @Test
    fun `a missing manual history source cannot be replaced by its remote identity bridge`() = runBlocking {
        val song = scannedSong(explicit = false).let { it.copy(explicitLocalSourceReference = it.mediaUri) }
        rememberCurrentBridge(song)
        externalAudioMissing = true
        val previousRootQueries = currentRootQueries.get()

        val result = coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri)

        assertEquals(LocalPlaybackReferenceResolution.Missing, result)
        assertEquals(previousRootQueries, currentRootQueries.get())
    }

    @Test
    fun `a verified history alias uses its snapshot without current playlist membership`() = runBlocking {
        val (song, source) = recoveredLegacySource(snapshotMarker = true)
        explicitReferences.remove(requireNotNull(song.mediaUri))
        explicitReferencesKnown = false
        rememberCurrentBridge(song)

        val result = coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source)

        assertEquals(LocalPlaybackReferenceResolution.Playable(audioUri.toString()), result)
    }

    @Test
    fun `an unverified alias cannot replace a manual history snapshot with a bridge`() = runBlocking {
        val (song, verified) = recoveredLegacySource(snapshotMarker = true)
        explicitReferences.remove(requireNotNull(song.mediaUri))
        val source = ResolvedLocalMediaSource.direct(song, verified.reference)
        rememberCurrentBridge(song)
        val previousQueries = audioQueries.get()

        val result = coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source)

        assertUnknown(result)
        assertEquals(previousQueries, audioQueries.get())
    }

    @Test
    fun `invalid history markers retain ordinary managed bridge recovery`() = runBlocking {
        val scanned = scannedSong(explicit = false)
        listOf(alternateAudioUri.toString(), "https://example.com/song.flac", "", " ").forEach { marker ->
            val song = scanned.copy(explicitLocalSourceReference = marker)
            rememberCurrentBridge(song)

            val result = coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri)

            assertEquals(LocalPlaybackReferenceResolution.Playable(currentAudioUri.toString()), result)
        }
    }

    @Test
    fun `a stale history marker cannot override unknown global membership for a different source`() = runBlocking {
        val song = scannedSong(explicit = false).copy(explicitLocalSourceReference = alternateAudioUri.toString())
        explicitReferencesKnown = false
        rememberCurrentBridge(song)
        val previousQueries = audioQueries.get()

        assertUnknown(coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri))
        assertEquals(previousQueries, audioQueries.get())
    }

    @Test
    fun `a verified manual alias in the current root keeps its selected file instead of a song bridge`() = runBlocking {
        val (song, source) = recoveredLegacySource()
        configureSelectedRootWithBridge(song)

        assertEquals(LocalPlaybackReferenceResolution.Playable(audioUri.toString()),
            coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source))
    }

    @Test
    fun `a verified alias in the current root cannot consume a bridge while provenance is unknown`() = runBlocking {
        val (song, source) = recoveredLegacySource()
        unknownExplicitReferences.add(requireNotNull(song.mediaUri))
        configureSelectedRootWithBridge(song)

        assertUnknown(coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source))
    }

    @Test
    fun `a verified alias in the current root retains its bridge when neither reference is manual`() = runBlocking {
        val (song, source) = recoveredLegacySource()
        explicitReferences.remove(requireNotNull(song.mediaUri))
        configureSelectedRootWithBridge(song)

        assertEquals(LocalPlaybackReferenceResolution.Playable(alternateAudioUri.toString()),
            coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source))
    }

    @Test
    fun `current root references without exact manual evidence retain their bridge without a source context`() = runBlocking {
        val (song, source) = recoveredLegacySource()
        configureSelectedRootWithBridge(song)

        assertEquals(LocalPlaybackReferenceResolution.Playable(alternateAudioUri.toString()),
            coordinator.resolvePermittedLocalPlayback(context, song, source.reference))
    }

    @Test
    fun `the same recovered URI cannot borrow provenance from another source snapshot`() = runBlocking {
        val (song, source) = recoveredLegacySource()
        val otherSong = song.copy(localFilePath = "/storage/emulated/0/Other/Scanned Song.flac")

        val result = coordinator.resolvePermittedLocalPlayback(context, otherSong, source.reference, source)

        assertTrue(result !is LocalPlaybackReferenceResolution.Playable)
    }

    @Test
    fun `unverified direct aliases cannot borrow the preferred reference provenance`() = runBlocking {
        val (song, source) = recoveredLegacySource()
        val unverified = ResolvedLocalMediaSource.direct(song, source.reference)

        val result = coordinator.resolvePermittedLocalPlayback(context, song, source.reference, unverified)

        assertTrue(result !is LocalPlaybackReferenceResolution.Playable)
    }

    @Test
    fun `an unverified alias cannot consume a download bridge for a declared manual source`() = runBlocking {
        assertUnverifiedAliasBlocksBridge(manualSourceKnown = true)
    }

    @Test
    fun `an unverified alias cannot consume a download bridge while declared provenance is unknown`() = runBlocking {
        assertUnverifiedAliasBlocksBridge(manualSourceKnown = false)
    }

    @Test
    fun `an unverified current root alias cannot replace its declared manual source with a bridge`() = runBlocking {
        assertUnverifiedAliasBlocksBridge(manualSourceKnown = true, sameRootBridge = true)
    }

    @Test
    fun `an unverified current root alias cannot use a bridge while declared provenance is unknown`() = runBlocking {
        assertUnverifiedAliasBlocksBridge(manualSourceKnown = false, sameRootBridge = true)
    }

    @Test
    fun `an unverified alias retains managed recovery when neither reference is manual`() = runBlocking {
        val song = scannedSong(explicit = false).copy(mediaUri = "content://media/external/audio/media/859470")
        val source = ResolvedLocalMediaSource.direct(song, audioUri.toString())
        rememberCurrentBridge(song)

        assertEquals(LocalPlaybackReferenceResolution.Playable(currentAudioUri.toString()),
            coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source))
    }

    @Test
    fun `verified aliases still wait behind the directory mutation fence`() = runBlocking {
        val (song, source) = recoveredLegacySource()
        val lease = ManagedDownloadDirectoryMutationFence.closeAndDrain()
        try {
            assertTrue(coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source)
                is LocalPlaybackReferenceResolution.TemporarilyUnavailable)
        } finally {
            lease.close()
        }
    }

    @Test
    fun `external scan with revoked provider permission stays temporarily unavailable`() = runBlocking {
        val scanned = scannedSong()
        externalReadGranted = false

        val result = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

        assertTrue(result is LocalPlaybackReferenceResolution.TemporarilyUnavailable)
        assertTrue((result as LocalPlaybackReferenceResolution.TemporarilyUnavailable).evidence is
            ManagedDownloadReferenceLookup.Result.PermissionLost)
        assertRemoteIdentity(scanned)
    }

    @Test
    fun `another reference cannot borrow explicit membership from the same remote song`() = runBlocking {
        val scanned = scannedSong()
        val otherReference = alternateAudioUri.toString()
        val otherSong = scanned.copy(mediaUri = otherReference, localFilePath = null)

        val result = coordinator.resolvePermittedLocalPlayback(context, otherSong, otherReference)

        assertTrue(result !is LocalPlaybackReferenceResolution.Playable)
    }

    @Test
    fun `external reference without local files membership keeps managed root restrictions`() = runBlocking {
        val scanned = scannedSong(explicit = false)

        val result = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

        assertTrue(result !is LocalPlaybackReferenceResolution.Playable)
    }

    @Test
    fun `managed song keeps its current root completion bridge instead of the old reference`() = runBlocking {
        val scanned = scannedSong(explicit = false)
        val currentReference = currentAudioUri.toString()
        rememberCurrentBridge(scanned)

        val result = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

        assertEquals(LocalPlaybackReferenceResolution.Playable(currentReference), result)
    }

    @Test
    fun `managed current root reference still waits behind the directory mutation fence`() = runBlocking {
        val scanned = scannedSong(explicit = false)
        val currentReference = currentAudioUri.toString()
        val lease = ManagedDownloadDirectoryMutationFence.closeAndDrain()
        try {
            val result = coordinator.resolvePermittedLocalPlayback(
                context,
                scanned.copy(mediaUri = currentReference, localFilePath = null),
                currentReference
            )

            assertTrue(result is LocalPlaybackReferenceResolution.TemporarilyUnavailable)
        } finally {
            lease.close()
        }
    }

    @Test
    fun `explicit pending reference cannot use external scan playback exemption`() = runBlocking {
        val scanned = scannedSong()
        val pendingReference = pendingAudioUri.toString()
        explicitReferences.add(pendingReference)
        val previousAudioQueries = audioQueries.get()

        val result = coordinator.resolvePermittedLocalPlayback(
            context,
            scanned.copy(mediaUri = pendingReference, localFilePath = null),
            pendingReference
        )

        assertTrue(result !is LocalPlaybackReferenceResolution.Playable)
        assertEquals(previousAudioQueries, audioQueries.get())
    }

    @Test
    fun `unknown membership waits without consuming the bridge and later true uses the selected file`() = runBlocking {
        val scanned = scannedSong()
        rememberCurrentBridge(scanned)
        explicitReferencesKnown = false
        val previousAudioQueries = audioQueries.get()
        val previousRootQueries = currentRootQueries.get()

        val unknown = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

        assertUnknown(unknown)
        assertEquals(previousAudioQueries, audioQueries.get())
        assertEquals(previousRootQueries, currentRootQueries.get())

        explicitReferencesKnown = true
        val known = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

        assertEquals(LocalPlaybackReferenceResolution.Playable(audioUri.toString()), known)
    }

    @Test
    fun `unknown membership waits and later false retains normal managed bridge recovery`() = runBlocking {
        val scanned = scannedSong(explicit = false)
        rememberCurrentBridge(scanned)
        explicitReferencesKnown = false
        val previousAudioQueries = audioQueries.get()
        val previousRootQueries = currentRootQueries.get()

        val unknown = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

        assertUnknown(unknown)
        assertEquals(previousAudioQueries, audioQueries.get())
        assertEquals(previousRootQueries, currentRootQueries.get())

        explicitReferencesKnown = true
        val known = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

        assertEquals(LocalPlaybackReferenceResolution.Playable(currentAudioUri.toString()), known)
    }

    @Test
    fun `missing explicit source cannot be replaced by a bridge sharing its remote identity`() = runBlocking {
        val scanned = scannedSong()
        rememberCurrentBridge(scanned)
        externalAudioMissing = true
        val previousRootQueries = currentRootQueries.get()

        val result = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

        assertEquals(LocalPlaybackReferenceResolution.Missing, result)
        assertEquals(previousRootQueries, currentRootQueries.get())
    }

    @Test
    fun `provider failure for an explicit source cannot fall back to an identity bridge`() = runBlocking {
        val scanned = scannedSong()
        rememberCurrentBridge(scanned)
        externalAudioFailure = IllegalStateException("fixture provider unavailable")
        val previousRootQueries = currentRootQueries.get()

        val result = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

        assertTrue(result is LocalPlaybackReferenceResolution.TemporarilyUnavailable)
        assertTrue((result as LocalPlaybackReferenceResolution.TemporarilyUnavailable).evidence is
            ManagedDownloadReferenceLookup.Result.ProviderFailure)
        assertEquals(previousRootQueries, currentRootQueries.get())
    }

    @Test
    fun `cancelled explicit source stays blocked without probing the provider`() = runBlocking {
        val scanned = scannedSong()
        val key = scanned.stableKey()
        val added = GlobalDownloadManager.cancelledSongKeys.add(key)
        val previousAudioQueries = audioQueries.get()
        try {
            val result = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

            assertTrue(result !is LocalPlaybackReferenceResolution.Playable)
            assertEquals(previousAudioQueries, audioQueries.get())
        } finally {
            if (added) GlobalDownloadManager.cancelledSongKeys.remove(key)
        }
    }

    @Test
    fun `explicit staging reference stays blocked without probing the provider`() = runBlocking {
        val scanned = scannedSong()
        val stagingReference = stagingAudioUri.toString()
        explicitReferences.add(stagingReference)
        val previousAudioQueries = audioQueries.get()

        val result = coordinator.resolvePermittedLocalPlayback(
            context,
            scanned.copy(mediaUri = stagingReference, localFilePath = null),
            stagingReference
        )

        assertTrue(result !is LocalPlaybackReferenceResolution.Playable)
        assertEquals(previousAudioQueries, audioQueries.get())
    }

    @Test
    fun `explicit source stays blocked by an active directory fence without probing`() = runBlocking {
        val scanned = scannedSong()
        val previousAudioQueries = audioQueries.get()
        val lease = ManagedDownloadDirectoryMutationFence.closeAndDrain()
        try {
            val result = coordinator.resolvePermittedLocalPlayback(context, scanned, scanned.mediaUri)

            assertTrue(result is LocalPlaybackReferenceResolution.TemporarilyUnavailable)
            assertEquals(previousAudioQueries, audioQueries.get())
        } finally {
            lease.close()
        }
    }

    private fun assertUnknown(result: LocalPlaybackReferenceResolution) {
        assertTrue(result is LocalPlaybackReferenceResolution.TemporarilyUnavailable)
        assertEquals(ManagedDownloadReferenceLookup.Result.OutOfScope,
            (result as LocalPlaybackReferenceResolution.TemporarilyUnavailable).evidence)
    }

    private suspend fun assertExactScannedSourceKeepsFile(directoryUri: String?) {
        val song = scannedSong()
        configureSelectedRootWithBridge(song, directoryUri)

        assertEquals(LocalPlaybackReferenceResolution.Playable(audioUri.toString()),
            coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri))
    }

    private suspend fun assertExactScannedSourceStaysMissing(directoryUri: String?) {
        val song = scannedSong()
        configureSelectedRootWithBridge(song, directoryUri)
        externalAudioMissing = true
        val previousDirectoryQueries = directoryQueries.get()

        assertEquals(LocalPlaybackReferenceResolution.Missing,
            coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri))
        assertEquals(previousDirectoryQueries, directoryQueries.get())
    }

    private suspend fun assertUnknownExactScannedSourceBlocksBridge(directoryUri: String?) {
        val song = scannedSong(explicit = false)
        unknownExplicitReferences.add(requireNotNull(song.mediaUri))
        configureSelectedRootWithBridge(song, directoryUri)
        val previousQueries = audioQueries.get()

        assertUnknown(coordinator.resolvePermittedLocalPlayback(context, song, song.mediaUri))
        assertEquals(previousQueries, audioQueries.get())
    }

    private suspend fun recoveredLegacySource(snapshotMarker: Boolean = false): Pair<SongItem, ResolvedLocalMediaSource> {
        val original = "content://media/external/audio/media/859470"
        val song = scannedSong(explicit = false).copy(
            mediaUri = original,
            localFilePath = "/storage/emulated/0/NeriPlayer-Download2/$AUDIO_NAME",
            explicitLocalSourceReference = original.takeIf { snapshotMarker }
        )
        explicitReferences.add(original)
        return song to requireNotNull(ResolvedLocalMediaSource.recoverFromFolderGrant(context, song))
    }

    private suspend fun verifiedDeclaredFileSource(reference: String = audio.absolutePath): Pair<SongItem, ResolvedLocalMediaSource> {
        val preferred = Uri.parse("content://media/external/audio/media/859470")
        val song = scannedSong(explicit = false).copy(mediaUri = preferred.toString(), localFilePath = reference)
        val proofQueries = AtomicInteger()
        val resolver = mock(ContentResolver::class.java)
        val proofContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
        }
        doAnswer { invocation ->
            assertEquals(preferred, invocation.getArgument<Uri>(0))
            assertEquals(listOf("_data"), invocation.getArgument<Array<String>>(1).toList())
            proofQueries.incrementAndGet()
            MatrixCursor(arrayOf("_data")).apply { addRow(arrayOf(audio.canonicalPath)) }
        }.`when`(resolver).query(any(), any(), any(), any(), any())
        doAnswer {
            throw FileNotFoundException("preferred MediaStore reference is unreadable")
        }.`when`(resolver).openAssetFileDescriptor(eq(preferred), eq("r"))

        val source = requireNotNull(ResolvedLocalMediaSource.verifiedReadableDeclaredFile(proofContext, song, reference))

        assertTrue("the declared file contains real readable bytes", audio.inputStream().use { it.read() >= 0 })
        assertTrue("the preferred MediaStore row proves the same canonical file", proofQueries.get() > 0)
        assertEquals(reference, source.reference)
        return song to source
    }

    private suspend fun assertUnverifiedAliasBlocksBridge(manualSourceKnown: Boolean, sameRootBridge: Boolean = false) {
        val original = "content://media/external/audio/media/859470"
        val song = scannedSong(explicit = false).copy(mediaUri = original)
        if (manualSourceKnown) explicitReferences.add(original) else unknownExplicitReferences.add(original)
        val source = ResolvedLocalMediaSource.direct(song, audioUri.toString())
        if (sameRootBridge) configureSelectedRootWithBridge(song) else rememberCurrentBridge(song)

        assertUnknown(coordinator.resolvePermittedLocalPlayback(context, song, source.reference, source))
    }

    private fun configureSelectedRootWithBridge(song: SongItem, directoryUri: String? = externalRoot.toString()) {
        ManagedDownloadStorage.settings.updateDirectoryUri(directoryUri)
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        rememberCurrentBridge(song, alternateAudioUri)
    }

    private fun rememberCurrentBridge(song: SongItem, uri: Uri = currentAudioUri) {
        val reference = uri.toString()
        completedReferences.rememberCompletedAudioReference(
            song,
            ManagedDownloadStorage.StoredEntry(
                name = DocumentsContract.getDocumentId(uri).substringAfterLast('/'),
                reference = reference,
                mediaUri = reference,
                localFilePath = null,
                sizeBytes = audio.length(),
                lastModifiedMs = 1_000L
            )
        )
    }

    private suspend fun scannedSong(explicit: Boolean = true): SongItem {
        val result = LocalAudioImportManager.scanFolderSongs(context, externalRoot)
        assertTrue("the selected folder scan completes", result.completed)
        assertEquals(0, result.failedCount)
        val song = result.songs.single()
        assertEquals(audioUri.toString(), song.mediaUri)
        assertRemoteIdentity(song)
        if (explicit) explicitReferences.add(requireNotNull(song.mediaUri))
        return song
    }

    private fun assertRemoteIdentity(song: SongItem) {
        assertEquals(859470L, song.id)
        assertEquals("netease", song.channelId)
        assertEquals("859470", song.audioId)
        assertEquals("859470|netease|", song.sourceStableKey)
    }

    private fun readableFile(uri: Uri): File {
        checkReadable(uri)
        return when (uri) {
            audioUri, alternateAudioUri, currentAudioUri, pendingAudioUri, stagingAudioUri -> audio
            metadataUri -> metadata
            else -> throw FileNotFoundException("fixture document is missing")
        }
    }

    private fun checkReadable(uri: Uri) {
        if (isExternal(uri) && !externalReadGranted) throw SecurityException("fixture read grant revoked")
        if (uri == audioUri) externalAudioFailure?.let { throw it }
    }

    private fun isExternal(uri: Uri): Boolean =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() == EXTERNAL_ROOT_ID

    private fun valueFor(id: String, column: String): Any? = when (column) {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
        OpenableColumns.DISPLAY_NAME -> id.substringAfterLast('/')
        DocumentsContract.Document.COLUMN_MIME_TYPE -> when (id) {
            CURRENT_ROOT_ID, EXTERNAL_ROOT_ID -> DocumentsContract.Document.MIME_TYPE_DIR
            AUDIO_ID, CURRENT_AUDIO_ID, ALTERNATE_AUDIO_ID, PENDING_AUDIO_ID, STAGING_AUDIO_ID -> "audio/flac"
            else -> "application/json"
        }
        OpenableColumns.SIZE -> if (id == METADATA_ID) metadata.length() else audio.length()
        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1_000L
        DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        "duration" -> 180_000L
        else -> null
    }

    private fun grant(uri: Uri, write: Boolean = false): UriPermission = mock(UriPermission::class.java).also {
        doReturn(uri).`when`(it).uri
        doReturn(true).`when`(it).isReadPermission
        doReturn(write).`when`(it).isWritePermission
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val CURRENT_ROOT_ID = "primary:NeriPlayer-Download"
        const val EXTERNAL_ROOT_ID = "primary:NeriPlayer-Download2"
        const val AUDIO_NAME = "Scanned Song.flac"
        const val METADATA_NAME = "$AUDIO_NAME.npmeta.json"
        const val AUDIO_ID = "$EXTERNAL_ROOT_ID/$AUDIO_NAME"
        const val METADATA_ID = "$EXTERNAL_ROOT_ID/$METADATA_NAME"
        const val CURRENT_AUDIO_ID = "$CURRENT_ROOT_ID/$AUDIO_NAME"
        const val ALTERNATE_AUDIO_ID = "$EXTERNAL_ROOT_ID/Another Song.flac"
        const val PENDING_AUDIO_ID = "$EXTERNAL_ROOT_ID/$AUDIO_NAME.npdl_pending.operation.flac"
        const val STAGING_AUDIO_ID = "$EXTERNAL_ROOT_ID/download_staging/$AUDIO_NAME"
    }
}
