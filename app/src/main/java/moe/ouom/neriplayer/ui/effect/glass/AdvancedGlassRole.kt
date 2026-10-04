package moe.ouom.neriplayer.ui.effect.glass

internal enum class AdvancedGlassRole {
    MiniPlayer,
    BottomNavigation,
    NavigationRail,
    ScreenTopTab,
    SettingsGroup,
    SettingsHeader,
    SettingsSection,
    PlaylistSheet,
    SemanticCard,
    ExploreTag,
    ThemeModeToggle,
    InlineControl
}

internal fun isGlobalAdvancedGlassNavigation(role: AdvancedGlassRole): Boolean =
    role == AdvancedGlassRole.MiniPlayer || role == AdvancedGlassRole.BottomNavigation ||
        role == AdvancedGlassRole.NavigationRail

internal fun advancedGlassRegionNavigationOwner(role: AdvancedGlassRole, navigationOwner: Any?): Any? =
    if (isGlobalAdvancedGlassNavigation(role)) null else navigationOwner
