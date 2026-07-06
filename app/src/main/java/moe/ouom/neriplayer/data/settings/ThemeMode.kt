package moe.ouom.neriplayer.data.settings

enum class ThemeMode(
    val followSystemDark: Boolean,
    val forceDark: Boolean
) {
    LIGHT(
        followSystemDark = false,
        forceDark = false
    ),
    DARK(
        followSystemDark = false,
        forceDark = true
    ),
    AUTO(
        followSystemDark = true,
        forceDark = false
    );

    fun resolveUseDark(systemDark: Boolean): Boolean {
        return when (this) {
            LIGHT -> false
            DARK -> true
            AUTO -> systemDark
        }
    }

    companion object {
        fun fromPreferenceFlags(
            forceDark: Boolean,
            followSystemDark: Boolean
        ): ThemeMode {
            return when {
                forceDark -> DARK
                followSystemDark -> AUTO
                else -> LIGHT
            }
        }
    }
}
