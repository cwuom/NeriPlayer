package moe.ouom.neriplayer.data.settings.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.dataStore

private val PREVIEW_THEME_PROMPT_ANSWERED = booleanPreferencesKey("preview_theme_prompt_answered")

class PreviewThemePromptRepository(
    private val dataStore: DataStore<Preferences>,
    private val onUiStyleChanged: (UiStyle) -> Unit = {}
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.dataStore,
        onUiStyleChanged = context.applicationContext.let { appContext ->
            { style -> persistThemeUiStyle(appContext, style) }
        }
    )

    val shouldPromptFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        when {
            preferences[PREVIEW_THEME_PROMPT_ANSWERED] == true -> false
            UiStyle.fromStorage(preferences[SettingsKeys.UI_STYLE]) == UiStyle.Clean -> {
                // 已经主动启用过预览主题, 切回 Material 后也无需再次询问
                dataStore.edit { it[PREVIEW_THEME_PROMPT_ANSWERED] = true }
                false
            }
            else -> true
        }
    }.distinctUntilChanged()

    suspend fun answer(enable: Boolean) {
        dataStore.edit { preferences ->
            preferences[PREVIEW_THEME_PROMPT_ANSWERED] = true
            if (enable) preferences[SettingsKeys.UI_STYLE] = UiStyle.Clean.storageValue
        }
        if (enable) onUiStyleChanged(UiStyle.Clean)
    }
}
