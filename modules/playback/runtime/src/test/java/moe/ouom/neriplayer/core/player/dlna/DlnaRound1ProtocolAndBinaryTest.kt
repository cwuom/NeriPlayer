package moe.ouom.neriplayer.core.player.dlna

import android.media.AudioFormat
import moe.ouom.neriplayer.core.player.dlna.protocol.DidlLiteBuilder
import moe.ouom.neriplayer.core.player.dlna.protocol.DlnaProtocolInfo
import moe.ouom.neriplayer.core.player.dlna.protocol.FlacHeaderInspector
import moe.ouom.neriplayer.core.player.dlna.protocol.UpnpXmlParser
import moe.ouom.neriplayer.core.player.dlna.protocol.WavId3TagBuilder
import moe.ouom.neriplayer.core.player.dlna.audio.DlnaTrackMetadataNormalizer
import moe.ouom.neriplayer.core.player.dlna.audio.LosslessSourcePolicy
import moe.ouom.neriplayer.core.player.dlna.audio.LosslessSourceResolver
import moe.ouom.neriplayer.core.player.dlna.audio.PcmSampleConverter
import moe.ouom.neriplayer.core.player.dlna.protocol.FlacVorbisCommentWriter
import moe.ouom.neriplayer.core.player.dlna.protocol.Mp3Id3TagWriter
import moe.ouom.neriplayer.core.player.dlna.protocol.Mp4MetadataWriter
import moe.ouom.neriplayer.core.player.media.PlaybackMediaItemFactory
import moe.ouom.neriplayer.core.player.url.buildNeteaseQualityCandidates
import moe.ouom.neriplayer.core.player.url.sanitizeNeteasePlaybackLogSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDeviceOptions
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaPlaybackSnapshot
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaStreamMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Round 1 Simulation Tests:
 * - WAV RIFF "id3 " chunk builder (ID3v2.3 UTF-16LE BOM, Unicode & emoji, rejection guards)
 * - Dynamic WAV layout offsets & RIFF32 bounds
 * - Bit-exact PCM sample conversion (16/24/32-bit & IEEE float -> 24-bit without resampling/gain)
 * - FLAC STREAMINFO & WAV & MP3 & ALAC binary header inspection
 * - Strict lossless policy & no-MP3/no-upsampling guards
 * - DIDL-Lite XML generation & hardened UPnP SOAP/DeviceDescription parsing
 */
class DlnaRound1ProtocolAndBinaryTest {

    @Test
    fun wavId3Chunk_matchesExactGoldenAsciiAndUnicodeRoundtrip() {
        assertEquals(0, WavId3TagBuilder.buildId3Chunk("", "", "").size)

        val goldenAscii = WavId3TagBuilder.buildId3Chunk("A", "B", "C")
        val expectedGolden = byteArrayOf(
            'i'.code.toByte(), 'd'.code.toByte(), '3'.code.toByte(), ' '.code.toByte(),
            55, 0, 0, 0,
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            0, 0, 0, 45,
            'T'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), '2'.code.toByte(),
            0, 0, 0, 5, 0, 0, 1, 0xFF.toByte(), 0xFE.toByte(), 'A'.code.toByte(), 0,
            'T'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte(),
            0, 0, 0, 5, 0, 0, 1, 0xFF.toByte(), 0xFE.toByte(), 'B'.code.toByte(), 0,
            'T'.code.toByte(), 'A'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(),
            0, 0, 0, 5, 0, 0, 1, 0xFF.toByte(), 0xFE.toByte(), 'C'.code.toByte(), 0, 0
        )
        assertArrayEquals(expectedGolden, goldenAscii)
        assertTrue(WavId3TagBuilder.isValidWavId3Chunk(goldenAscii))

        val em7Fixture = WavId3TagBuilder.buildId3Chunk("EM7 ID3 TITLE", "EM7 ID3 ARTIST", "EM7 ID3 ALBUM")
        assertEquals(138, em7Fixture.size)
        assertTrue(WavId3TagBuilder.isValidWavId3Chunk(em7Fixture))

