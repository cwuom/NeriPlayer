package moe.ouom.neriplayer.ui.effect.glass

import android.content.res.AssetManager
import java.io.IOException
import java.io.InputStream

internal const val ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH =
    "shaders/advanced_glass_region_mask.agsl"

internal class AdvancedGlassShaderSource(
    private val assetManager: AssetManager
) {
    private val source by lazy {
        readShaderSource(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH) { path ->
            assetManager.open(path)
        }
    }

    fun load(): String = source
}

internal fun readShaderSource(
    assetPath: String,
    openAsset: (String) -> InputStream
): String {
    val source = try {
        openAsset(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText()
        }
    } catch (error: IOException) {
        throw IllegalStateException("Failed to load shader asset: $assetPath", error)
    }
    check(source.isNotBlank()) { "Shader asset is empty: $assetPath" }
    return source
}
