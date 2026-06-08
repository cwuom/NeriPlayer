package moe.ouom.neriplayer.ksp.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoSettingsCatalog(
    val packageName: String = "moe.ouom.neriplayer.data.settings.generated",
    val settingsKeysPackageName: String = "moe.ouom.neriplayer.data.settings"
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoSetting(
    val key: String,
    val type: SettingValueType,
    val defaultBoolean: Boolean = false,
    val defaultFloat: Float = 0f,
    val defaultInt: Int = 0,
    val defaultLong: Long = 0L,
    val defaultString: String = "",
    val section: String = "",
    val order: Int = 0,
    val ui: SettingUiType = SettingUiType.None,
    val access: SettingAccessMode = SettingAccessMode.ReadWrite,
    val constantName: String = "",
    val exportable: Boolean = true,
    val repositoryName: String = "",
    val normalizer: KClass<*> = Unit::class
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoSettingsSection(
    val key: String = "",
    val order: Int = 0
)

data class AutoSettingEntry(
    val titleRes: Int = 0,
    val descriptionRes: Int = 0,
    val iconRes: Int = 0,
    val icon: AutoSettingIcon = AutoSettingIcon.None
)

data class AutoSettingsSectionEntry(
    val titleRes: Int = 0,
    val descriptionRes: Int = 0
)

fun autoSetting(
    titleRes: Int = 0,
    descriptionRes: Int = 0,
    iconRes: Int = 0,
    icon: AutoSettingIcon = AutoSettingIcon.None
): AutoSettingEntry {
    return AutoSettingEntry(
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        iconRes = iconRes,
        icon = icon
    )
}

fun autoSettingsSection(
    titleRes: Int = 0,
    descriptionRes: Int = 0
): AutoSettingsSectionEntry {
    return AutoSettingsSectionEntry(
        titleRes = titleRes,
        descriptionRes = descriptionRes
    )
}

enum class AutoSettingIcon {
    None,
    AdsClick,
    Analytics,
    AutoAwesome,
    BlurOn,
    Brightness4,
    Colorize,
    Download,
    Error,
    Home,
    Info,
    Keyboard,
    LibraryMusic,
    Settings,
    Subtitles,
    Tune,
    Wallpaper
}

enum class SettingValueType {
    Boolean,
    Float,
    Int,
    Long,
    String
}

enum class SettingUiType {
    None,
    Switch,
    Custom
}

enum class SettingAccessMode {
    KeyOnly,
    ReadWrite
}
