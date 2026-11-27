package moe.ouom.neriplayer.core.player.service.presentation

import android.media.AudioDeviceInfo
import android.media.MediaMetadata
import android.os.Build
import moe.ouom.neriplayer.core.player.metadata.EXTERNAL_BLUETOOTH_METADATA_MAX_UTF8_BYTES
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class BluetoothLyricMetadataRegressionTest {
    private val song = SongItem(1L, "Song", "Artist", "Album", 1L, 60_000L, null)
    private val artwork = PlaybackArtworkSnapshot(null, null, null, false, false, false)

    @Test
    fun `connecting bluetooth audio keeps lyrics in the framework display title`() {
        val payload = ExternalBluetoothLyricPayload("line", "译文")
        val speaker = metadata(payload, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertEquals("line", speaker.description.title.toString())

        val routes = buildList {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }
        for (route in routes) {
            val connected = metadata(payload, route)
            assertEquals("Song | line | 译文", connected.getString(MediaMetadata.METADATA_KEY_TITLE))
            assertEquals("Song | line | 译文", connected.description.title.toString())
            assertEquals("Artist", connected.description.subtitle.toString())
            assertEquals("Album", connected.getString(MediaMetadata.METADATA_KEY_ALBUM))
        }
    }

    @Test
    fun `successive lyric lines update the framework display title without changing song identity`() {
        val first = metadata(ExternalBluetoothLyricPayload("first"))
        val second = metadata(ExternalBluetoothLyricPayload("second"))
        assertEquals("Song | first", first.description.title.toString())
        assertEquals("Song | second", second.description.title.toString())
        assertFalse(first.description.mediaId.isNullOrBlank())
        assertEquals(first.description.mediaId, second.description.mediaId)
        assertEquals("Artist", second.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals(60_000L, second.getLong(MediaMetadata.METADATA_KEY_DURATION))
    }

    @Test
    fun `translation only and empty payloads publish readable framework display titles`() {
        val translated = metadata(ExternalBluetoothLyricPayload(translation = "译文"))
        assertEquals("Song | 译文", translated.description.title.toString())

        val empty = metadata(ExternalBluetoothLyricPayload())
        assertEquals("Song", empty.description.title.toString())
        assertEquals("Artist", empty.description.subtitle.toString())
    }

    @Test
    fun `framework display title respects the bluetooth utf8 limit without splitting code points`() {
        val result = metadata(ExternalBluetoothLyricPayload("歌词🎵".repeat(200)))
        val title = result.description.title.toString()
        assertEquals(result.getString(MediaMetadata.METADATA_KEY_TITLE), title)
        assertTrue(title.startsWith("Song | 歌词🎵"))
        assertTrue(title.toByteArray(Charsets.UTF_8).size <= EXTERNAL_BLUETOOTH_METADATA_MAX_UTF8_BYTES)
        assertFalse(title.contains('\uFFFD'))
    }

    /**
     * 蓝牙输出时, 「蓝牙外接设备翻译歌词」关闭后 metadata 里不能带翻译。
     * 开关变化要立刻反映到 metadata, 不能等下一行歌词。
     */
    @Test
    fun `disabling the bluetooth translation switch strips the translation from the metadata`() {
        val payload = ExternalBluetoothLyricPayload("line", "译文")

        val withTranslation = serviceMetadataText(
            song = song,
            payload = payload,
            audioDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            forceSendLyrics = true,
            bluetoothTranslationEnabled = true,
        )
        val withoutTranslation = serviceMetadataText(
            song = song,
            payload = payload,
            audioDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            forceSendLyrics = true,
            bluetoothTranslationEnabled = false,
        )

        assertTrue(withTranslation.title.contains("译文"))
        assertFalse(withoutTranslation.title.contains("译文"))
        // 歌词本身要保留
        assertTrue(withoutTranslation.title.contains("line"))
    }

    /**
     * 关掉车机翻译时, 只有 AVRCP 面向的 TITLE/ARTIST 丢掉译文。
     * 灵动岛读的 DISPLAY_* 字段必须保留译文, 否则关车机翻译会连带关掉灵动岛翻译。
     */
    @Test
    fun `disabling the bluetooth translation switch keeps the display fields translated`() {
        val payload = ExternalBluetoothLyricPayload("line", "译文")

        val text = serviceMetadataText(
            song = song,
            payload = payload,
            audioDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            forceSendLyrics = true,
            bluetoothTranslationEnabled = false,
        )

        // AVRCP 面向车机的字段: 不带译文
        assertFalse(text.title.contains("译文"))
        assertFalse(text.artist.contains("译文"))
        // 系统展示面(灵动岛等)读的字段: 保留译文
        assertTrue(text.displayTitle.contains("译文"))
        assertTrue(text.displayDescription.orEmpty().contains("译文"))
    }

    /**
     * 非蓝牙输出时, 翻译开关不影响 metadata: 灵动岛、锁屏歌词等展示面仍然要带翻译。
     * 非蓝牙时标题只放歌词本身, 译文落在 artist 上, 所以两处都要查。
     */
    @Test
    fun `the bluetooth translation switch does not affect non bluetooth output`() {
        val payload = ExternalBluetoothLyricPayload("line", "译文")

        val withTranslation = serviceMetadataText(
            song = song,
            payload = payload,
            audioDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            forceSendLyrics = true,
            bluetoothTranslationEnabled = true,
        )
        val withoutTranslation = serviceMetadataText(
            song = song,
            payload = payload,
            audioDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            forceSendLyrics = true,
            bluetoothTranslationEnabled = false,
        )

        assertTrue(withTranslation.title.contains("译文") || withTranslation.artist.contains("译文"))
        assertTrue(withoutTranslation.title.contains("译文") || withoutTranslation.artist.contains("译文"))
    }

    private fun metadata(
        payload: ExternalBluetoothLyricPayload,
        route: Int = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    ): MediaMetadata {
        val text = serviceMetadataText(
            song = song,
            payload = payload,
            audioDeviceType = route,
            forceSendLyrics = true,
            bluetoothTranslationEnabled = true,
        )
        val mediaId = serviceMetadataMediaId(song, listOf(song), 0)
        val snapshot = serviceMetadataSnapshot(song, text, artwork, 1, 0, mediaId = mediaId)
        return serviceMediaMetadata(snapshot, artwork)
    }
}
