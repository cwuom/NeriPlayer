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
                    preferences[SettingsKeys.USB_EXCLUSIVE_BACKGROUND_BUFFER_MS],
                volumeRiskThresholdDbfs =
                    preferences[SettingsKeys.USB_EXCLUSIVE_VOLUME_RISK_THRESHOLD_DBFS]
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

    val volumeRiskThresholdDbfsFlow: Flow<Int> = preferencesFlow
        .map { preferences -> preferences.volumeRiskThresholdDbfs }
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
        val normalized = normalizeUsbExclusiveForegroundBufferMs(bufferMs)
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_FOREGROUND_BUFFER_MS,
            value = normalized
        ) {
            copy(usbExclusiveForegroundBufferMs = normalized)
        }
    }

    suspend fun setBackgroundBufferMs(bufferMs: Int) {
        val normalized = normalizeUsbExclusiveBackgroundBufferMs(bufferMs)
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_BACKGROUND_BUFFER_MS,
            value = normalized
        ) {
            copy(usbExclusiveBackgroundBufferMs = normalized)
        }
    }

    suspend fun setVolumeRiskThresholdDbfs(thresholdDbfs: Int) {
        val normalized = normalizeUsbExclusiveVolumeRiskThresholdDbfs(thresholdDbfs)
        setStoredPreference(
            key = SettingsKeys.USB_EXCLUSIVE_VOLUME_RISK_THRESHOLD_DBFS,
            value = normalized
        ) {
            copy(usbExclusiveVolumeRiskThresholdDbfs = normalized)
        }
    }

    suspend fun setPreferences(preferences: UsbExclusivePreferences) {
        val normalizedPreferences = preferences.copy(
            foregroundBufferMs = normalizeUsbExclusiveForegroundBufferMs(
                preferences.foregroundBufferMs
            ),
            backgroundBufferMs = normalizeUsbExclusiveBackgroundBufferMs(
                preferences.backgroundBufferMs
            ),
            volumeRiskThresholdDbfs = normalizeUsbExclusiveVolumeRiskThresholdDbfs(
                preferences.volumeRiskThresholdDbfs
            )
        )
        context.dataStore.edit { mutablePreferences ->
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_DEVICE_KEY] =
                normalizedPreferences.selectedDeviceKey
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_MODE] =
                normalizedPreferences.sampleRateMode.storageValue
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_MODE] =
                normalizedPreferences.bitDepthMode.storageValue
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_BUFFER_PROFILE] =
                normalizedPreferences.bufferProfile.storageValue
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY] =
                normalizedPreferences.unsupportedFormatPolicy.storageValue
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY] =
                normalizedPreferences.sampleRateCompatibilityEnabled
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY] =
                normalizedPreferences.bitDepthCompatibilityEnabled
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_CHANNEL_COMPATIBILITY] =
                normalizedPreferences.channelCompatibilityEnabled
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_FOREGROUND_BUFFER_MS] =
                normalizedPreferences.foregroundBufferMs
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_BACKGROUND_BUFFER_MS] =
                normalizedPreferences.backgroundBufferMs
            mutablePreferences[SettingsKeys.USB_EXCLUSIVE_VOLUME_RISK_THRESHOLD_DBFS] =
                normalizedPreferences.volumeRiskThresholdDbfs
        }
        updatePlaybackPreferenceSnapshot(context) {
            it.copy(
                usbExclusiveDeviceKey = normalizedPreferences.selectedDeviceKey,
                usbExclusiveSampleRateMode = normalizedPreferences.sampleRateMode.storageValue,
                usbExclusiveBitDepthMode = normalizedPreferences.bitDepthMode.storageValue,
                usbExclusiveBufferProfile = normalizedPreferences.bufferProfile.storageValue,
                usbExclusiveUnsupportedFormatPolicy =
                    normalizedPreferences.unsupportedFormatPolicy.storageValue,
                usbExclusiveSampleRateCompatibility =
                    normalizedPreferences.sampleRateCompatibilityEnabled,
                usbExclusiveBitDepthCompatibility =
                    normalizedPreferences.bitDepthCompatibilityEnabled,
                usbExclusiveChannelCompatibility =
                    normalizedPreferences.channelCompatibilityEnabled,
                usbExclusiveForegroundBufferMs = normalizedPreferences.foregroundBufferMs,
                usbExclusiveBackgroundBufferMs = normalizedPreferences.backgroundBufferMs,
                usbExclusiveVolumeRiskThresholdDbfs =
                    normalizedPreferences.volumeRiskThresholdDbfs
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
