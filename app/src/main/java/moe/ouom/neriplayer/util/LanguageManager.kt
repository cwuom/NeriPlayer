package moe.ouom.neriplayer.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import moe.ouom.neriplayer.R
import java.util.Locale

/**
 * 语言管理工具类
 * Language management utility
 */
object LanguageManager {

    private const val PREF_NAME = "language_settings"
    private const val KEY_LANGUAGE = "selected_language"

    /**
     * 支持的语言
     * Supported languages
     */
    enum class Language(val code: String) {
        CHINESE("zh"),
        ENGLISH("en"),
        SYSTEM("")
    }

    /**
     * 获取当前设置的语言
     * Get current language setting
     */
    fun getCurrentLanguage(context: Context): Language {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LANGUAGE, "") ?: ""
        return Language.entries.find { it.code == code } ?: Language.SYSTEM
    }

    /**
     * 设置语言
     * Set language
     */
    fun setLanguage(context: Context, language: Language) {
        // 保存设置
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    /**
     * 应用语言设置到 Context
     * Apply language setting to Context
     */
    fun applyLanguage(context: Context): Context {
        val language = getCurrentLanguage(context)
        val locale = when (language) {
            Language.CHINESE -> Locale("zh")
            Language.ENGLISH -> Locale("en")
            Language.SYSTEM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.resources.configuration.locales[0]
                } else {
                    @Suppress("DEPRECATION")
                    Locale.getDefault()
                }
            }
        }

        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return context.createConfigurationContext(config)
    }

    /**
     * 重启 Activity
     * Restart Activity
     */
    fun restartActivity(activity: Activity) {
        val intent = activity.intent
        activity.finish()
        activity.startActivity(intent)
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * 初始化语言设置（在Application中调用）
     * Initialize language setting (call in Application)
     */
    fun init(context: Context) {
        applyLanguage(context)
    }

    /**
     * 获取当前显示的语言（考虑系统语言）
     * Get current display language (considering system language)
     */
    fun getCurrentDisplayLanguage(context: Context): String {
        val currentLanguage = getCurrentLanguage(context)
        return if (currentLanguage == Language.SYSTEM) {
            val systemLocale =
                context.resources.configuration.locales[0]
            when {
                systemLocale.language.startsWith("zh") -> context.getString(R.string.language_simplified_chinese)
                else -> "English"
            }
        } else {
            currentLanguage.getDisplayName(context)
        }
    }
}

/**
 * 获取语言的显示名称
 * Get display name of language
 */
fun LanguageManager.Language.getDisplayName(context: Context): String = when (this) {
    LanguageManager.Language.CHINESE -> context.getString(R.string.language_simplified_chinese)
    LanguageManager.Language.ENGLISH -> "English"
    LanguageManager.Language.SYSTEM -> context.getString(R.string.language_follow_system)
}
