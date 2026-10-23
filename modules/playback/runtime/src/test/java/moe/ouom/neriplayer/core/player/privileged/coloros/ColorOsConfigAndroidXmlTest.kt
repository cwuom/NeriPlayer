package moe.ouom.neriplayer.core.player.privileged.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class ColorOsConfigAndroidXmlTest {
    @Test fun `Android XML factory supports bounded safe transformations`() {
        val original = "<config><version>10</version><whitelist/><policy/><excludeList/></config>"
        val target = ColorOsConfigXml.enable(original, "moe.ouom.neriplayer", 11)
        assertEquals(11L, ColorOsConfigXml.version(target))
        assertTrue(ColorOsConfigXml.isEnabled(target, "moe.ouom.neriplayer"))
    }
}
