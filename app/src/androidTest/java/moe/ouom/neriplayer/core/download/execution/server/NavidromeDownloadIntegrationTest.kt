package moe.ouom.neriplayer.core.download.execution.server

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.EventListener
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.manager.batch.startBatchDownload
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioMetadataStore
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.identity.toPlaybackSongItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.EmulatorOfflineNetworkFixture
import moe.ouom.neriplayer.testutil.grantRuntimePermissions
import moe.ouom.neriplayer.testutil.playbackRuntimePermissions
import moe.ouom.neriplayer.ui.screen.server.MusicServerScreen
import moe.ouom.neriplayer.ui.screen.server.album.ServerAlbumDetailScreen
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.server.MusicServerViewModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavidromeDownloadIntegrationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val report by lazy { File(context.cacheDir, "navidrome-download-result.json") }
    private var phase = "guard"
    private val completedFormats = mutableListOf<String>()
    private val playbackChecks = mutableListOf<String>()

    @Test
    fun realDownloadsRemainPlayableOfflineAfterRemovingTheirLocalProfile() = runBlocking {
        val fixture = requireIsolatedFixture()
        val app = context.applicationContext as Application
        instrumentation.runOnMainSync {
            if (!AppContainer.isInitialized()) AppContainer.initialize(app)
        }
        val repository = AppContainer.subsonicRepository
        repository.accounts.load()
        assumeTrue("fixture requires an application without saved server profiles",
            repository.accounts.profiles.value.isEmpty())
        assumeTrue("fixture requires an application without a playback queue",
            PlayerManager.currentQueueFlow.value.isEmpty() && !PlayerManager.isPlayingFlow.value)
        assumeTrue("fixture never takes over an existing playback service",
            !AudioPlayerService.isInstanceActiveForDiagnostics())
        grantRuntimePermissions(*playbackRuntimePermissions())
        GlobalDownloadManager.initialize(context)
        withTimeout(30_000L) { GlobalDownloadManager.startupProgressRestoreReady.await() }
        GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)
        assumeTrue("fixture requires an application without existing downloads",
            GlobalDownloadManager.downloadedSongs.value.isEmpty() &&
                !GlobalDownloadManager.downloadTaskSummary.value.hasDownloadManagerEntry)

        var ownedProfile: SubsonicProfile? = null
        var startup: Job? = null
        var scenario: ActivityScenario<TestComposeActivity>? = null
        var selected = emptyMap<String, SongItem>()
        var failure: Throwable? = null
        try {
            phase = "local-profile"
            repository.addAccount("PR511 isolated download fixture", fixture.baseUrl,
                fixture.username, fixture.password)
            val profile = repository.accounts.profiles.value.single()
            ownedProfile = profile
            phase = "paged-library"
            val songs = mutableListOf<SongItem>()
            for (offset in listOf(0, 30, 60, 90)) {
                val page = repository.search(profile.id, "", offset, 30)
                songs += page
                if (page.size < 30) break
            }
            assertEquals("local fixture must expose its full paginated library", 65, songs.size)
            assertEquals(65, songs.map(SongItem::stableKey).distinct().size)
            val byFormat = linkedMapOf<String, SongItem>()
            for (song in songs.sortedBy { it.name }) {
                val source = repository.download(song)
                val format = source.fileExtensionHint ?: continue
                if (format in Formats && format !in byFormat) byFormat[format] = song
                if (byFormat.size == Formats.size) break
            }
            assertEquals(Formats, byFormat.keys)
            selected = byFormat
            scenario = ActivityScenario.launch(TestComposeActivity::class.java)
            captureRealBrowser(scenario, repository, profile.id)
            phase = "real-download-queue"
            startup = GlobalDownloadManager.startBatchDownload(context, selected.values.toList(),
                skipTrafficRiskPrompt = true, cleanupBeforeStart = false)
            assertNotNull("real batch admission must start", startup)
            withTimeout(60_000L) { startup?.join() }
            val selectedKeys = selected.values.map(SongItem::stableKey).toSet()
            await("five finalized downloads", 300_000L) {
                val tasks = GlobalDownloadManager.downloadTasks.value.filter { it.song.stableKey() in selectedKeys }
                assertFalse("real download queue reported a failed fixture task",
                    tasks.any { it.status == DownloadStatus.FAILED })
                val downloaded = GlobalDownloadManager.downloadedSongs.value
                selectedKeys.all { key -> downloaded.any { it.stableKey == key } } &&
                    tasks.none { it.status in PendingStatuses }
            }
            GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)
            val downloaded = selected.mapValues { (_, song) ->
                GlobalDownloadManager.downloadedSongs.value.single { it.stableKey == song.stableKey() }
            }
            await("download sidecar enrichment", 60_000L) {
                downloaded.all { (format, item) ->
                    val local = item.toPlaybackSongItem()
                    val audio = storedEntry(item)
                    val metadata = DownloadedAudioMetadataStore(1, 0L, "NavidromeFixture").read(context, audio)
                    metadata?.downloadFinalized == true && !metadata.audioPublicationPending &&
                        ManagedDownloadStorage.findCoverReference(context, audio) != null &&
                        (format !in setOf("mp3", "flac", "m4a") ||
                            !ManagedDownloadStorage.readLyrics(context, local, translated = false).isNullOrBlank())
                }
            }
            phase = "audio-and-sidecars"
            selected.forEach { (format, song) ->
                verifyPublishedDownload(format, song, downloaded.getValue(format), fixture.password)
                completedFormats += format
            }
            phase = "remove-local-profile"
            repository.accounts.remove(profile.id)
            assertTrue(repository.accounts.profiles.value.isEmpty())
            downloaded.values.forEach { audio -> openLocal(audio.filePath).use { assertTrue(it.read() >= 0) } }

            phase = "offline-media3-playback"
            EmulatorOfflineNetworkFixture.start(context).use {
                selected.forEach { (format, song) ->
                    playOffline(app, song, "$format-original")
                    playOffline(app, downloaded.getValue(format).toPlaybackSongItem(), "$format-local")
                }
            }
            phase = "complete"
            writeReport("pass")
        } catch (error: Throwable) {
            failure = error
            writeReport("fail", error.javaClass.simpleName)
            // 失败信息只报告阶段和异常类型，不串联可能带认证 URL 的底层异常
            throw AssertionError("Navidrome fixture failed at $phase (${error.javaClass.simpleName}); " +
                "diagnostics are in cache/navidrome-download-result.json").apply { stackTrace = error.stackTrace }
        } finally {
            startup?.cancel()
            val ownedKeys = selected.values.map(SongItem::stableKey).toSet()
            GlobalDownloadManager.downloadTasks.value.filter {
                it.song.stableKey() in ownedKeys && it.status in PendingStatuses
            }.forEach { GlobalDownloadManager.cancelDownloadTask(it.song.stableKey()) }
            val cleanupErrors = mutableListOf<String>()
            fun cleanup(name: String, action: () -> Unit) {
                if (runCatching(action).isFailure) cleanupErrors += name
            }
            cleanup("player") {
                instrumentation.runOnMainSync {
                    context.stopService(Intent(context, AudioPlayerService::class.java))
                    if (PlayerManager.isPlayerInitialized()) PlayerManager.release()
                }
            }
            cleanup("activity") { scenario?.close() }
            ownedProfile?.let { profile ->
                if (repository.accounts.profile(profile.id) != null) {
                    cleanup("local-profile") { runBlocking { repository.accounts.remove(profile.id) } }
                }
            }
            // 下载和 sidecar 保留在专用 AVD 供诊断，由调用方关闭本次隔离 AVD 后统一清理
            if (cleanupErrors.isNotEmpty()) {
                writeReport(if (failure == null) "cleanup-failed" else "fail",
                    cleanupErrors.joinToString(","))
                if (failure == null) throw AssertionError("Fixture cleanup failed: ${cleanupErrors.joinToString()}")
            }
        }
    }

    private fun requireIsolatedFixture(): Fixture {
        assumeTrue("local Navidrome integration is opt-in",
            InstrumentationRegistry.getArguments().getString("navidromeFixture") == "true")
        assumeTrue("fixture can only change networking on an emulator",
            Build.VERSION.SDK_INT >= 30 && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() }
        }
        assumeTrue("fixture requires the dedicated PR511 AVD", avd == "NeriPlayer_PR511_Test")
        if (report.exists()) check(report.delete()) { "Cannot replace this fixture's previous diagnostic report" }
        val file = File(context.cacheDir, "navidrome-test.json")
        assumeTrue("fixture credentials must be supplied through private app storage", file.isFile)
        val data = try { JSONObject(file.readText()) }
        catch (_: Exception) { throw AssertionError("Private Navidrome fixture JSON is invalid") }
        val baseUrl = data.optString("baseUrl")
        val uri = Uri.parse(baseUrl)
        assumeTrue("fixture only accepts the task-local Navidrome instance",
            uri.scheme == "http" && uri.host == "10.0.2.2" && uri.port == 45331 &&
                uri.path == "/music/" && uri.query == null && uri.userInfo == null && uri.fragment == null)
        val username = data.optString("username")
        val password = data.optString("password")
        assumeTrue("fixture credentials are incomplete", username.isNotBlank() && password.isNotEmpty())
        return Fixture(baseUrl, username, password)
    }

    private suspend fun verifyPublishedDownload(
        format: String,
        original: SongItem,
        downloaded: DownloadedSong,
        password: String
    ) {
        assertTrue("final audio must contain bytes", downloaded.fileSize > 0L)
        assertEquals(original.stableKey(), downloaded.stableKey)
        val ref = checkNotNull(ServerSongRef.from(original))
        assertEquals(ref.mediaUri, downloaded.sourceMediaUri)
        val local = downloaded.toPlaybackSongItem()
        assertEquals(original.stableKey(), local.stableKey())
        assertEquals(ref, ServerSongRef.from(local))
        val anotherProfile = ServerSongRef(UUID.randomUUID().toString(), ref.songId)
        assertNotEquals(anotherProfile.audioId, local.audioId)
        assertNotEquals(anotherProfile.cacheKey, ref.cacheKey)
        val uri = local.mediaUri.orEmpty()
        assertTrue("download projection must use a local address",
            uri.startsWith("/") || Uri.parse(uri).scheme in setOf("file", "content"))
        val audio = storedEntry(downloaded)
        assertEquals("published audio must retain its original container extension", format,
            audio.name.substringAfterLast('.'))
        val metadataEntry = ManagedDownloadStorage.findMetadataForAudio(context, audio)
        assertNotNull("published audio must have a restorable sidecar", metadataEntry)
        val metadataText = checkNotNull(ManagedDownloadStorage.readText(context, checkNotNull(metadataEntry).reference))
        assertFalse("sidecar must not contain the fixture credential", metadataText.contains(password))
        assertFalse("sidecar must not persist authenticated query parameters",
            Regex("[?&](?:p|t|s)=").containsMatchIn(metadataText))
        val metadata = checkNotNull(DownloadedAudioMetadataStore(1, 0L, "NavidromeFixture")
            .read(context, audio, metadataEntry))
        assertTrue("publication must finish before offline playback", metadata.downloadFinalized == true)
        assertFalse(metadata.audioPublicationPending)
        assertEquals(original.stableKey(), metadata.stableKey)
        val cover = ManagedDownloadStorage.findCoverReference(context, audio)
        assertNotNull("fixture cover must be saved locally", cover)
        openLocal(checkNotNull(cover)).use { assertTrue(it.read() >= 0) }
        if (format in setOf("mp3", "flac", "m4a")) {
            val lyric = ManagedDownloadStorage.readLyrics(context, local, translated = false)
            assertTrue("fixture lyric must survive in local assets", !lyric.isNullOrBlank())
            val lyricReference = ManagedDownloadStorage.findLyricLocation(context, local.id,
                listOf(audio.nameWithoutExtension), translated = false)
            assertNotNull("fixture lyric must have a local sidecar", lyricReference)
            openLocal(checkNotNull(lyricReference)).use { assertTrue(it.read() >= 0) }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(uri.takeUnless { it.startsWith("/") }
                ?: Uri.fromFile(File(uri)).toString()), null)
            val index = (0 until extractor.trackCount).first { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(index)
            assertTrue("published $format must expose an audio sample",
                extractor.readSampleData(ByteBuffer.allocate(512 * 1024), 0) > 0)
        } finally { extractor.release() }
    }

    private suspend fun captureRealBrowser(
        scenario: ActivityScenario<TestComposeActivity>,
        repository: SubsonicRepository,
        profileId: String
    ) {
        if (InstrumentationRegistry.getArguments().getString("captureMusicServerUi") != "true") return
        phase = "real-browser-screenshots"
        val previousLoader = Coil.imageLoader(context)
        val loadedImages = ConcurrentHashMap.newKeySet<String>()
        val imageLoader = previousLoader.newBuilder().okHttpClient(AppContainer.sharedOkHttpClient)
            .memoryCache(MemoryCache.Builder(context).maxSizePercent(0.02).build())
            .diskCache(null)
            .eventListenerFactory { _ ->
                object : EventListener {
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        loadedImages += request.data.toString()
                    }
                }
            }.build()
        val style = mutableStateOf(UiStyle.Clean)
        val dark = mutableStateOf(false)
        val selectedAlbum = mutableStateOf<ServerAlbum?>(null)
        lateinit var viewModel: MusicServerViewModel
        try {
            Coil.setImageLoader(imageLoader)
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity, viewModelFactory {
                    initializer { MusicServerViewModel(repository) }
                })[MusicServerViewModel::class.java]
                activity.setContent {
                    NeriTheme(followSystemDark = false, forceDark = dark.value, dynamicColor = false,
                        seedColorHex = "6750A4", uiStyle = style.value, animateColors = false) {
                        Surface(Modifier.fillMaxSize()) {
                            val album = selectedAlbum.value
                            if (album != null) ServerAlbumDetailScreen(
                                album = album, onBack = { selectedAlbum.value = null },
                                onSongClick = { songs, index -> PlayerManager.playPlaylist(songs, index) }
                            ) else Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                                MusicServerScreen(onAlbumClick = { selectedAlbum.value = it },
                                    vm = viewModel, bottomContentPadding = 0.dp)
                            }
                        }
                    }
                }
            }
            await("real browser account and albums", 30_000L) {
                val state = viewModel.state.value
                assertTrue("browser must load the actual fixture", state.error == null && state.accountsError == null)
                !state.accountsLoading && !state.loading && state.profileId == profileId && state.albums.isNotEmpty()
            }
            val firstAlbum = viewModel.state.value.albums.first()
            instrumentation.runOnMainSync { viewModel.setCategory(ServerLibraryCategory.SONGS) }
            await("real browser song page", 30_000L) {
                val state = viewModel.state.value
                assertTrue("browser song page must not fail", state.error == null)
                !state.loading && state.songs.size >= 5
            }
            val firstSongs = viewModel.state.value.songs.take(3)
            val covers = firstSongs.mapNotNull { it.coverUrl }.toSet()
            assertTrue("real fixture rows must have artwork", covers.isNotEmpty())
            for (uiStyle in listOf(UiStyle.Clean, UiStyle.Material)) {
                for (isDark in listOf(false, true)) {
                    composeRule.runOnIdle { style.value = uiStyle; dark.value = isDark }
                    composeRule.waitUntil(30_000L) { loadedImages.containsAll(covers) }
                    composeRule.waitForIdle()
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.waitForIdle()
                    composeRule.onNodeWithText(firstSongs.first().name).assertIsDisplayed()
                    captureBrowserBitmap("navidrome-browser-${uiStyle.name.lowercase()}-${if (isDark) "dark" else "light"}.png")
                }
            }
            val albumSongs = repository.albumSongs(profileId, firstAlbum.id)
            assertEquals(firstAlbum.songCount, albumSongs.size)
            instrumentation.runOnMainSync { selectedAlbum.value = firstAlbum }
            composeRule.waitUntil(30_000L) {
                composeRule.onAllNodesWithText(albumSongs.first().name).fetchSemanticsNodes().isNotEmpty()
            }
            firstAlbum.coverUrl?.let { cover ->
                composeRule.waitUntil(30_000L) { cover in loadedImages }
            }
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText(firstAlbum.name)[0].assertIsDisplayed()
            captureBrowserBitmap("navidrome-browser-album-material-dark.png")
        } finally {
            scenario.onActivity { it.setContent {} }
            composeRule.waitForIdle()
            Coil.setImageLoader(previousLoader)
            imageLoader.shutdown()
        }
    }

    private fun captureBrowserBitmap(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun playOffline(app: Application, song: SongItem, label: String) {
        val preferences = readPlaybackPreferenceSnapshotSync(app).copy(maxCacheSizeBytes = 0L,
            usbExclusivePlayback = false, preemptAudioFocus = false, keepLastPlaybackProgress = false)
        instrumentation.runOnMainSync {
            if (PlayerManager.isPlayerInitialized()) PlayerManager.release()
            PlayerManager.initializePreloaded(app, preferences, EmptyPlayer)
            PlayerManager.playPlaylist(listOf(song), 0)
        }
        await("$label Media3 READY", 30_000L) {
            PlayerManager.playerPlaybackStateFlow.value == Player.STATE_READY &&
                PlayerManager.isPlayingFlow.value
        }
        val start = PlayerManager.playbackPositionFlow.value
        await("$label playback position advances", 10_000L) {
            PlayerManager.playbackPositionFlow.value >= start + 750L
        }
        val actual = PlayerManager.currentMediaUrlFlow.value.orEmpty()
        assertTrue("offline playback must resolve a downloaded file",
            actual.startsWith("/") || Uri.parse(actual).scheme in setOf("file", "content"))
        playbackChecks += label
        instrumentation.runOnMainSync { PlayerManager.pause(forcePersist = false) }
    }

    private suspend fun await(label: String, timeoutMs: Long, condition: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            assertTrue("Timed out waiting for $label", SystemClock.elapsedRealtime() < deadline)
            delay(100L)
        }
    }

    private fun openLocal(reference: String): InputStream {
        val uri = Uri.parse(reference)
        return when (uri.scheme) {
            "content" -> checkNotNull(context.contentResolver.openInputStream(uri))
            "file" -> File(checkNotNull(uri.path)).inputStream()
            null -> File(reference).inputStream()
            else -> throw AssertionError("Expected a local asset reference")
        }
    }

    private fun storedEntry(downloaded: DownloadedSong): ManagedDownloadStorage.StoredEntry {
        val local = downloaded.toPlaybackSongItem()
        return ManagedDownloadStorage.StoredEntry(
            downloaded.localFileName ?: File(downloaded.filePath).name,
            downloaded.filePath, local.mediaUri ?: downloaded.filePath, local.localFilePath,
            downloaded.fileSize, downloaded.downloadTime)
    }

    private fun writeReport(status: String, errorType: String? = null) {
        report.writeText(JSONObject().put("status", status).put("phase", phase)
            .put("formats", completedFormats.joinToString(","))
            .put("offlinePlaybackChecks", playbackChecks.joinToString(","))
            .put("errorType", errorType).toString(2))
    }

    private class Fixture(val baseUrl: String, val username: String, val password: String)

    private companion object {
        val Formats = setOf("mp3", "flac", "wav", "m4a", "ogg")
        val PendingStatuses = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING_NETWORK)
        val EmptyPlayer = RestoredPlayerStateSnapshot(emptyList(), -1, null, Player.REPEAT_MODE_OFF,
            false, null, -1, 0L, false, 0, -1)
    }
}
