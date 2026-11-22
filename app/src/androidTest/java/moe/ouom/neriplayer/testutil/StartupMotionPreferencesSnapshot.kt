package moe.ouom.neriplayer.testutil

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.data.settings.dataStore

/** 引导经过动效一步时会写入推荐配置, 走引导的测试用它记下原值并在结束后还原, 原本没有的键要删掉 */
internal class StartupMotionPreferencesSnapshot private constructor(
    private val booleans: Map<String, Boolean?>,
    private val quality: String?
) {
    suspend fun restore(context: Context) {
        context.dataStore.edit { prefs ->
            booleans.forEach { (name, value) ->
                val key = booleanPreferencesKey(name)
                if (value == null) prefs.remove(key) else prefs[key] = value
            }
            val qualityKey = stringPreferencesKey(QUALITY_KEY)
            if (quality == null) prefs.remove(qualityKey) else prefs[qualityKey] = quality
        }
    }

    companion object {
        private const val QUALITY_KEY = "advanced_blur_quality"
        private val BooleanKeys = listOf(
            "advanced_blur_enabled",
            "enhanced_advanced_blur_enabled",
            "liquid_glass_enabled",
            "enhanced_visual_effects_enabled",
            "nowplaying_dynamic_background_enabled",
            "nowplaying_audio_reactive_enabled",
            "lyric_blur_enabled",
            "prefer_high_refresh_rate"
        )

        suspend fun capture(context: Context): StartupMotionPreferencesSnapshot {
            val prefs = context.dataStore.data.first()
            return StartupMotionPreferencesSnapshot(
                booleans = BooleanKeys.associateWith { prefs[booleanPreferencesKey(it)] },
                quality = prefs[stringPreferencesKey(QUALITY_KEY)]
            )
        }
    }
}
