package moe.ouom.neriplayer.dlna

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.dlna.audio.LosslessSourceResolver
import moe.ouom.neriplayer.core.player.dlna.protocol.DidlLiteBuilder
import moe.ouom.neriplayer.core.player.dlna.session.DlnaPreferencesStore
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDeviceOptions
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaStreamMode
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DlnaAppIntegrationTest {

    @Test
    fun preferencesStore_migratesLegacyStrictDefaultToAdaptiveAndPreservesFutureToggles() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Simulate v1 installation where strictLosslessMode was persisted as true
        val rawPrefs = app.getSharedPreferences("neri_dlna_preferences", Context.MODE_PRIVATE)
        rawPrefs.edit()
            .clear()
            .putString(
                "device_options_json",
                """{"uuid:em7-legacy":{"preferOriginalFlac":true,"wavFallbackEnabled":true,"wavEmbedId3Enabled":true,"strictLosslessMode":true}}"""
            )
            .commit()

        val store = DlnaPreferencesStore(app)
        val migrated = store.loadAllDeviceOptions()["uuid:em7-legacy"]!!
        assertFalse("Legacy v1 strictLosslessMode=true should migrate to adaptive false", migrated.strictLosslessMode)
        assertTrue(migrated.wavEmbedId3Enabled)

        // User explicitly re-enables strictLosslessMode in v2 -> must be preserved across reloads
        store.saveDeviceOptions("uuid:em7-legacy", migrated.copy(strictLosslessMode = true))
        val reloadedAfterOptIn = DlnaPreferencesStore(app).loadAllDeviceOptions()["uuid:em7-legacy"]!!
        assertTrue(reloadedAfterOptIn.strictLosslessMode)

        val em7 = DlnaDevice(
            udn = "uuid:em7-test",
            friendlyName = "Shanling EM7",
            location = "http://192.168.1.50:49152/desc.xml",
            host = "192.168.1.50",
            manufacturer = "Shanling",
            modelName = "EM7"
        )
        val generic = DlnaDevice(
            udn = "uuid:generic-test",
            friendlyName = "Living Room Speaker",
            location = "http://192.168.1.60:49152/desc.xml",
            host = "192.168.1.60"
        )

        val em7Defaults = store.defaultOptionsForDevice(em7)
        val genericDefaults = store.defaultOptionsForDevice(generic)
        assertTrue(em7Defaults.wavEmbedId3Enabled)
        assertFalse(genericDefaults.wavEmbedId3Enabled)
        assertTrue(em7Defaults.preferOriginalFlac)
        assertFalse("Adaptive mode should be default for new devices", em7Defaults.strictLosslessMode)
        assertFalse(genericDefaults.strictLosslessMode)

        store.saveManualDevice(em7)
        assertEquals("Shanling EM7", store.loadManualDevices().first().friendlyName)
        store.removeManualDevice(em7.udn)
        assertTrue(store.loadManualDevices().isEmpty())
    }

    @Test
    fun losslessSourceResolver_supportsAdaptiveDirectForBilibiliYouTubeAndNeteaseWithCovers() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val zhConfig = Configuration(app.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val zhContext = app.createConfigurationContext(zhConfig)

        val tempDir = Files.createTempDirectory("dlna_app_int_").toFile()
        val pngCover = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()
        )
        val m4aPayload = byteArrayOf(
            0, 0, 0, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), '5'.code.toByte()
        ) + ByteArray(256) { (it and 0xFF).toByte() }
        val webmPayload = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()) +
            ByteArray(256) { ((it * 3) and 0xFF).toByte() }

        var serverSocket: ServerSocket? = null
        val running = AtomicBoolean(true)
        val pool = Executors.newCachedThreadPool()
        var biliRefererSeen = false

        try {
            serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            val port = serverSocket.localPort
            pool.execute {
                while (running.get()) {
                    val sock = try { serverSocket.accept() } catch (_: Exception) { break }
                    pool.execute {
                        sock.use { s ->
                            val input = BufferedInputStream(s.getInputStream())
                            val buf = ByteArrayOutputStream()
                            var matched = 0
                            while (true) {
                                val b = input.read()
                                if (b < 0) return@use
                                buf.write(b)
                                matched = when {
                                    matched == 0 && b == '\r'.code -> 1
                                    matched == 1 && b == '\n'.code -> 2
                                    matched == 2 && b == '\r'.code -> 3
                                    matched == 3 && b == '\n'.code -> 4
                                    b == '\r'.code -> 1
                                    else -> 0
                                }
                                if (matched == 4) break
                            }
                            val headerText = buf.toString(Charsets.UTF_8.name())
                            val target = headerText.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1).orEmpty()
                            val cleanTarget = target.substringBefore('?')
                            if (target.contains("bili") && headerText.contains("Referer: https://www.bilibili.com", ignoreCase = true)) {
                                biliRefererSeen = true
                            }
                            val (mime, body) = when {
                                cleanTarget.endsWith(".m4s") -> "audio/mp4" to m4aPayload
                                cleanTarget.endsWith(".webm") -> "audio/webm" to webmPayload
                                cleanTarget.endsWith(".png") -> "image/png" to pngCover
                                else -> "application/octet-stream" to ByteArray(0)
                            }
                            val resp = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            s.getOutputStream().write(resp.toByteArray(Charsets.US_ASCII))
                            s.getOutputStream().write(body)
                            s.getOutputStream().flush()
                        }
                    }
                }
            }

            val em7 = DlnaDevice(
                udn = "uuid:em7-adaptive",
                friendlyName = "Shanling EM7",
                location = "http://192.168.1.50:49152/desc.xml",
                host = "192.168.1.50",
                supportsFlac = true,
                supportsWav = true
            )
            val resolver = LosslessSourceResolver(
                context = zhContext,
                okHttpClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(),
                biliCookieHeaderProvider = { "SESSDATA=test_cookie" },
                getLocalizedString = { resId, args ->
                    if (args.isEmpty()) zhContext.getString(resId) else zhContext.getString(resId, *args)
                }
            )

            // 1. Bilibili M4A stream (adaptive default -> streams original M4A directly with cover, NEVER converts to WAV)
            val biliSong = SongItem(
                id = 1001L,
                name = "Bili Audio Track",
                artist = "UP 主",
                album = "Bilibili|BV1xx411c7mD",
                albumId = 0L,
                durationMs = 150_000L,
                coverUrl = "http://127.0.0.1:$port/cover.png",
                channelId = "bilibili"
            )
            val biliUrl = SongUrlResult.Success(
                url = "http://127.0.0.1:$port/bili_stream.m4s?bilivideo",
                mimeType = "audio/mp4",
                audioInfo = PlaybackAudioInfo(
                    source = PlaybackAudioSource.BILIBILI,
                    qualityKey = "high",
                    qualityLabel = "高",
                    codecLabel = "AAC",
                    mimeType = "audio/mp4",
                    bitrateKbps = 192
                )
            )
            val biliReady = resolver.prepareForCast(
                song = biliSong,
                generationId = 10L,
                urlResult = biliUrl,
                requestedLosslessQuality = true,
                device = em7,
                options = DlnaDeviceOptions(wavEmbedId3Enabled = true, strictLosslessMode = false),
                isCancelled = { false }
            ) as LosslessSourceResolver.ResolutionOutcome.Ready

            assertTrue(biliRefererSeen)
            assertEquals(DlnaStreamMode.ADAPTIVE_DIRECT, biliReady.qualityReport.deliveryMode)
            assertEquals("audio/mp4", biliReady.snapshot.contentType)
            assertEquals("m4a", biliReady.streamExtension)
            assertEquals("哔哩哔哩", biliReady.snapshot.album)
            assertArrayEquals(pngCover, biliReady.coverBytes)
            val biliDidl = DidlLiteBuilder.buildDidlLite(
                biliReady.snapshot,
                "http://192.168.1.10:5000/stream.m4a",
                "http://192.168.1.10:5000/art.png"
            )
            assertTrue(biliDidl.contains("<dc:title>Bili Audio Track</dc:title>"))
            assertTrue(biliDidl.contains("<upnp:albumArtURI>http://192.168.1.10:5000/art.png</upnp:albumArtURI>"))

            // 2. YouTube Music WebM/Opus stream (adaptive default -> streams original WebM directly)
            val ytSong = SongItem(
                id = 1002L,
                name = "YT Music Track",
                artist = "YT Artist",
                album = "YT Album",
                albumId = 0L,
                durationMs = 210_000L,
                coverUrl = "http://127.0.0.1:$port/yt_cover.png",
                channelId = "youtube_music",
                mediaUri = "https://music.youtube.com/watch?v=dQw4w9WgXcQ"
            )
            val ytUrl = SongUrlResult.Success(
                url = "http://127.0.0.1:$port/yt_audio.webm",
                mimeType = "audio/webm",
                audioInfo = PlaybackAudioInfo(
                    source = PlaybackAudioSource.YOUTUBE_MUSIC,
                    qualityKey = "very_high",
                    qualityLabel = "极高",
                    codecLabel = "OPUS",
                    mimeType = "audio/webm",
                    bitrateKbps = 160,
                    sampleRateHz = 48_000
                )
            )
            val ytReady = resolver.prepareForCast(
                song = ytSong,
                generationId = 11L,
                urlResult = ytUrl,
                requestedLosslessQuality = true,
                device = em7,
                options = DlnaDeviceOptions(strictLosslessMode = false),
                isCancelled = { false }
            ) as LosslessSourceResolver.ResolutionOutcome.Ready

            assertEquals(DlnaStreamMode.ADAPTIVE_DIRECT, ytReady.qualityReport.deliveryMode)
            assertEquals("audio/webm", ytReady.snapshot.contentType)
            assertEquals("webm", ytReady.streamExtension)
            assertEquals(0, ytReady.snapshot.bitDepth)
            assertNotNull(ytReady.coverBytes)

            // 3. NetEase MP3: adaptive allows direct MP3 pass-through; optional strict mode blocks with localized ZH message
            val mp3File = File(tempDir, "after_all.mp3").apply {
                writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0) + ByteArray(256))
            }
            val neSong = SongItem(
                id = 123456L,
                name = "After All",
                artist = "Elton John / Charlie Puth",
                album = "The Lockdown Sessions",
                albumId = 99L,
                durationMs = 208_000L,
                coverUrl = "http://127.0.0.1:$port/ne_cover.png"
            )
            val neUrl = SongUrlResult.Success(
                url = mp3File.absolutePath,
                mimeType = "audio/mpeg",
                audioInfo = PlaybackAudioInfo(
                    source = PlaybackAudioSource.NETEASE,
                    qualityKey = "exhigh",
                    qualityLabel = "极高",
                    codecLabel = "MP3",
                    mimeType = "audio/mpeg",
                    bitrateKbps = 320
                )
            )
            val neAdaptive = resolver.prepareForCast(
                song = neSong,
                generationId = 12L,
                urlResult = neUrl,
                requestedLosslessQuality = true,
                device = em7,
                options = DlnaDeviceOptions(), // default adaptive!
                isCancelled = { false }
            ) as LosslessSourceResolver.ResolutionOutcome.Ready
            assertEquals(DlnaStreamMode.ADAPTIVE_DIRECT, neAdaptive.qualityReport.deliveryMode)
            assertEquals("audio/mpeg", neAdaptive.snapshot.contentType)
            assertEquals("The Lockdown Sessions", neAdaptive.snapshot.album)
            val taggedMp3File = (neAdaptive.streamContent as moe.ouom.neriplayer.core.player.dlna.server.DlnaMediaServer.StreamContent.FileContent).file
            val taggedHeader = taggedMp3File.readBytes()
            assertEquals('I'.code.toByte(), taggedHeader[0])
            assertEquals('D'.code.toByte(), taggedHeader[1])
            assertEquals('3'.code.toByte(), taggedHeader[2])
            assertTrue(taggedMp3File.length() > mp3File.length())
            assertTrue(neAdaptive.qualityReport.degraded)
            assertTrue(neAdaptive.qualityReport.degradationReason.orEmpty().contains("MP3 · 极高 · 320 kbps"))
            assertArrayEquals(pngCover, neAdaptive.coverBytes)

            val neStrictBlocked = resolver.prepareForCast(
                song = neSong,
                generationId = 13L,
                urlResult = neUrl,
                requestedLosslessQuality = true,
                device = em7,
                options = DlnaDeviceOptions(strictLosslessMode = true),
                isCancelled = { false }
            ) as LosslessSourceResolver.ResolutionOutcome.Blocked
            assertTrue(neStrictBlocked.qualityReport.strictLosslessBlocked)
            assertTrue(neStrictBlocked.reason.contains("严格无损模式"))
        } finally {
            running.set(false)
            runCatching { serverSocket?.close() }
            pool.shutdownNow()
            tempDir.deleteRecursively()
        }
    }
}
