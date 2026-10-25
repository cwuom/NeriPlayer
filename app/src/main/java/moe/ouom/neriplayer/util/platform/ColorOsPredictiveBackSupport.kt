package moe.ouom.neriplayer.util.platform

import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val ROM_PROPERTY_TIMEOUT_MILLIS = 250L
private const val COLOR_OS_DETECTION_ATTEMPTS = 3
private const val COLOR_OS_DETECTION_RETRY_MILLIS = 500L
private val colorOsPredictiveBackDetector = ColorOsPredictiveBackDetector {
    runInterruptible(Dispatchers.IO) { readColorOsPredictiveBackSupport() }
}

private val colorOsName = Regex(
    "(?:^|[^a-z])coloros(?:[^a-z]|$)",
    RegexOption.IGNORE_CASE
)
private val vendorRomVersion = Regex(
    "v?\\d+(?:\\.\\d+)*(?:[-_][a-z0-9.]+)?",
    RegexOption.IGNORE_CASE
)
private val chineseOtaRegion = Regex(
    "(?:^|[^a-z0-9])cn\\d{2}(?:[^a-z0-9]|$)",
    RegexOption.IGNORE_CASE
)
private val internationalOtaRegion = Regex(
    "(?:^|[^a-z0-9])(?:ex|in|eu|na)\\d{2}(?:[^a-z0-9]|$)",
    RegexOption.IGNORE_CASE
)

internal suspend fun detectColorOsPredictiveBackSupport(): Boolean? {
    return colorOsPredictiveBackDetector.detect()
}

internal class ColorOsPredictiveBackDetector(private val readSupport: suspend () -> Boolean?) {
    private val detectionMutex = Mutex()

    @Volatile
    private var cachedSupport: Boolean? = null

    suspend fun detect(): Boolean? {
        currentCoroutineContext().ensureActive()
        cachedSupport?.let { return it }
        return detectionMutex.withLock {
            cachedSupport?.let { return@withLock it }
            repeat(COLOR_OS_DETECTION_ATTEMPTS) { attempt ->
                currentCoroutineContext().ensureActive()
                val support = readSupport()
                currentCoroutineContext().ensureActive()
                if (support != null) {
                    cachedSupport = support
                    return@withLock support
                }
                // 单批检测只短暂重试，后续恢复由前台生命周期触发
                if (attempt < COLOR_OS_DETECTION_ATTEMPTS - 1) {
                    delay(COLOR_OS_DETECTION_RETRY_MILLIS)
                }
            }
            null
        }
    }
}

internal fun supportsColorOsPredictiveBack(
    manufacturer: String?,
    brand: String?,
    oppoRom: String? = null,
    oplusRom: String? = null,
    oxygenVersion: String? = null,
    otaVersion: String? = null
): Boolean {
    if (!hasEligibleColorOsVendor(manufacturer, brand) || !oxygenVersion.isNullOrBlank()) {
        return false
    }
    val romFields = listOfNotNull(oppoRom, oplusRom, otaVersion).map(String::trim)
    if (romFields.any(::hasConflictingRomMarker)) {
        return false
    }
    val hasColorOsName = romFields.any(colorOsName::containsMatchIn)
    val hasVendorRom = listOfNotNull(oppoRom, oplusRom).any { vendorRomVersion.matches(it.trim()) }
    val isOnePlus = listOf(manufacturer, brand).any {
        it?.trim()?.equals("oneplus", ignoreCase = true) == true
    }
    return if (isOnePlus) {
        supportsOnePlusColorOs(hasColorOsName, hasVendorRom, otaVersion.orEmpty().trim())
    } else {
        hasColorOsName || hasVendorRom
    }
}

private fun supportsOnePlusColorOs(
    hasColorOsName: Boolean,
    hasVendorRom: Boolean,
    otaVersion: String
): Boolean {
    if (internationalOtaRegion.containsMatchIn(otaVersion)) {
        return false
    }
    return hasColorOsName || hasVendorRom && chineseOtaRegion.containsMatchIn(otaVersion)
}

private fun hasConflictingRomMarker(value: String): Boolean {
    return value.contains("oxygen", ignoreCase = true) || value.contains("realme", ignoreCase = true)
}

private fun hasEligibleColorOsVendor(manufacturer: String?, brand: String?): Boolean {
    val identities = listOfNotNull(manufacturer, brand).map(String::trim).filter(String::isNotEmpty)
    return identities.isNotEmpty() && identities.all {
        it.equals("oppo", ignoreCase = true) || it.equals("oneplus", ignoreCase = true)
    }
}

private fun readColorOsPredictiveBackSupport(): Boolean? {
    val manufacturer = Build.MANUFACTURER
    val brand = Build.BRAND
    if (!hasEligibleColorOsVendor(manufacturer, brand)) {
        return false
    }
    // 空属性可用于判断 ROM，读取失败则保留未知状态供重试
    val oxygenVersion = readRomProperty("ro.oxygen.version") ?: return null
    if (oxygenVersion.isNotBlank()) {
        return false
    }
    val oppoRom = readRomProperty("ro.build.version.opporom") ?: return null
    val oplusRom = readRomProperty("ro.build.version.oplusrom") ?: return null
    val otaVersion = readRomProperty("ro.build.version.ota") ?: return null
    return supportsColorOsPredictiveBack(manufacturer, brand, oppoRom, oplusRom, oxygenVersion, otaVersion)
}

private fun readRomProperty(name: String): String? {
    var process: Process? = null
    return try {
        process = ProcessBuilder("/system/bin/getprop", name).start()
        process.outputStream.close()
        if (!process.waitFor(ROM_PROPERTY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
            null
        } else {
            process.inputStream.bufferedReader().use { it.readLine().orEmpty().trim() }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } finally {
        process?.closeRomPropertyProcess()
    }
}

private fun Process.closeRomPropertyProcess() {
    if (isAlive) {
        destroyForcibly()
    }
    outputStream.closeRomPropertyStream()
    inputStream.closeRomPropertyStream()
    errorStream.closeRomPropertyStream()
}

private fun Closeable.closeRomPropertyStream() {
    try {
        close()
    } catch (_: IOException) {
        // 子进程退出时管道可能已关闭，其余流仍需独立释放
    }
}
