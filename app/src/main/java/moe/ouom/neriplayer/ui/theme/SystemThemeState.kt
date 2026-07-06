package moe.ouom.neriplayer.ui.theme

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

fun isActualSystemDarkTheme(context: Context): Boolean {
    val applicationContext = context.applicationContext
    val uiModeManager = ContextCompat.getSystemService(applicationContext, UiModeManager::class.java)
    when (uiModeManager?.nightMode) {
        UiModeManager.MODE_NIGHT_YES -> return true
        UiModeManager.MODE_NIGHT_NO -> return false
        UiModeManager.MODE_NIGHT_AUTO,
        UiModeManager.MODE_NIGHT_CUSTOM,
        null -> Unit
    }

    val appNightMode = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    if (appNightMode == Configuration.UI_MODE_NIGHT_YES) {
        return true
    }
    if (appNightMode == Configuration.UI_MODE_NIGHT_NO) {
        return false
    }

    return (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
}

@Composable
fun rememberActualSystemDarkTheme(): Boolean {
    val context = LocalContext.current
    val applicationContext = remember(context) { context.applicationContext }
    var systemDark by remember(applicationContext) {
        mutableStateOf(isActualSystemDarkTheme(applicationContext))
    }

    DisposableEffect(applicationContext) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                systemDark = isActualSystemDarkTheme(applicationContext)
            }
        }
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            runCatching {
                applicationContext.unregisterReceiver(receiver)
            }
        }
    }

    return systemDark
}
