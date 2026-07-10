package moe.ouom.neriplayer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class UsbExclusiveSettingsStore(private val context: Context) {
    val preferencesFlow: Flow<UsbExclusivePreferences> = context.dataStore.data
        .map { preferences ->
            UsbExclusivePreferences.fromStorageValues(
                sampleRateMode = preferences[SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_MODE],
                bitDepthMode = preferences[SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_MODE],
                bufferProfile = preferences[SettingsKeys.USB_EXCLUSIVE_BUFFER_PROFILE],
                unsupportedFormatPolicy =
                    preferences[SettingsKeys.USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY],
                selectedDeviceKey = preferences[SettingsKeys.USB_EXCLUSIVE_DEVICE_KEY]
            )
        }
        .distinctUntilChanged()

    val selectedDeviceKeyFlow: Flow<String> = preferencesFlow
        .map { preferences -> preferences.selectedDeviceKey }
        .distinctUntilChanged()

    val sampleRateModeFlow: Flow<UsbExclusiveSampleRateMode> = preferencesFlow
        .map { preferences -> preferences.sampleRateMode }
        .distinctUntilChanged()

    val bitDepthModeFlow: Flow<UsbExclusiveBitDepthMode> = preferencesFlow
        .map { preferences -> preferences.bitDepthMode }
        .distinctUntilChanged()

    val bufferProfileFlow: Flow<UsbExclusiveBufferProfile> = preferencesFlow
        .map { preferences -> preferences.bufferProfile }
        .distinctUntilChanged()

    val unsupportedFormatPolicyFlow: Flow<UsbExclusiveUnsupportedFormatPolicy> = preferencesFlow
        .map { preferences -> preferences.unsupportedFormatPolicy }
        .distinctUntilChanged()

    suspend fun setSampleRateMode(mode: UsbExclusiveSampleRateMode) {
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_MODE,
            value = mode.storageValue
        ) {
            copy(usbExclusiveSampleRateMode = mode.storageValue)
        }
    }

    suspend fun setSelectedDeviceKey(deviceKey: String) {
        val normalizedKey = normalizeUsbExclusiveDeviceKey(deviceKey)
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_DEVICE_KEY,
            value = normalizedKey
        ) {
            copy(usbExclusiveDeviceKey = normalizedKey)
        }
    }

    suspend fun setBitDepthMode(mode: UsbExclusiveBitDepthMode) {
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_MODE,
            value = mode.storageValue
        ) {
            copy(usbExclusiveBitDepthMode = mode.storageValue)
        }
    }

    suspend fun setBufferProfile(profile: UsbExclusiveBufferProfile) {
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_BUFFER_PROFILE,
            value = profile.storageValue
        ) {
            copy(usbExclusiveBufferProfile = profile.storageValue)
        }
    }

    suspend fun setUnsupportedFormatPolicy(policy: UsbExclusiveUnsupportedFormatPolicy) {
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY,
            value = policy.storageValue
        ) {
            copy(usbExclusiveUnsupportedFormatPolicy = policy.storageValue)
        }
    }

    suspend fun setPreferences(preferences: UsbExclusivePreferences) {
        context.dataStore.edit { mutablePreferences ->
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_DEVICE_KEY] =
                preferences.selectedDeviceKey
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_MODE] =
                preferences.sampleRateMode.storageValue
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_MODE] =
                preferences.bitDepthMode.storageValue
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_BUFFER_PROFILE] =
                preferences.bufferProfile.storageValue
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY] =
                preferences.unsupportedFormatPolicy.storageValue
        }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(
                usbExclusiveDeviceKey = preferences.selectedDeviceKey,
                usbExclusiveSampleRateMode = preferences.sampleRateMode.storageValue,
                usbExclusiveBitDepthMode = preferences.bitDepthMode.storageValue,
                usbExclusiveBufferProfile = preferences.bufferProfile.storageValue,
                usbExclusiveUnsupportedFormatPolicy =
                    preferences.unsupportedFormatPolicy.storageValue
            )
        }
    }

    private suspend fun setStoredPreference(
        key: Preferences.Key<String>,
        value: String,
        updateSnapshot: PlaybackPreferenceSnapshot.() -> PlaybackPreferenceSnapshot
    ) {
        context.dataStore.edit { it[key] = value }
        updatePlaybackPreferenceSnapshot(context) { snapshot ->
            snapshot.updateSnapshot()
        }
    }
}
