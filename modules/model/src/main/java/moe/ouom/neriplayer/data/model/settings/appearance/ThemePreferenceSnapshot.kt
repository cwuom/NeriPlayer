package moe.ouom.neriplayer.data.model.settings.appearance

data class ThemePreferenceSnapshot(
    val dynamicColor: Boolean = true,
    val forceDark: Boolean = false,
    val followSystemDark: Boolean = true
) {
    fun resolveUseDark(systemDark: Boolean): Boolean {
        return when {
            forceDark -> true
            followSystemDark -> systemDark
            else -> false
        }
    }
}
