package moe.ouom.neriplayer.data.config

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import moe.ouom.neriplayer.data.model.config.TypedPreferenceSnapshot
import moe.ouom.neriplayer.data.model.settings.appearance.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.SettingsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigSettingsPreferencesTest {
    private val booleanKey = SETTINGS_BOOLEAN_KEYS[0]
    private val otherBooleanKey = SETTINGS_BOOLEAN_KEYS[1]
    private val floatKey = SETTINGS_FLOAT_KEYS.first()
    private val intKey = SETTINGS_INT_KEYS.first()
    private val longKey = SETTINGS_LONG_KEYS.first()
    private val stringKey = SETTINGS_STRING_KEYS.first()
    private val unrelatedKey = stringPreferencesKey("not_a_backed_up_setting")

    @Test
    fun `exported snapshots keep only backed up keys with their stored types`() {
        val prefs = mutablePreferencesOf(
            booleanKey to true,
            floatKey to 1.5f,
            intKey to 3,
            longKey to 4L,
            stringKey to "value",
            unrelatedKey to "ignored"
        )

        assertEquals(
            TypedPreferenceSnapshot(
                booleans = mapOf(booleanKey.name to true),
                floats = mapOf(floatKey.name to 1.5f),
                ints = mapOf(intKey.name to 3),
                longs = mapOf(longKey.name to 4L),
                strings = mapOf(stringKey.name to "value")
            ),
            prefs.toTypedPreferenceSnapshot()
        )
        assertEquals(
            TypedPreferenceSnapshot(),
            mutablePreferencesOf(stringPreferencesKey(booleanKey.name) to "yes").toTypedPreferenceSnapshot()
        )
    }

    @Test
    fun `restoring a snapshot replaces backed up keys and leaves other keys alone`() {
        val prefs = mutablePreferencesOf(
            booleanKey to false,
            otherBooleanKey to true,
            stringKey to "old",
            unrelatedKey to "kept"
        )

        prefs.replaceSettingsWith(
            TypedPreferenceSnapshot(
                booleans = mapOf(booleanKey.name to true),
                longs = mapOf(longKey.name to 9L)
            )
        )

        assertEquals(true, prefs[booleanKey])
        assertNull(prefs[otherBooleanKey])
        assertNull(prefs[stringKey])
        assertEquals(9L, prefs[longKey])
        assertEquals("kept", prefs[unrelatedKey])
    }

    @Test
    fun `theme snapshots fall back to dynamic light colors that follow the system`() {
        assertEquals(
            ThemePreferenceSnapshot(dynamicColor = true, forceDark = false, followSystemDark = true),
            emptyPreferences().toThemePreferenceSnapshot()
        )
        assertEquals(
            ThemePreferenceSnapshot(dynamicColor = false, forceDark = true, followSystemDark = false),
            mutablePreferencesOf(
                SettingsKeys.DYNAMIC_COLOR to false,
                SettingsKeys.FORCE_DARK to true,
                SettingsKeys.FOLLOW_SYSTEM_DARK to false
            ).toThemePreferenceSnapshot()
        )
    }
}
