package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics.coloros

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigException
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ColorOsSystemUiAssetLoaderTest {
    @Test
    fun readsResourcesWithoutTargetCodeAndClosesTheAsset() {
        val fixture = fixture("<config>歌词: a,b</config>")
        val asset = loadColorOsSystemUiAsset(fixture.context)
        assertEquals("<config>歌词: a,b</config>", asset.xml)
        assertEquals(17L, asset.versionCode)
        assertEquals(42L, asset.lastUpdateTime)
        assertTrue(fixture.input.closed)
    }

    @Test
    fun rejectsSystemUiReplacementDuringTheRead() {
        val fixture = fixture("<config/>", changed = true)
        val failure = assertThrows(ColorOsConfigException::class.java) {
            loadColorOsSystemUiAsset(fixture.context)
        }
        assertEquals(ColorOsConfigStatus.CONFLICT, failure.status)
        assertTrue(fixture.input.closed)
    }

    @Test
    fun oversizedAssetsFailBeforePrivilegeCanBeRequestedAndCloseTheStream() {
        val fixture = fixture("x".repeat(128 * 1024 + 1))
        val failure = assertThrows(ColorOsConfigException::class.java) {
            loadColorOsSystemUiAsset(fixture.context)
        }
        assertEquals(ColorOsConfigStatus.UNSUPPORTED, failure.status)
        assertEquals("systemui_asset_too_large", failure.detailCode)
        assertTrue(fixture.input.closed)
    }

    private fun fixture(xml: String, changed: Boolean = false): Fixture {
        val context = mock(Context::class.java)
        val target = mock(Context::class.java)
        val manager = mock(PackageManager::class.java)
        val assets = mock(AssetManager::class.java)
        val input = TrackedInput(xml.toByteArray(Charsets.UTF_8))
        val before = PackageInfo().apply { setLongVersionCode(17); lastUpdateTime = 42 }
        val after = PackageInfo().apply { setLongVersionCode(if (changed) 18 else 17); lastUpdateTime = 42 }
        `when`(context.packageManager).thenReturn(manager)
        `when`(context.createPackageContext("com.android.systemui", 0)).thenReturn(target)
        `when`(target.assets).thenReturn(assets)
        `when`(assets.open("app_systemui_oplus_media_controller_config.xml")).thenReturn(input)
        `when`(manager.getPackageInfo("com.android.systemui", 0)).thenReturn(before, after)
        return Fixture(context, input)
    }

    private data class Fixture(val context: Context, val input: TrackedInput)

    private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}
