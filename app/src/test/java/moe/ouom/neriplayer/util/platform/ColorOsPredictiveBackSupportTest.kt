package moe.ouom.neriplayer.util.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorOsPredictiveBackSupportTest {
    @Test
    fun `unknown device does not enable the preview`() {
        assertFalse(supportsColorOsPredictiveBack(manufacturer = null, brand = null))
        assertFalse(supportsColorOsPredictiveBack(manufacturer = "", brand = " "))
    }

    @Test
    fun `oppo and oneplus brands alone do not establish coloros`() {
        assertFalse(supportsColorOsPredictiveBack(manufacturer = "OPPO", brand = "OPPO"))
        assertFalse(supportsColorOsPredictiveBack(manufacturer = "OnePlus", brand = "OnePlus"))
    }

    @Test
    fun `oppo coloros supports domestic and international roms`() {
        assertTrue(
            supportsColorOsPredictiveBack(
                manufacturer = "OPPO",
                brand = "OPPO",
                oppoRom = "V14.0.0",
                otaVersion = "PHZ110_14.0.0.700(CN01)"
            )
        )
        assertTrue(
            supportsColorOsPredictiveBack(
                manufacturer = "OPPO",
                brand = "OPPO",
                oplusRom = "V15.0.0",
                otaVersion = "CPH2659_15.0.0.700(EX01)"
            )
        )
    }

    @Test
    fun `oneplus chinese rom needs explicit coloros evidence`() {
        assertTrue(
            supportsColorOsPredictiveBack(
                manufacturer = "OnePlus",
                brand = "OnePlus",
                oplusRom = "V15.0.0",
                otaVersion = "PJZ110_15.0.0.401(CN01)"
            )
        )
        assertTrue(
            supportsColorOsPredictiveBack(
                manufacturer = "OnePlus",
                brand = "OnePlus",
                oppoRom = "ColorOS 16.0"
            )
        )
        assertFalse(
            supportsColorOsPredictiveBack(
                manufacturer = "OnePlus",
                brand = "OnePlus",
                oplusRom = "V15.0.0"
            )
        )
    }

    @Test
    fun `oneplus international ota is rejected despite shared oplus properties`() {
        assertFalse(
            supportsColorOsPredictiveBack(
                manufacturer = "OnePlus",
                brand = "OnePlus",
                oppoRom = "ColorOS 15.0",
                oplusRom = "V15.0.0",
                otaVersion = "CPH2653_15.0.0.406(EX01)"
            )
        )
    }

    @Test
    fun `oxygenos marker overrides positive coloros evidence`() {
        assertFalse(
            supportsColorOsPredictiveBack(
                manufacturer = "OnePlus",
                brand = "OnePlus",
                oppoRom = "ColorOS 15.0",
                oplusRom = "V15.0.0",
                oxygenVersion = "OxygenOS 15.0",
                otaVersion = "PJZ110_15.0.0.401(CN01)"
            )
        )
        assertFalse(
            supportsColorOsPredictiveBack(
                manufacturer = "OPPO",
                brand = "OPPO",
                oppoRom = "OxygenOS 15.0"
            )
        )
    }

    @Test
    fun `realme ui is rejected even when the manufacturer is oppo`() {
        assertFalse(
            supportsColorOsPredictiveBack(
                manufacturer = "OPPO",
                brand = "realme",
                oppoRom = "V14.0.0",
                oplusRom = "V15.0.0",
                otaVersion = "RMX3852_15.0.0.500(CN01)"
            )
        )
        assertFalse(
            supportsColorOsPredictiveBack(
                manufacturer = "realme",
                brand = "OPPO",
                oppoRom = "ColorOS 15.0"
            )
        )
    }

    @Test
    fun `unknown and custom roms do not inherit vendor support`() {
        assertFalse(
            supportsColorOsPredictiveBack(
                manufacturer = "Google",
                brand = "google",
                oppoRom = "ColorOS 15.0"
            )
        )
        assertFalse(
            supportsColorOsPredictiveBack(
                manufacturer = "OPPO",
                brand = "OPPO",
                oppoRom = "unknown",
                oplusRom = "AOSP"
            )
        )
        assertFalse(
            supportsColorOsPredictiveBack(
                manufacturer = "OnePlus",
                brand = "OnePlus",
                otaVersion = "PJZ110_15.0.0.401(CN01)"
            )
        )
    }

    @Test
    fun `rom fields tolerate casing and surrounding whitespace`() {
        assertTrue(
            supportsColorOsPredictiveBack(
                manufacturer = " onePLUS ",
                brand = null,
                oplusRom = " v15.0.0 ",
                otaVersion = " pjz110_15.0.0.401(cn01) "
            )
        )
        assertFalse(
            supportsColorOsPredictiveBack(
                manufacturer = " OnePlus ",
                brand = " ONEPLUS ",
                oppoRom = " ColorOS 15.0 ",
                oxygenVersion = " oxygenos 15.0 "
            )
        )
    }
}
