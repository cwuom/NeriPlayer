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
    val key: String = "",
    val type: SettingValueType = SettingValueType.Boolean,
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
    val descriptionRes: Int = 0,
    val iconRes: Int = 0,
    val icon: AutoSettingIcon = AutoSettingIcon.None
)

data class AutoSettingSpec<T>(
    val key: String,
    val type: SettingValueType,
    val defaultValue: T,
    val titleRes: Int = 0,
    val descriptionRes: Int = 0,
    val iconRes: Int = 0,
    val icon: AutoSettingIcon = AutoSettingIcon.None
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

fun autoBooleanSetting(
    key: String,
    defaultValue: Boolean = false,
    titleRes: Int = 0,
    descriptionRes: Int = 0,
    iconRes: Int = 0,
    icon: AutoSettingIcon = AutoSettingIcon.None
): AutoSettingSpec<Boolean> {
    return AutoSettingSpec(
        key = key,
        type = SettingValueType.Boolean,
        defaultValue = defaultValue,
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        iconRes = iconRes,
        icon = icon
    )
}

fun autoSwitchSetting(
    key: String,
    defaultValue: Boolean = false,
    titleRes: Int = 0,
    descriptionRes: Int = 0,
    iconRes: Int = 0,
    icon: AutoSettingIcon = AutoSettingIcon.None
): AutoSettingSpec<Boolean> {
    return autoBooleanSetting(
        key = key,
        defaultValue = defaultValue,
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        iconRes = iconRes,
        icon = icon
    )
}

fun autoFloatSetting(
    key: String,
    defaultValue: Float = 0f,
    titleRes: Int = 0,
    descriptionRes: Int = 0,
    iconRes: Int = 0,
    icon: AutoSettingIcon = AutoSettingIcon.None
): AutoSettingSpec<Float> {
    return AutoSettingSpec(
        key = key,
        type = SettingValueType.Float,
        defaultValue = defaultValue,
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        iconRes = iconRes,
        icon = icon
    )
}

fun autoIntSetting(
    key: String,
    defaultValue: Int = 0,
    titleRes: Int = 0,
    descriptionRes: Int = 0,
    iconRes: Int = 0,
    icon: AutoSettingIcon = AutoSettingIcon.None
): AutoSettingSpec<Int> {
    return AutoSettingSpec(
        key = key,
        type = SettingValueType.Int,
        defaultValue = defaultValue,
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        iconRes = iconRes,
        icon = icon
    )
}

fun autoLongSetting(
    key: String,
    defaultValue: Long = 0L,
    titleRes: Int = 0,
    descriptionRes: Int = 0,
    iconRes: Int = 0,
    icon: AutoSettingIcon = AutoSettingIcon.None
): AutoSettingSpec<Long> {
    return AutoSettingSpec(
        key = key,
        type = SettingValueType.Long,
        defaultValue = defaultValue,
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        iconRes = iconRes,
        icon = icon
    )
}

fun autoStringSetting(
    key: String,
    defaultValue: String = "",
    titleRes: Int = 0,
    descriptionRes: Int = 0,
    iconRes: Int = 0,
    icon: AutoSettingIcon = AutoSettingIcon.None
): AutoSettingSpec<String> {
    return AutoSettingSpec(
        key = key,
        type = SettingValueType.String,
        defaultValue = defaultValue,
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        iconRes = iconRes,
        icon = icon
    )
}

fun autoSettingsSection(
    titleRes: Int = 0,
    descriptionRes: Int = 0,
    iconRes: Int = 0,
    icon: AutoSettingIcon = AutoSettingIcon.None
): AutoSettingsSectionEntry {
    return AutoSettingsSectionEntry(
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        iconRes = iconRes,
        icon = icon
    )
}

enum class AutoSettingIcon {
    None,
    AccountCircle,
    AdsClick,
    Analytics,
    Audiotrack,
    AutoAwesome,
    BlurOn,
    BluetoothAudio,
    Bolt,
    Brightness4,
    Cloud,
    Colorize,
    Download,
    Error,
    Home,
    Info,
    Keyboard,
    Layers,
    LibraryMusic,
    Palette,
    PlaylistPlay,
    Router,
    Settings,
    Storage,
    Subtitles,
    Sync,
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
