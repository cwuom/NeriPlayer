package moe.ouom.neriplayer.core.api.kugou

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.api.kugou/KugouClientWrapper
 * Created: 2025/07/05
 */

import android.app.ActivityManager
import android.content.Context
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.auth.kugou.KugouCookieRepository
import moe.ouom.neriplayer.util.NPLogger
import top.ghhccghk.multiplatform.kugouapi.KuGouClient
import top.ghhccghk.multiplatform.kugouapi.KuGouConfig
import top.ghhccghk.multiplatform.kugouapi.core.KuGouResponse

private const val TAG = "KugouClientWrapper"

/**
 * Thin wrapper around [KuGouClient] that:
 *
 * 1. **Seeds** the underlying SDK CookieJar from [KugouCookieRepository] on construction,
 *    so that the very first API call carries the stored auth cookies.
 * 2. **After every** API call, snapshots the SDK CookieJar and pushes any
 *    new / changed cookies back into [KugouCookieRepository] via [KugouCookieRepository.updateCookies],
 *    which persists them to EncryptedSharedPreferences.
 *
 * This follows the same pattern as [moe.ouom.neriplayer.core.api.netease.NeteaseClient],
 * where `setPersistedCookies()` seeds the in-memory CookieJar and `getCookies()`
 * reads it back for persistence.
 *
 * **All sub-APIs** (`search`, `song`, `auth`, etc.) are exposed as direct delegates,
 * so call-sites write `wrapper.search.search(...)` exactly as they would with the raw SDK.
 */
