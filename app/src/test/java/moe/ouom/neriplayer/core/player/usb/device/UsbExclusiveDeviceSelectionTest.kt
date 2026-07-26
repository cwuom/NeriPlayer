package moe.ouom.neriplayer.core.player.usb.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 H1(多设备选错/无声)与 L1(子串误命中)修复的纯函数级判定:
 * - auto + 多设备拒绝而非静默取首个
 * - 指定 key 时 host 与音频侧收敛到同一设备
 * - label 精确匹配不误命中子串兄弟设备
 */
class UsbExclusiveDeviceSelectionTest {

    private data class FakeUsbAudioDevice(val label: String)

    private val autoKey = "auto"

    @Test
    fun `auto selects the sole usb device`() {
        val devices = listOf(FakeUsbAudioDevice("dac_a"))
        val result = selectUsbExclusiveDevice(devices, autoKey, allowSingleFallback = false) { true }
        assertEquals(UsbExclusiveDeviceSelectionOutcome.SELECTED, result.outcome)
        assertEquals("dac_a", result.device?.label)
    }

    @Test
    fun `auto with multiple usb devices is ambiguous instead of picking first`() {
        val devices = listOf(FakeUsbAudioDevice("dac_a"), FakeUsbAudioDevice("dac_b"))
        val hostResult =
            selectUsbExclusiveDevice(devices, autoKey, allowSingleFallback = false) { true }
        val audioResult =
            selectUsbExclusiveDevice(devices, autoKey, allowSingleFallback = true) { true }
        assertEquals(UsbExclusiveDeviceSelectionOutcome.AMBIGUOUS, hostResult.outcome)
        assertEquals(UsbExclusiveDeviceSelectionOutcome.AMBIGUOUS, audioResult.outcome)
        assertNull(hostResult.device)
        assertNull(audioResult.device)
    }

    @Test
    fun `auto with no usb device is none`() {
        val result = selectUsbExclusiveDevice(
            emptyList<FakeUsbAudioDevice>(),
            autoKey,
            allowSingleFallback = true
        ) { true }
        assertEquals(UsbExclusiveDeviceSelectionOutcome.NONE, result.outcome)
    }

    @Test
    fun `explicit key selects the same device on host and audio sides`() {
        val key = "usb:1234:5678:dac"
        val devices = listOf(FakeUsbAudioDevice("DAC"), FakeUsbAudioDevice("DAC Pro"))
        val hostPick = selectUsbExclusiveDevice(devices, key, allowSingleFallback = false) { d ->
            usbExclusiveDeviceKeyMatchesLabel(key, d.label)
        }
        val audioPick = selectUsbExclusiveDevice(devices, key, allowSingleFallback = true) { d ->
            usbExclusiveDeviceKeyMatchesLabel(key, d.label)
        }
        assertEquals(UsbExclusiveDeviceSelectionOutcome.SELECTED, hostPick.outcome)
        assertEquals(UsbExclusiveDeviceSelectionOutcome.SELECTED, audioPick.outcome)
        assertEquals("DAC", hostPick.device?.label)
        assertEquals("DAC", audioPick.device?.label)
    }

    @Test
    fun `exact label match does not hit substring sibling`() {
        val key = "usb:1234:5678:dac"
        assertTrue(usbExclusiveDeviceKeyMatchesLabel(key, "DAC"))
        assertFalse(usbExclusiveDeviceKeyMatchesLabel(key, "DAC Pro"))
        assertFalse(usbExclusiveDeviceKeyMatchesLabel(key, "Super DAC"))
    }

    @Test
    fun `audio side falls back to sole output when host label mismatches`() {
        // productName 为空导致 host label 与音频 label 不一致时,音频侧仅在唯一输出时可安全退回
        val key = "usb:1234:5678:mismatch"
        val single = listOf(FakeUsbAudioDevice("actual_dac"))
        val audioSingle = selectUsbExclusiveDevice(single, key, allowSingleFallback = true) { d ->
            usbExclusiveDeviceKeyMatchesLabel(key, d.label)
        }
        val hostSingle = selectUsbExclusiveDevice(single, key, allowSingleFallback = false) { d ->
            usbExclusiveDeviceKeyMatchesLabel(key, d.label)
        }
        assertEquals(UsbExclusiveDeviceSelectionOutcome.SELECTED, audioSingle.outcome)
        assertEquals("actual_dac", audioSingle.device?.label)
        // host 侧不退回其它设备:用户所选设备不在场即视为未找到
        assertEquals(UsbExclusiveDeviceSelectionOutcome.NONE, hostSingle.outcome)
    }

    @Test
    fun `explicit no match with multiple candidates is none not ambiguous`() {
        // 指定设备不在场时,即便在场多个其它设备也应拒绝(NONE),而非误报歧义:
        // 歧义仅指 "key 命中多个" 或 auto 多设备,与 "指定设备缺席" 是两回事
        val key = "usb:1234:5678:mismatch"
        val many = listOf(FakeUsbAudioDevice("dac_a"), FakeUsbAudioDevice("dac_b"))
        val audioMany = selectUsbExclusiveDevice(many, key, allowSingleFallback = true) { d ->
            usbExclusiveDeviceKeyMatchesLabel(key, d.label)
        }
        assertEquals(UsbExclusiveDeviceSelectionOutcome.NONE, audioMany.outcome)
    }

    @Test
    fun `two identical labels matching one key is ambiguous`() {
        // 同型号双 DAC(M3)以安全拒绝暴露,而非静默取首个选错单元
        val key = "usb:1234:5678:dac"
        val twins = listOf(FakeUsbAudioDevice("DAC"), FakeUsbAudioDevice("DAC"))
        val result = selectUsbExclusiveDevice(twins, key, allowSingleFallback = true) { d ->
            usbExclusiveDeviceKeyMatchesLabel(key, d.label)
        }
        assertEquals(UsbExclusiveDeviceSelectionOutcome.AMBIGUOUS, result.outcome)
    }
}
