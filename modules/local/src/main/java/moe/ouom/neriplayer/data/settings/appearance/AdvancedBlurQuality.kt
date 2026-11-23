package moe.ouom.neriplayer.data.settings.appearance

import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.Locale

const val DEFAULT_ADVANCED_BLUR_QUALITY = "default"

object AdvancedBlurQualityPreference {
    fun normalize(value: String): String = fromStorage(value).storageValue

    fun defaultForDevice(
        isDimensityDevice: Boolean,
        isEntryLevelDevice: Boolean = false
    ): AdvancedBlurQuality = if (isDimensityDevice || isEntryLevelDevice) {
        AdvancedBlurQuality.UltraLow
    } else {
        AdvancedBlurQuality.Default
    }

    fun defaultForCurrentDevice(context: Context): AdvancedBlurQuality =
        defaultForDevice(isCurrentBuildDimensity(), isCurrentDeviceEntryLevelBlurDevice(context))

    fun resolve(
        value: String?,
        isDimensityDevice: Boolean,
        isEntryLevelDevice: Boolean = false
    ): AdvancedBlurQuality {
        return value?.let(::fromStorage)
            ?: defaultForDevice(isDimensityDevice, isEntryLevelDevice)
    }

    fun fromStorage(value: String): AdvancedBlurQuality = when (
        value.trim().lowercase(Locale.ROOT)
    ) {
        AdvancedBlurQuality.UltraLow.storageValue -> AdvancedBlurQuality.UltraLow
        AdvancedBlurQuality.Low.storageValue -> AdvancedBlurQuality.Low
        AdvancedBlurQuality.High.storageValue -> AdvancedBlurQuality.High
        else -> AdvancedBlurQuality.Default
    }
}

fun AdvancedBlurQuality.canBeSelectedWhen(
    enhancedAdvancedBlurEnabled: Boolean
): Boolean = this != AdvancedBlurQuality.High || enhancedAdvancedBlurEnabled

fun isDimensityDevice(
    socManufacturer: String?,
    socModel: String?,
    hardware: String?,
    board: String?
): Boolean {
    val identifiers = listOfNotNull(socManufacturer, socModel, hardware, board).map { identifier ->
        identifier.lowercase(Locale.ROOT)
    }
    if (identifiers.any { identifier ->
            DimensityNamePattern.containsMatchIn(identifier) ||
                DimensityModelPattern.containsMatchIn(identifier)
        }
    ) {
        return true
    }
    val hasMediaTekContext = identifiers.any(MediaTekContextPattern::containsMatchIn)
    return hasMediaTekContext && identifiers.any { identifier ->
        DimensityShorthandPattern.containsMatchIn(identifier) ||
            DimensityBoardPattern.containsMatchIn(identifier)
    }
}

// 保留既有透镜兼容策略，避免扩充芯片身份识别后额外关闭透镜
fun usesDimensityBlurCompatibility(
    socManufacturer: String?,
    socModel: String?,
    hardware: String?,
    board: String?
): Boolean {
    val identifiers = listOfNotNull(socManufacturer, socModel, hardware, board).map { identifier ->
        identifier.lowercase(Locale.ROOT)
    }
    if (identifiers.any { identifier ->
            "dimensity" in identifier || LegacyDimensityModelPattern.containsMatchIn(identifier)
        }
    ) {
        return true
    }
    val hasMediaTekContext = identifiers.any { identifier ->
        "mediatek" in identifier
    }
    return hasMediaTekContext && identifiers.any { identifier ->
        LegacyDimensityShorthandPattern.containsMatchIn(identifier)
    }
}

fun isCurrentBuildDimensity(): Boolean = currentBuildMatches(::isDimensityDevice)

// 标称 4GB 的机器扣掉内核与固件预留后约 3.6 GiB, 取 4.5 GiB 把它们都划进来又不误伤 6GB 机型
internal const val ENTRY_LEVEL_MAX_TOTAL_MEMORY_BYTES = 4_831_838_208L
private const val ENTRY_LEVEL_MAX_CPU_CORES = 4

