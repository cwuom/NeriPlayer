package moe.ouom.neriplayer.data.config

import android.content.Context
import androidx.annotation.StringRes
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.config.AppConfigImportWarning

internal class ConfigImportWarnings(private val context: Context) : ArrayList<String>() {
    val codes = linkedMapOf<Int, AppConfigImportWarning>()

    fun addWarning(code: AppConfigImportWarning) {
        codes[size] = code
        add(context.getString(code.resourceId()))
    }
}

internal fun MutableList<String>.addConfigImportWarning(context: Context, code: AppConfigImportWarning) {
    if (this is ConfigImportWarnings) {
        addWarning(code)
    } else {
        add(context.getString(code.resourceId()))
    }
}

@StringRes
private fun AppConfigImportWarning.resourceId(): Int = when (this) {
    AppConfigImportWarning.INVALID_SETTING_VALUES -> CoreCommonR.string.config_import_warning_invalid_setting_values
    AppConfigImportWarning.BACKGROUND_IMAGE -> CoreCommonR.string.config_import_warning_background_image
    AppConfigImportWarning.DOWNLOAD_DIRECTORY -> CoreCommonR.string.config_import_warning_download_directory
    AppConfigImportWarning.NETEASE_COOKIE -> CoreCommonR.string.config_import_warning_netease_cookie
}
