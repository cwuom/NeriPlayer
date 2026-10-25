package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics.coloros

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.io.ByteArrayOutputStream
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigException
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigStatus
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsSystemUiAsset

private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
private const val SYSTEM_UI_ASSET = "app_systemui_oplus_media_controller_config.xml"
private const val MAX_SYSTEM_UI_ASSET_BYTES = 128 * 1024

internal fun loadColorOsSystemUiAsset(context: Context): ColorOsSystemUiAsset {
    try {
        val before = context.packageManager.getPackageInfo(SYSTEM_UI_PACKAGE, 0)
        val xml = context.createPackageContext(SYSTEM_UI_PACKAGE, 0).assets.open(SYSTEM_UI_ASSET).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count = input.read(buffer)
            while (count != -1) {
                if (output.size() + count > MAX_SYSTEM_UI_ASSET_BYTES) {
                    throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "systemui_asset_too_large")
                }
                output.write(buffer, 0, count)
                count = input.read(buffer)
            }
            output.toString(Charsets.UTF_8.name())
        }
        val after = context.packageManager.getPackageInfo(SYSTEM_UI_PACKAGE, 0)
        if (PackageInfoCompat.getLongVersionCode(before) != PackageInfoCompat.getLongVersionCode(after) ||
            before.lastUpdateTime != after.lastUpdateTime
        ) {
            throw ColorOsConfigException(ColorOsConfigStatus.CONFLICT, "systemui_changed_during_read")
        }
        return ColorOsSystemUiAsset(PackageInfoCompat.getLongVersionCode(after), after.lastUpdateTime, xml)
    } catch (failure: ColorOsConfigException) {
        throw failure
    } catch (_: Exception) {
        throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "systemui_asset_unavailable")
    }
}
