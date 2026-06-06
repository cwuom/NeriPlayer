package com.mocharealm.accompanist.lyrics.ui.utils

import android.os.Build

private val cjkBlocks: Set<Character.UnicodeBlock> by lazy {
    mutableSetOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E)
            add(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F)
            add(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            add(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_H)
        }
    }
}

private val arabicBlocks: Set<Character.UnicodeBlock> by lazy {
    mutableSetOf(
        Character.UnicodeBlock.ARABIC,
        Character.UnicodeBlock.ARABIC_SUPPLEMENT,
        Character.UnicodeBlock.ARABIC_EXTENDED_A,
        Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A,
        Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            add(Character.UnicodeBlock.ARABIC_EXTENDED_B)
        }
    }
}

private val devanagariBlocks: Set<Character.UnicodeBlock> by lazy {
    setOf(
        Character.UnicodeBlock.DEVANAGARI,
        Character.UnicodeBlock.DEVANAGARI_EXTENDED
    )
}

fun Char.isCjk(): Boolean {
    val block = Character.UnicodeBlock.of(this) ?: return false
    return block in cjkBlocks
}

fun Char.isJapanese(): Boolean {
    return this.code in 0x3040..0x309F ||
        this.code in 0x30A0..0x30FF ||
        this.code in 0xFF66..0xFF9F
}

fun Char.isKorean(): Boolean {
    return this.code in 0xAC00..0xD7AF || this.code in 0x1100..0x11FF
}

fun Char.isArabic(): Boolean {
    val block = Character.UnicodeBlock.of(this) ?: return false
    return block in arabicBlocks
}

fun Char.isDevanagari(): Boolean {
    val block = Character.UnicodeBlock.of(this) ?: return false
    return block in devanagariBlocks
}

fun String.isPureCjk(): Boolean {
    val cleaned = filter { it != ' ' && it != ',' && it != '\n' && it != '\r' }
    if (cleaned.isEmpty()) {
        return false
    }
    return cleaned.all { it.isCjk() }
}

fun String.containsJapanese(): Boolean = any { it.isJapanese() }

fun String.containsKorean(): Boolean = any { it.isKorean() }

fun String.isRtl(): Boolean = any { it.isArabic() }

fun String.isPunctuation(): Boolean {
    return isNotEmpty() && all { char ->
        char.isWhitespace() ||
            char in ".,!?;:\"'()[]{}…—–-、。，！？；：\"\"''（）【】《》～·" ||
            Character.getType(char) in setOf(
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt()
            )
    }
}
