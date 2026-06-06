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
    val titleRes: String = "",
    val descriptionRes: String = "",
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
    val titleRes: String = "",
    val descriptionRes: String = "",
    val order: Int = 0
)

data class AutoSettingEntry(
    val iconRes: Int = 0,
    val icon: AutoSettingIcon = AutoSettingIcon.None
)

fun autoSetting(
    iconRes: Int = 0,
    icon: AutoSettingIcon = AutoSettingIcon.None
): AutoSettingEntry {
    return AutoSettingEntry(
        iconRes = iconRes,
        icon = icon
    )
}

enum class AutoSettingIcon {
    None,
    AdsClick,
    Analytics,
    AutoAwesome,
    BlurOn,
    Brightness4,
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