        val unicodeTitle = "标题 <&> \uD83C\uDFB5"
        val unicodeArtist = "测试歌手"
        val unicodeAlbum = "测试专辑"
        val unicodeChunk = WavId3TagBuilder.buildId3Chunk(unicodeTitle, unicodeArtist, unicodeAlbum)
        assertTrue(WavId3TagBuilder.isValidWavId3Chunk(unicodeChunk))
        val decoded = decodeId3Frames(unicodeChunk)
        assertEquals(unicodeTitle, decoded["TIT2"])
        assertEquals(unicodeArtist, decoded["TPE1"])
        assertEquals(unicodeAlbum, decoded["TALB"])

        // Reject malformed inputs: embedded NUL, lone surrogates, truncations, corruptions
        assertEquals(0, WavId3TagBuilder.buildId3Chunk("A\u0000B", "", "").size)
        assertEquals(0, WavId3TagBuilder.buildId3Chunk("\uD800", "", "").size)
        assertEquals(0, WavId3TagBuilder.buildId3Chunk("", "\uDC00", "").size)
        for (len in 0 until goldenAscii.size) {
            assertFalse(WavId3TagBuilder.isValidWavId3Chunk(goldenAscii.copyOf(len)))
        }
    }

    @Test
    fun wavLayout_computesDynamicOffsetsAndRejectsOverflow() {
        val pcmBytes = ByteArray(1024) { (it and 0xFF).toByte() }
        val id3 = WavId3TagBuilder.buildId3Chunk("EM7 ID3 TITLE", "EM7 ID3 ARTIST", "EM7 ID3 ALBUM")
        val layout = WavId3TagBuilder.buildWavLayout(
            sampleRateHz = 96_000,
            bitsPerSample = 24,
            channels = 2,
            pcmDataBytes = pcmBytes.size.toLong(),
            id3Chunk = id3
        )
        assertEquals(44L + 138L, layout.pcmStartOffset)
        assertEquals(182L + 1024L, layout.totalBytes)
        assertEquals(182, layout.headerBytes.size)
        assertEquals(138, layout.id3ChunkSize)

        // Verify RIFF size and data size in header
        val riffSize = readLe32(layout.headerBytes, 4)
        assertEquals(layout.totalBytes - 8L, riffSize)
        val dataSize = readLe32(layout.headerBytes, layout.headerBytes.size - 4)
        assertEquals(pcmBytes.size.toLong(), dataSize)

        var overflowThrown = false
        try {
            WavId3TagBuilder.buildWavLayout(
                sampleRateHz = 192_000,
                bitsPerSample = 24,
                channels = 2,
                pcmDataBytes = 0xFFFF_FFFFL,
                id3Chunk = id3
            )
        } catch (_: IllegalArgumentException) {
            overflowThrown = true
        }
        assertTrue(overflowThrown)
    }

    @Test
    fun pcmSampleConverter_preserves16And24BitValuesWithoutGainOrResampling() {
        // 16-bit pass-through
        val buf16 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf16.putShort(0x1234.toShort()).putShort((-12345).toShort()).flip()
        val out16 = PcmSampleConverter.convertToTargetLePcm(buf16, AudioFormat.ENCODING_PCM_16BIT, 16)
        assertEquals(4, out16.size)
        assertEquals(0x34.toByte(), out16[0])
        assertEquals(0x12.toByte(), out16[1])

        // 24-bit packed pass-through
        val buf24 = ByteBuffer.wrap(byteArrayOf(0x11, 0x22, 0x33, 0xAA.toByte(), 0xBB.toByte(), 0x7F))
        val out24 = PcmSampleConverter.convertToTargetLePcm(buf24, AudioFormat.ENCODING_PCM_24BIT_PACKED, 24)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0xAA.toByte(), 0xBB.toByte(), 0x7F), out24)

        // Float32 exact 24-bit integer roundtrip
        val expected24 = 0x345678
        val fVal = (expected24 / 8388608.0).toFloat()
        val bufFloat = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(fVal)
        bufFloat.flip()
        val recovered24 = PcmSampleConverter.convertToTargetLePcm(bufFloat, AudioFormat.ENCODING_PCM_FLOAT, 24)
        val reconstructed = (recovered24[0].toInt() and 0xFF) or
            ((recovered24[1].toInt() and 0xFF) shl 8) or
            ((recovered24[2].toInt() and 0xFF) shl 16)
        assertEquals(expected24, reconstructed)
    }

    @Test
    fun flacAndWavHeaderInspector_extractsExactSpecsAndDetectsLossyContainers() {
        val flac96k24b = buildSyntheticFlacBytes(sampleRate = 96_000, channels = 2, bitDepth = 24, totalSamples = 480_000L)
        val inspectedFlac = FlacHeaderInspector.inspectBytes(flac96k24b)
        assertEquals(FlacHeaderInspector.ContainerFormat.FLAC, inspectedFlac.format)
        assertTrue(inspectedFlac.isLossless)
        assertEquals(96_000, inspectedFlac.sampleRateHz)
        assertEquals(24, inspectedFlac.bitDepth)
        assertEquals(2, inspectedFlac.channelCount)
        assertEquals(480_000L, inspectedFlac.totalSamples)

        val wavHeader = WavId3TagBuilder.buildWavLayout(192_000, 24, 2, 48L).headerBytes + ByteArray(48)
        val inspectedWav = FlacHeaderInspector.inspectBytes(wavHeader)
        assertEquals(FlacHeaderInspector.ContainerFormat.WAV, inspectedWav.format)
        assertTrue(inspectedWav.isLossless)
        assertEquals(192_000, inspectedWav.sampleRateHz)
        assertEquals(24, inspectedWav.bitDepth)
        assertEquals(44L, inspectedWav.wavDataOffset)
        assertEquals(48L, inspectedWav.wavDataSize)

        val mp3Header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0)
        val inspectedMp3 = FlacHeaderInspector.inspectBytes(mp3Header)
        assertEquals(FlacHeaderInspector.ContainerFormat.MP3, inspectedMp3.format)
        assertFalse(inspectedMp3.isLossless)

        val mp4Ftyp = byteArrayOf(
            0, 0, 0, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte()
        ) + ByteArray(32)
        val inspectedMp4 = FlacHeaderInspector.inspectBytes(mp4Ftyp)
        assertEquals(FlacHeaderInspector.ContainerFormat.AAC_OR_MP4, inspectedMp4.format)
        assertEquals("audio/mp4", inspectedMp4.mimeType)
        assertFalse(inspectedMp4.isLossless)

        val webmHeader = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte())
        val inspectedWebm = FlacHeaderInspector.inspectBytes(webmHeader)
        assertEquals(FlacHeaderInspector.ContainerFormat.WEBM, inspectedWebm.format)
        assertEquals("audio/webm", inspectedWebm.mimeType)
        assertFalse(inspectedWebm.isLossless)

        val adtsAac = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x80.toByte())
        val inspectedAdts = FlacHeaderInspector.inspectBytes(adtsAac)
        assertEquals(FlacHeaderInspector.ContainerFormat.AAC_ADTS, inspectedAdts.format)
        assertEquals("audio/aac", inspectedAdts.mimeType)
        assertFalse(inspectedAdts.isLossless)
    }

    @Test
    fun losslessSourcePolicy_enforcesStrictLosslessAndDeviceModes() {
        val em7 = DlnaDevice(
            udn = "uuid:shanling-em7",
            friendlyName = "Shanling EM7",
            location = "http://192.168.1.50:49152/desc.xml",
            host = "192.168.1.50",
            supportsFlac = true,
            supportsWav = true
        )
        val flacInfo = FlacHeaderInspector.InspectedAudio(
            format = FlacHeaderInspector.ContainerFormat.FLAC,
            mimeType = "audio/x-flac",
            isLossless = true,
            sampleRateHz = 96_000,
            bitDepth = 24,
            channelCount = 2
        )
        val mp3Info = FlacHeaderInspector.InspectedAudio(
            format = FlacHeaderInspector.ContainerFormat.MP3,
            mimeType = "audio/mpeg",
            isLossless = false
        )

        // 1. Default strict mode + preferOriginalFlac -> ORIGINAL_FLAC
        val d1 = LosslessSourcePolicy.evaluate(
            inspected = flacInfo,
            audioInfo = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, qualityKey = "hires", qualityLabel = "Hi-Res"),
            isPreviewClip = false,
            requestedLosslessQuality = true,
            device = em7,
            options = DlnaDeviceOptions(preferOriginalFlac = true, wavFallbackEnabled = true, wavEmbedId3Enabled = true, strictLosslessMode = true)
        ) as LosslessSourcePolicy.DeliveryDecision.Deliver
        assertEquals(DlnaStreamMode.ORIGINAL_FLAC, d1.mode)
        assertEquals(96_000, d1.sampleRateHz)
        assertEquals(24, d1.bitDepth)
        assertFalse(d1.degraded)

        // 2. Per-device WAV+ID3 mode when preferOriginalFlac is disabled
        val d2 = LosslessSourcePolicy.evaluate(
            inspected = flacInfo,
            audioInfo = PlaybackAudioInfo(PlaybackAudioSource.LOCAL),
            isPreviewClip = false,
            requestedLosslessQuality = true,
            device = em7,
            options = DlnaDeviceOptions(preferOriginalFlac = false, wavFallbackEnabled = true, wavEmbedId3Enabled = true, strictLosslessMode = true)
        ) as LosslessSourcePolicy.DeliveryDecision.Deliver
        assertEquals(DlnaStreamMode.LOSSLESS_WAV_ID3, d2.mode)
        assertEquals(96_000, d2.sampleRateHz)
        assertEquals(24, d2.bitDepth)

        // 3. Default options use Adaptive Direct Streaming (strictLosslessMode = false)
        assertFalse(DlnaDeviceOptions().strictLosslessMode)
        val adaptiveMp3 = LosslessSourcePolicy.evaluate(
            inspected = mp3Info,
            audioInfo = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, qualityKey = "exhigh", qualityLabel = "极高", bitrateKbps = 320),
            isPreviewClip = false,
            requestedLosslessQuality = true,
            device = em7,
            options = DlnaDeviceOptions() // default adaptive!
        ) as LosslessSourcePolicy.DeliveryDecision.Deliver
        assertEquals(DlnaStreamMode.ADAPTIVE_DIRECT, adaptiveMp3.mode)
        assertEquals("audio/mpeg", adaptiveMp3.mimeType)
        assertEquals(0, adaptiveMp3.bitDepth)

        // 4. Optional strict lossless blocks MP3 and preview clips when explicitly enabled
        val blockedMp3 = LosslessSourcePolicy.evaluate(
            inspected = mp3Info,
            audioInfo = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, qualityKey = "standard"),
            isPreviewClip = false,
            requestedLosslessQuality = true,
            device = em7,
            options = DlnaDeviceOptions(strictLosslessMode = true)
        )
        assertTrue(blockedMp3 is LosslessSourcePolicy.DeliveryDecision.Blocked)

        val blockedPreview = LosslessSourcePolicy.evaluate(
            inspected = flacInfo,
            audioInfo = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, qualityKey = "lossless"),
            isPreviewClip = true,
            requestedLosslessQuality = true,
            device = em7,
            options = DlnaDeviceOptions(strictLosslessMode = true)
        )
        assertTrue(blockedPreview is LosslessSourcePolicy.DeliveryDecision.Blocked)

        // Adaptive mode allows preview clip direct stream if user chooses not to enable strict mode
        val adaptivePreview = LosslessSourcePolicy.evaluate(
            inspected = flacInfo,
            audioInfo = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, qualityKey = "lossless"),
            isPreviewClip = true,
            requestedLosslessQuality = true,
            device = em7,
            options = DlnaDeviceOptions(strictLosslessMode = false)
        )
        assertTrue(adaptivePreview is LosslessSourcePolicy.DeliveryDecision.Deliver)
    }

    @Test
    fun didlAndUpnpXmlParser_roundtripWithNamespacePrefixesAndXxeHardening() {
        val snapshot = DlnaPlaybackSnapshot(
            generationId = 7L,
            songKey = "netease|12345",
            title = "晴天 & <Live> \uD83C\uDFB5",
            artist = "周杰伦",
            album = "叶惠美 \"Deluxe\"",
            trackNumber = 3,
            durationMs = 269_000L,
            streamPath = "/dlna/stream/abc.flac",
            coverPath = "/dlna/artwork/def.jpg",
            contentType = "audio/x-flac",
            protocolInfo = DlnaProtocolInfo.protocolInfoFor(DlnaStreamMode.ORIGINAL_FLAC),
            totalBytes = 32_000_000L,
            sampleRateHz = 96_000,
            bitDepth = 24,
            channelCount = 2,
            streamMode = DlnaStreamMode.ORIGINAL_FLAC
        )
        val didl = DidlLiteBuilder.buildDidlLite(
            snapshot = snapshot,
            streamUrl = "http://192.168.1.20:45000/dlna/stream/abc.flac?t=1&g=7",
            coverUrl = "http://192.168.1.20:45000/dlna/artwork/def.jpg"
        )
        assertTrue(didl.contains("<upnp:artist role=\"Performer\">周杰伦</upnp:artist>"))
        assertTrue(didl.contains("sampleFrequency=\"96000\""))
        assertTrue(didl.contains("bitsPerSample=\"24\""))
        assertTrue(didl.contains("size=\"32000000\""))

        // Roundtrip DIDL nested inside SOAP GetPositionInfoResponse with namespace prefix
        val soap = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <u:TrackMetaData>${DidlLiteBuilder.escapeXml(didl)}</u:TrackMetaData>
                  <u:RelTime>0:01:23</u:RelTime>
                </u:GetPositionInfoResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val parsed = UpnpXmlParser.parseSoapResponse(soap, "GetPositionInfo") as UpnpXmlParser.SoapParseResult.Success
        assertEquals(didl, parsed.values["TrackMetaData"])
        assertEquals(83_000L, UpnpXmlParser.parseUpnpTimeToMs(parsed.values["RelTime"].orEmpty()))

        // Reject XXE / DOCTYPE
        val xxe = """<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><root><UDN>&xxe;</UDN></root>"""
        assertEquals(null, UpnpXmlParser.parseDeviceDescription(xxe, "http://192.168.1.50/desc.xml"))
    }

    @Test
    fun standardQualityResolutionAndUrlRedaction_preservesAppSettingsAndRedactsSignedTokens() {
        assertEquals(
            listOf("exhigh", "higher", "standard"),
            buildNeteaseQualityCandidates("exhigh")
        )
        assertEquals(
            listOf("lossless", "exhigh", "higher", "standard"),
            buildNeteaseQualityCandidates("lossless")
        )

        // YouTube DLNA preferM4a hint uses a distinct cache key from WebM/Opus while keeping quality setting
        val webmKey = PlaybackMediaItemFactory.youtubeCacheKey("dQw4w9WgXcQ", "high", preferM4a = false)
        val m4aKey = PlaybackMediaItemFactory.youtubeCacheKey("dQw4w9WgXcQ", "high", preferM4a = true)
        assertFalse(webmKey == m4aKey)

        val signedSuccess = NeteasePlaybackResponseParser.PlaybackResult.Success(
            url = "https://m801.music.126.net/20261119/signed_secret_token/file.flac?authSecret=xyz123",
            type = "flac",
            level = "lossless",
            bitrateKbps = 980
        )
        val sanitized = sanitizeNeteasePlaybackLogSummary(123456L, "lossless", signedSuccess)
        assertFalse(sanitized.contains("signed_secret_token"))
        assertFalse(sanitized.contains("authSecret=xyz123"))
        assertTrue(sanitized.contains("https://m801.music.126.net/<redacted>"))
        assertTrue(sanitized.contains("resolvedLevel=lossless"))

        // Staging cache key stability across CDN edge rotation + .pcm eviction bound
        val biliAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.BILIBILI,
            qualityKey = "high",
            mimeType = "audio/mp4",
            bitrateKbps = 192
        )
        val stableKeyA = LosslessSourceResolver.buildStableRemoteStagingKey(
            songKey = "bilibili|BV1xx",
            url = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/a.m4s?os=cos&uipk=1&deadline=111",
            audioInfo = biliAudioInfo
        )
        val stableKeyB = LosslessSourceResolver.buildStableRemoteStagingKey(
            songKey = "bilibili|BV1xx",
            url = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/b.m4s?os=akam&token=222",
            audioInfo = biliAudioInfo
        )
        assertEquals(stableKeyA, stableKeyB)

        val evictDir = java.nio.file.Files.createTempDirectory("dlna_staging_evict_").toFile()
        try {
            val oldPcm = java.io.File(evictDir, "old.pcm").apply {
                writeBytes(ByteArray(600))
                setLastModified(1_000L)
            }
            val oldAudio = java.io.File(evictDir, "mid.audio").apply {
                writeBytes(ByteArray(600))
                setLastModified(2_000L)
            }
            val freshPcm = java.io.File(evictDir, "fresh.pcm").apply {
                writeBytes(ByteArray(600))
                setLastModified(3_000L)
            }
            LosslessSourceResolver.evictStagingDirectory(evictDir, maxBytes = 1000L)
            assertFalse("Oldest .pcm should be evicted", oldPcm.exists())
            assertFalse("Second oldest file should be evicted to reach 75% target", oldAudio.exists())
            assertTrue("Newest .pcm should remain", freshPcm.exists())
        } finally {
            evictDir.deleteRecursively()
        }
    }

    @Test
    fun containerTagWritersAndNormalizer_embedMetadataWhilePreservingAudioBytes() {
        val tempDir = java.nio.file.Files.createTempDirectory("dlna_tags_").toFile()
        try {
            // 1. Metadata normalizer strips internal source tags
            val biliSong = SongItem(
                id = 1L,
                name = "测试视频",
                artist = "UP主",
                album = "Bilibili|12345|BV1xx411c7mD",
                albumId = 0L,
                durationMs = 1000L,
                coverUrl = null,
                channelId = "bilibili"
            )
            val normBili = DlnaTrackMetadataNormalizer.normalize(biliSong, PlaybackAudioSource.BILIBILI, biliFallbackAlbum = "哔哩哔哩")
            assertEquals("测试视频", normBili.title)
            assertEquals("UP主", normBili.artist)
            assertEquals("哔哩哔哩", normBili.album)

            val neSong = SongItem(
                id = 2L,
                name = "After All",
                artist = "Elton John / Charlie Puth",
                album = "NeteaseThe Lockdown Sessions",
                albumId = 2L,
                durationMs = 208_000L,
                coverUrl = null
            )
            val normNe = DlnaTrackMetadataNormalizer.normalize(neSong, PlaybackAudioSource.NETEASE)
            assertEquals("The Lockdown Sessions", normNe.album)

            // 2. FLAC Vorbis Comment injection keeps audio frames 100% byte-identical
            val rawFlac = buildSyntheticFlacBytes(96_000, 2, 24, 480_000L, payloadBytes = 512)
            val originalAudioFrames = rawFlac.copyOfRange(42, rawFlac.size)
            val srcFlac = java.io.File(tempDir, "in.flac").apply { writeBytes(rawFlac) }
            val outFlac = java.io.File(tempDir, "out.flac")
            assertTrue(FlacVorbisCommentWriter.writeTags(srcFlac, outFlac, "After All", "Elton John", "Lockdown"))
            val taggedBytes = outFlac.readBytes()
            val reinspected = FlacHeaderInspector.inspectBytes(taggedBytes)
            assertEquals(96_000, reinspected.sampleRateHz)
            assertEquals(24, reinspected.bitDepth)
            assertTrue(String(taggedBytes, Charsets.UTF_8).contains("ARTIST=Elton John"))
            assertTrue(String(taggedBytes, Charsets.UTF_8).contains("ALBUM=Lockdown"))
            // Verify trailing 512 audio frame bytes are completely untouched
            assertArrayEquals(originalAudioFrames, taggedBytes.copyOfRange(taggedBytes.size - 512, taggedBytes.size))

            // 3. MP3 ID3v2.3 UTF-16LE BOM injection keeps MPEG frames untouched
            val mpegFrames = ByteArray(256) { (0xAA).toByte() }
            val srcMp3 = java.io.File(tempDir, "in.mp3").apply {
                writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0) + mpegFrames)
            }
            val outMp3 = java.io.File(tempDir, "out.mp3")
            assertTrue(Mp3Id3TagWriter.writeTags(srcMp3, outMp3, "After All", "Elton John", "Lockdown"))
            val outMp3Bytes = outMp3.readBytes()
            val newId3Len = Mp3Id3TagWriter.detectExistingId3v2Length(outMp3).toInt()
            assertTrue(newId3Len > 20)
            assertArrayEquals(mpegFrames, outMp3Bytes.copyOfRange(newId3Len, outMp3Bytes.size))

            // 4. MP4 faststart ilst injection keeps mdat payload untouched and updates stco chunk offset
            val mdatContent = ByteArray(128) { (it + 1).toByte() }
            val srcMp4 = java.io.File(tempDir, "in.m4a").apply {
                writeBytes(buildSyntheticMp4ForTest(mdatContent))
            }
            val outMp4 = java.io.File(tempDir, "out.m4a")
            assertTrue(Mp4MetadataWriter.writeTags(srcMp4, outMp4, "B站歌曲", "UP主", "哔哩哔哩"))
            val outMp4Bytes = outMp4.readBytes()
            val outMp4Utf8 = String(outMp4Bytes, Charsets.UTF_8)
            assertTrue(outMp4Utf8.contains("B站歌曲"))
            assertTrue(outMp4Utf8.contains("UP主"))
            assertTrue(outMp4Utf8.contains("哔哩哔哩"))
            // mdat must end with exact untouched mdatContent
            assertArrayEquals(mdatContent, outMp4Bytes.copyOfRange(outMp4Bytes.size - mdatContent.size, outMp4Bytes.size))
            // Verify stco offset points directly to start of mdat data
            val mdatTagIndex = indexOfAscii(outMp4Bytes, "mdat")
            assertTrue(mdatTagIndex > 0)
            val expectedChunkOffset = mdatTagIndex + 4
            val stcoTagIndex = indexOfAscii(outMp4Bytes, "stco")
            assertTrue(stcoTagIndex in 1 until mdatTagIndex)
            val actualChunkOffset = ((outMp4Bytes[stcoTagIndex + 12].toInt() and 0xFF) shl 24) or
                ((outMp4Bytes[stcoTagIndex + 13].toInt() and 0xFF) shl 16) or
                ((outMp4Bytes[stcoTagIndex + 14].toInt() and 0xFF) shl 8) or
                (outMp4Bytes[stcoTagIndex + 15].toInt() and 0xFF)
            assertEquals(expectedChunkOffset, actualChunkOffset)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun dlnaStateMachine_autoAdvancesOnEndPausedOrStoppedButNotMidSongPause() {
        val midSongPause = moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.evaluatePoll(
            moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.PollObservation(
                previousState = moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState.PLAYING,
                currentState = moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState.PAUSED_PLAYBACK,
                positionMs = 62_000L,
                durationMs = 180_000L,
                userPausedOrStopped = false,
                generationId = 1L,
                activeGenerationId = 1L,
                hasStartedPlayingInGeneration = true,
                consecutiveStoppedPolls = 0,
                lastPlayingPositionMs = 62_000L,
                consecutiveEndPausedPolls = 3
            )
        )
        assertTrue(midSongPause is moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.PollDecision.SyncPlayback)
        assertFalse((midSongPause as moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.PollDecision.SyncPlayback).isPlaying)

        val firstEndPause = moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.evaluatePoll(
            moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.PollObservation(
                previousState = moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState.PLAYING,
                currentState = moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState.PAUSED_PLAYBACK,
                positionMs = 179_500L,
                durationMs = 180_000L,
                userPausedOrStopped = false,
                generationId = 1L,
                activeGenerationId = 1L,
                hasStartedPlayingInGeneration = true,
                consecutiveStoppedPolls = 0,
                lastPlayingPositionMs = 179_500L,
                consecutiveEndPausedPolls = 1
            )
        )
        assertTrue(firstEndPause is moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.PollDecision.KeepPolling)

        val confirmedEndPauseResetToZero = moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.evaluatePoll(
            moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.PollObservation(
                previousState = moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState.PAUSED_PLAYBACK,
                currentState = moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState.PAUSED_PLAYBACK,
                positionMs = 0L,
                durationMs = 180_000L,
                userPausedOrStopped = false,
                generationId = 1L,
                activeGenerationId = 1L,
                hasStartedPlayingInGeneration = true,
                consecutiveStoppedPolls = 0,
                lastPlayingPositionMs = 178_000L,
                consecutiveEndPausedPolls = 2
            )
        )
        assertEquals(
            moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.PollDecision.TrackEnded(1L),
            confirmedEndPauseResetToZero
        )

        assertTrue(
            moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.matchesNextTrackUri(
                reportedTrackUri = "http://192.168.1.50:49200/dlna/stream/next-123.flac",
                expectedNextUrl = "http://192.168.1.50:49200/dlna/stream/next-123.flac",
                expectedNextPath = "/dlna/stream/next-123.flac"
            )
        )
        assertFalse(
            moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine.matchesNextTrackUri(
                reportedTrackUri = "http://192.168.1.50:49200/dlna/stream/current-999.flac",
                expectedNextUrl = "http://192.168.1.50:49200/dlna/stream/next-123.flac",
                expectedNextPath = "/dlna/stream/next-123.flac"
            )
        )
    }

    private fun buildSyntheticMp4ForTest(mdatPayload: ByteArray): ByteArray {
        val ftyp = wrapMp4Box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(Charsets.ISO_8859_1))
        val mdat = wrapMp4Box("mdat", mdatPayload)
        val initialChunkOffset = ftyp.size + 8 // start of mdat payload when mdat precedes moov
        val stcoPayload = byteArrayOf(
            0, 0, 0, 0, // version/flags
            0, 0, 0, 1, // entry_count = 1
            ((initialChunkOffset ushr 24) and 0xFF).toByte(),
            ((initialChunkOffset ushr 16) and 0xFF).toByte(),
            ((initialChunkOffset ushr 8) and 0xFF).toByte(),
            (initialChunkOffset and 0xFF).toByte()
        )
        val stbl = wrapMp4Box("stbl", wrapMp4Box("stco", stcoPayload))
        val minf = wrapMp4Box("minf", stbl)
        val mdia = wrapMp4Box("mdia", minf)
        val trak = wrapMp4Box("trak", mdia)
        val moov = wrapMp4Box("moov", trak)
        return ftyp + mdat + moov
    }

    private fun wrapMp4Box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf(
            ((size ushr 24) and 0xFF).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte()
        ) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    private fun indexOfAscii(data: ByteArray, tag: String): Int {
        val needle = tag.toByteArray(Charsets.ISO_8859_1)
        for (i in 0..(data.size - needle.size)) {
            if (data[i] == needle[0] && data[i + 1] == needle[1] && data[i + 2] == needle[2] && data[i + 3] == needle[3]) {
                return i
            }
        }
        return -1
    }

    private fun decodeId3Frames(chunk: ByteArray): Map<String, String> {
        val size = readLe32(chunk, 4).toInt()
        val result = LinkedHashMap<String, String>()
        var at = 18
        while (at < 8 + size) {
            val id = String(chunk, at, 4, Charsets.US_ASCII)
            val len = ((chunk[at + 4].toInt() and 0xFF) shl 24) or
                ((chunk[at + 5].toInt() and 0xFF) shl 16) or
                ((chunk[at + 6].toInt() and 0xFF) shl 8) or
                (chunk[at + 7].toInt() and 0xFF)
            val text = String(chunk, at + 13, len - 3, Charsets.UTF_16LE)
            result[id] = text
            at += 10 + len
        }
        return result
    }

    private fun readLe32(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun buildSyntheticFlacBytes(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        totalSamples: Long,
        payloadBytes: Int = 256
    ): ByteArray {
        val bytes = ByteArray(4 + 4 + 34 + payloadBytes)
        bytes[0] = 'f'.code.toByte()
        bytes[1] = 'L'.code.toByte()
        bytes[2] = 'a'.code.toByte()
        bytes[3] = 'C'.code.toByte()
        // Last metadata block (0x80) + STREAMINFO (0), length = 34
        bytes[4] = 0x80.toByte()
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 34
        val streamInfoStart = 8
        // Packed sampleRate (20 bits), channels-1 (3 bits), bps-1 (5 bits), totalSamples (36 bits)
        bytes[streamInfoStart + 10] = ((sampleRate ushr 12) and 0xFF).toByte()
        bytes[streamInfoStart + 11] = ((sampleRate ushr 4) and 0xFF).toByte()
        val bpsMinus1 = bitDepth - 1
        val chMinus1 = channels - 1
        bytes[streamInfoStart + 12] = (((sampleRate and 0x0F) shl 4) or ((chMinus1 and 0x07) shl 1) or ((bpsMinus1 ushr 4) and 0x01)).toByte()
        bytes[streamInfoStart + 13] = (((bpsMinus1 and 0x0F) shl 4) or (((totalSamples ushr 32) and 0x0FL).toInt())).toByte()
        bytes[streamInfoStart + 14] = ((totalSamples ushr 24) and 0xFFL).toByte()
        bytes[streamInfoStart + 15] = ((totalSamples ushr 16) and 0xFFL).toByte()
        bytes[streamInfoStart + 16] = ((totalSamples ushr 8) and 0xFFL).toByte()
        bytes[streamInfoStart + 17] = (totalSamples and 0xFFL).toByte()
        return bytes
    }
}