/**
 * 入门级 GPU 扛不住整屏模糊管线, 未手动选择画质时和天玑一样默认走区域模糊
 * 覆盖低内存标记, 4GB 及以下, 四核及以下, 以及联发科 Helio, 紫光展锐, 骁龙 4 系与 6x0 系
 */
fun isEntryLevelBlurDevice(
    socManufacturer: String?,
    socModel: String?,
    hardware: String?,
    board: String?,
    lowRamDevice: Boolean,
    totalMemoryBytes: Long,
    cpuCores: Int
): Boolean {
    if (lowRamDevice) return true
    if (totalMemoryBytes in 1..ENTRY_LEVEL_MAX_TOTAL_MEMORY_BYTES) return true
    if (cpuCores in 1..ENTRY_LEVEL_MAX_CPU_CORES) return true
    return listOfNotNull(socManufacturer, socModel, hardware, board).any { identifier ->
        EntryLevelSocPattern.containsMatchIn(identifier.lowercase(Locale.ROOT))
    }
}

fun isCurrentDeviceEntryLevelBlurDevice(context: Context): Boolean {
    val activityManager = context.applicationContext.getSystemService(ActivityManager::class.java)
    val memoryInfo = ActivityManager.MemoryInfo()
    runCatching { activityManager?.getMemoryInfo(memoryInfo) }
    return currentBuildMatches { socManufacturer, socModel, hardware, board ->
        isEntryLevelBlurDevice(
            socManufacturer = socManufacturer,
            socModel = socModel,
            hardware = hardware,
            board = board,
            lowRamDevice = activityManager?.isLowRamDevice == true,
            totalMemoryBytes = memoryInfo.totalMem,
            cpuCores = Runtime.getRuntime().availableProcessors()
        )
    }
}

fun currentBuildUsesDimensityBlurCompatibility(): Boolean =
    currentBuildMatches(::usesDimensityBlurCompatibility)

private inline fun currentBuildMatches(
    predicate: (String?, String?, String?, String?) -> Boolean
): Boolean = predicate(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MANUFACTURER
    } else {
        null
    },
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL
    } else {
        null
    },
    Build.HARDWARE,
    Build.BOARD
)

private val DimensityNamePattern =
    Regex("(?:^|[^a-z0-9])dimensity(?:[0-9]{3,4}[a-z]*)?(?:$|[^a-z0-9])")
private val DimensityModelPattern =
    Regex("(?:^|[^a-z0-9])mtk?(?:68|69)[0-9]{2}(?:[a-z][a-z0-9]*)?(?:$|[^a-z0-9])")
private val MediaTekContextPattern = Regex("(?:^|[^a-z0-9])(?:mediatek|mtk)(?:$|[^a-z0-9])")
private val DimensityShorthandPattern =
    Regex("(?:^|[^a-z0-9])d(?:[7-9][0-9]{2}|1[0-9]{3}|[6-9][0-9]{3})(?:[a-z][a-z0-9]*)?(?:$|[^a-z0-9])")
private val DimensityBoardPattern =
    Regex("(?:^|[^a-z0-9])k(?:68|69)[0-9]{2}(?:[a-z][a-z0-9]*)?(?:$|[^a-z0-9])")
private val EntryLevelSocPattern = Regex(
    "(?:^|[^a-z0-9])(?:helio|mtk?67[0-9]{2}|unisoc|spreadtrum|ums[0-9]{3,4}|sc98[0-9]{2}|sm4[0-9]{3}|sm6[12][0-9]{2})" +
        "(?:[a-z][a-z0-9]*)?(?:$|[^a-z0-9])"
)
private val LegacyDimensityModelPattern = Regex("(?:^|[^a-z0-9])mt(?:68|69)[0-9a-z]*")
private val LegacyDimensityShorthandPattern = Regex("(?:^|[^a-z0-9])d[6-9][0-9]{3}(?:$|[^a-z0-9])")
