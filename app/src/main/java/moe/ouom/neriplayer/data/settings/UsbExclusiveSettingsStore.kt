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
                selectedDeviceKey = preferences[SettingsKeys.USB_EXCLUSIVE_DEVICE_KEY],
                sampleRateCompatibilityEnabled =
                    preferences[SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY],
                bitDepthCompatibilityEnabled =
                    preferences[SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY],
                channelCompatibilityEnabled =
                    preferences[SettingsKeys.USB_EXCLUSIVE_CHANNEL_COMPATIBILITY],
                foregroundBufferMs =
                    preferences[SettingsKeys.USB_EXCLUSIVE_FOREGROUND_BUFFER_MS],
                backgroundBufferMs =
                    preferences[SettingsKeys.USB_EXCLUSIVE_BACKGROUND_BUFFER_MS]
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

    val sampleRateCompatibilityFlow: Flow<Boolean> = preferencesFlow
        .map { preferences -> preferences.sampleRateCompatibilityEnabled }
        .distinctUntilChanged()

    val bitDepthCompatibilityFlow: Flow<Boolean> = preferencesFlow
        .map { preferences -> preferences.bitDepthCompatibilityEnabled }
        .distinctUntilChanged()

    val channelCompatibilityFlow: Flow<Boolean> = preferencesFlow
        .map { preferences -> preferences.channelCompatibilityEnabled }
        .distinctUntilChanged()

    val foregroundBufferMsFlow: Flow<Int> = preferencesFlow
        .map { preferences -> preferences.foregroundBufferMs }
        .distinctUntilChanged()

    val backgroundBufferMsFlow: Flow<Int> = preferencesFlow
        .map { preferences -> preferences.backgroundBufferMs }
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

    suspend fun setSampleRateCompatibilityEnabled(enabled: Boolean) {
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY,
            value = enabled
        ) {
            copy(usbExclusiveSampleRateCompatibility = enabled)
        }
    }

    suspend fun setBitDepthCompatibilityEnabled(enabled: Boolean) {
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY,
            value = enabled
        ) {
            copy(usbExclusiveBitDepthCompatibility = enabled)
        }
    }

    suspend fun setChannelCompatibilityEnabled(enabled: Boolean) {
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_CHANNEL_COMPATIBILITY,
            value = enabled
        ) {
            copy(usbExclusiveChannelCompatibility = enabled)
        }
    }

    suspend fun setForegroundBufferMs(bufferMs: Int) {
        val normalized = normalizeUsbExclusiveBufferMs(bufferMs)
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_FOREGROUND_BUFFER_MS,
            value = normalized
        ) {
            copy(usbExclusiveForegroundBufferMs = normalized)
        }
    }

    suspend fun setBackgroundBufferMs(bufferMs: Int) {
        val normalized = normalizeUsbExclusiveBufferMs(bufferMs)
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_BACKGROUND_BUFFER_MS,
            value = normalized
        ) {
            copy(usbExclusiveBackgroundBufferMs = normalized)
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
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY] =
                preferences.sampleRateCompatibilityEnabled
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY] =
                preferences.bitDepthCompatibilityEnabled
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_CHANNEL_COMPATIBILITY] =
                preferences.channelCompatibilityEnabled
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_FOREGROUND_BUFFER_MS] =
                preferences.foregroundBufferMs
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_BACKGROUND_BUFFER_MS] =
                preferences.backgroundBufferMs
        }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(
                usbExclusiveDeviceKey = preferences.selectedDeviceKey,
                usbExclusiveSampleRateMode = preferences.sampleRateMode.storageValue,
                usbExclusiveBitDepthMode = preferences.bitDepthMode.storageValue,
                usbExclusiveBufferProfile = preferences.bufferProfile.storageValue,
                usbExclusiveUnsupportedFormatPolicy =
                    preferences.unsupportedFormatPolicy.storageValue,
                usbExclusiveSampleRateCompatibility =
                    preferences.sampleRateCompatibilityEnabled,
                usbExclusiveBitDepthCompatibility =
                    preferences.bitDepthCompatibilityEnabled,
                usbExclusiveChannelCompatibility =
                    preferences.channelCompatibilityEnabled,
                usbExclusiveForegroundBufferMs = preferences.foregroundBufferMs,
                usbExclusiveBackgroundBufferMs = preferences.backgroundBufferMs
            )
        }
    }

    private suspend fun <T> setStoredPreference(
        key: Preferences.Key<T>,
        value: T,
        updateSnapshot: PlaybackPreferenceSnapshot.() -> PlaybackPreferenceSnapshot
    ) {
        context.dataStore.edit { it[key] = value }
        updatePlaybackPreferenceSnapshot(context) { snapshot ->
            snapshot.updateSnapshot()
        }
    }
}