class KugouClientWrapper(
    private val context: Context,
    private val cookieRepo: KugouCookieRepository,
    private val config: KuGouConfig = KuGouConfig()
) {
    // ── The underlying SDK client ──────────────────────────────────
    private val sdk: KuGouClient = KuGouClient(config = config)

    /** Snapshot of the last cookie state we persisted; used to detect changes. */
    @Volatile
    private var lastSnapshot: Map<String, String> = emptyMap()

    init {
        // Seed the SDK's internal CookieJar from persisted state.
        seedFromRepository()
    }

    // ── Sub-API delegates ──────────────────────────────────────────
    // Every call-site writes `wrapper.search.search(...)` etc.
    val auth get() = sdk.auth
    val search get() = sdk.search
    val album get() = sdk.album
    val artist get() = sdk.artist
    val playlist get() = sdk.playlist
    val song get() = sdk.song
    val comment get() = sdk.comment
    val image get() = sdk.image
    val longAudio get() = sdk.longAudio
    val rank get() = sdk.rank
    val sceneMusic get() = sdk.sceneMusic
    val misc get() = sdk.misc
    val user get() = sdk.user
    val video get() = sdk.video
    val radio get() = sdk.radio
    val recommend get() = sdk.recommend
    val sheet get() = sdk.sheet
    val audioMatch get() = sdk.audioMatch
    val yueku get() = sdk.yueku
    val youth get() = sdk.youth
    val top get() = sdk.top

    // ── Cookie synchronization ─────────────────────────────────────

    /**
     * Seeds the SDK's CookieJar from the persisted repository.
     * Called once at construction and again whenever the repository's
     * `cookieFlow` emits (see [AppContainer.startCookieObserver]).
     */
    fun seedFromRepository() {
        val persisted = cookieRepo.loadCookies()
        persisted.forEach { (k, v) ->
            sdk.cookieJar[k] = v
        }
        lastSnapshot = persisted
        NPLogger.d(TAG, "Seeded ${persisted.size} cookies from repository.")
        if (sdk.cookieJar.getDfid() == "-") {
            NPLogger.d(TAG, "dfid is unset, registering device...")
            try {
                val deviceInfo = gatherDeviceInfo()
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    sdk.auth.registerDev(
                        availableRamSize = deviceInfo.availableRamSize,
                        availableRomSize = deviceInfo.availableRomSize,
                        availableSDSize = deviceInfo.availableSDSize,
                        batteryLevel = deviceInfo.batteryLevel,
                        batteryStatus = deviceInfo.batteryStatus,
                        brand = Build.BRAND,
                        buildSerial = Build.SERIAL,
                        device = Build.DEVICE,
                        manufacturer = Build.MANUFACTURER
                    )
                }
                syncCookiesToRepository()
                NPLogger.d(TAG, "Device registered, dfid=${sdk.cookieJar.getDfid()}")
            } catch (e: Exception) {
                NPLogger.w(TAG, "Failed to register device: ${e.message}")
            }
        }
    }

    /**
     * Snapshots the SDK's CookieJar and persists any changes back to
     * [KugouCookieRepository].
     *
     * This is called automatically after every API call via [invokeWithCookieSync].
     * Business code never needs to call this directly.
     */
    fun syncCookiesToRepository() {
        val current = sdk.cookieJar.getAll()
        if (current != lastSnapshot) {
            cookieRepo.updateCookies(current)
            lastSnapshot = current
        }
    }

    /**
     * Wraps any suspend API call: executes [block], then auto-syncs cookies.
     */
    private suspend fun <T> invokeWithCookieSync(block: suspend () -> T): T {
        val result = block()
        syncCookiesToRepository()
        return result
    }

    // ── Higher-level wrappers for frequently used APIs ─────────────
    // These ensure cookie sync happens even when called directly
    // (as opposed to through the raw sub-API delegates above).

    /**
     * Convenience search that auto-syncs cookies.
     */
    suspend fun searchSongs(
        keywords: String,
        page: Int = 1,
        pageSize: Int = 30
    ): KuGouResponse = withContext(Dispatchers.IO) {
        invokeWithCookieSync {
            sdk.search.search(
                keywords = keywords,
                page = page,
                pageSize = pageSize
            )
        }
    }

    /**
     * Convenience song info that auto-syncs cookies.
     */
    suspend fun getSongInfo(hash: String): KuGouResponse = withContext(Dispatchers.IO) {
        invokeWithCookieSync {
            sdk.song.getAudioInfo(hash)
        }
    }

    /**
     * Convenience URL resolve that auto-syncs cookies.
     */
    suspend fun getSongUrl(
        hash: String,
        quality: String = "128"
    ): KuGouResponse = withContext(Dispatchers.IO) {
        invokeWithCookieSync {
            sdk.song.getSongUrl(hash = hash, quality = quality)
        }
    }

    /**
     * Convenience QR key creation that auto-syncs cookies.
     */
    suspend fun createQrKey(): KuGouResponse = withContext(Dispatchers.IO) {
        invokeWithCookieSync {
            sdk.auth.createQrKey()
        }
    }

    /**
     * Convenience QR status check that auto-syncs cookies.
     */
    suspend fun checkQrCode(key: String): KuGouResponse = withContext(Dispatchers.IO) {
        invokeWithCookieSync {
            sdk.auth.checkQrCode(key)
        }
    }

    /**
     * Convenience QR code URL generation (local, no network).
     */
    fun createQrCodeUrl(key: String): String = sdk.auth.createQrCodeUrl(key)

    /**
     * Convenience lyric search that auto-syncs cookies.
     */
    suspend fun searchLyric(hash: String): KuGouResponse = withContext(Dispatchers.IO) {
        invokeWithCookieSync {
            sdk.search.searchLyric(hash = hash)
        }
    }

    /**
     * Convenience lyric download that auto-syncs cookies.
     */
    suspend fun getLyric(
        id: String,
        accessKey: String,
        decode: Boolean = true,
        fmt: String = "lrc"
    ): KuGouResponse = withContext(Dispatchers.IO) {
        invokeWithCookieSync {
            sdk.song.getLyric(id = id, accessKey = accessKey, decode = decode, fmt = fmt)
        }
    }

    /**
     * Convenience privilege-lite that auto-syncs cookies.
     */
    suspend fun getPrivilegeLite(hash: String): KuGouResponse = withContext(Dispatchers.IO) {
        invokeWithCookieSync {
            sdk.song.getPrivilegeLite(hash)
        }
    }

    /**
     * Convenience device registration that auto-syncs cookies.
     */
    suspend fun registerDev(): KuGouResponse = withContext(Dispatchers.IO) {
        invokeWithCookieSync {
            sdk.auth.registerDev()
        }
    }

    /**
     * Check whether the SDK CookieJar contains a valid login.
     */
    fun isLoggedIn(): Boolean = sdk.cookieJar.isLoggedIn()

    /**
     * Returns the current cookie snapshot from the SDK CookieJar.
     */
    fun getCookies(): Map<String, String> = sdk.cookieJar.getAll()

    // ── Device info gathering ──────────────────────────────────────

    private data class DeviceInfo(
        val availableRamSize: Long,
        val availableRomSize: Long,
        val availableSDSize: Long,
        val batteryLevel: Int,
        val batteryStatus: Int
    )

    private fun gatherDeviceInfo(): DeviceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val availableRam = memInfo.availMem

        val availableRom = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        }.getOrDefault(0L)

        val availableSD = runCatching {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        }.getOrDefault(0L)

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CAPACITY
        ) ?: 100

        // Battery status: 2 = charging, 3 = discharging
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val isCharging = bm?.isCharging == true
        val batteryStatus = if (isCharging) 2 else 3

        return DeviceInfo(
            availableRamSize = availableRam,
            availableRomSize = availableRom,
            availableSDSize = availableSD,
            batteryLevel = batteryLevel,
            batteryStatus = batteryStatus
        )
    }
}
