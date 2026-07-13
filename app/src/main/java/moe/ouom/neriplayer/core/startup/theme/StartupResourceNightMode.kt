package moe.ouom.neriplayer.core.startup.theme

import android.content.res.Configuration

internal object StartupResourceNightMode {
    fun isDark(uiMode: Int): Boolean {
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
