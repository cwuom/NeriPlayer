package moe.ouom.neriplayer.util

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.util/LanguageManager
 * Updated: 2026/3/23
 */


import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
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
        prefs.edit {
            putString(KEY_LANGUAGE, language.code)
        }
        cachedAppContext = null
        cachedAppLocale = null
    }

    private var cachedAppContext: Context? = null
    private var cachedAppLocale: Locale? = null

    /**
     * 应用语言设置到 Context
     * Apply language setting to Context
     */
    fun applyLanguage(context: Context): Context {
        val language = getCurrentLanguage(context)
        val locale = when (language) {
            Language.CHINESE -> Locale.forLanguageTag("zh")
            Language.ENGLISH -> Locale.forLanguageTag("en")
            Language.SYSTEM -> context.resources.configuration.locales[0]
        }

        val isAppContext = context.applicationContext === context
        if (isAppContext && cachedAppContext != null && cachedAppLocale == locale) {
            if (Locale.getDefault() != locale) {
                Locale.setDefault(locale)
            }
            return cachedAppContext!!
        }

        val currentLocale = context.resources.configuration.locales[0]

        if (currentLocale == locale && Locale.getDefault() == locale) {
            return context
        }

        if (Locale.getDefault() != locale) {
            Locale.setDefault(locale)
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        val newContext = context.createConfigurationContext(config)
        if (isAppContext) {
            cachedAppContext = newContext
            cachedAppLocale = locale
        }
        return newContext
    }

    /**
     * 重启 Activity
     * Restart Activity
     */
    fun restartActivity(activity: Activity) {
        activity.recreate()
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
                else -> context.getString(R.string.language_english)
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
    LanguageManager.Language.CHINESE -> context.getString(R.string.language_display_chinese)
    LanguageManager.Language.ENGLISH -> context.getString(R.string.language_display_english)
    LanguageManager.Language.SYSTEM -> context.getString(R.string.language_display_system)
}
