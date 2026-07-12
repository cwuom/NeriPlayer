package moe.ouom.neriplayer.util

import android.app.Activity
import android.content.Context
import moe.ouom.neriplayer.R

object LanguageManager {
    enum class Language(val code: String) {
        CHINESE("zh"),
        ENGLISH("en"),
        SYSTEM("")
    }

    fun getCurrentLanguage(context: Context): Language {
        return moe.ouom.neriplayer.util.platform.LanguageManager
            .getCurrentLanguage(context)
            .toRootLanguage()
    }

    fun setLanguage(context: Context, language: Language) {
        moe.ouom.neriplayer.util.platform.LanguageManager.setLanguage(
            context = context,
            language = language.toPlatformLanguage()
        )
    }

    fun applyLanguage(context: Context): Context {
        return moe.ouom.neriplayer.util.platform.LanguageManager.applyLanguage(context)
    }

    fun restartActivity(activity: Activity) {
        moe.ouom.neriplayer.util.platform.LanguageManager.restartActivity(activity)
    }

    fun init(context: Context) {
        moe.ouom.neriplayer.util.platform.LanguageManager.init(context)
    }
}

fun LanguageManager.Language.getDisplayName(context: Context): String = when (this) {
    LanguageManager.Language.CHINESE -> context.getString(R.string.language_display_chinese)
    LanguageManager.Language.ENGLISH -> context.getString(R.string.language_display_english)
    LanguageManager.Language.SYSTEM -> context.getString(R.string.language_display_system)
}

private fun LanguageManager.Language.toPlatformLanguage(): moe.ouom.neriplayer.util.platform.LanguageManager.Language {
    return when (this) {
        LanguageManager.Language.CHINESE -> moe.ouom.neriplayer.util.platform.LanguageManager.Language.CHINESE
        LanguageManager.Language.ENGLISH -> moe.ouom.neriplayer.util.platform.LanguageManager.Language.ENGLISH
        LanguageManager.Language.SYSTEM -> moe.ouom.neriplayer.util.platform.LanguageManager.Language.SYSTEM
    }
}

private fun moe.ouom.neriplayer.util.platform.LanguageManager.Language.toRootLanguage(): LanguageManager.Language {
    return when (this) {
        moe.ouom.neriplayer.util.platform.LanguageManager.Language.CHINESE -> LanguageManager.Language.CHINESE
        moe.ouom.neriplayer.util.platform.LanguageManager.Language.ENGLISH -> LanguageManager.Language.ENGLISH
        moe.ouom.neriplayer.util.platform.LanguageManager.Language.SYSTEM -> LanguageManager.Language.SYSTEM
    }
}
