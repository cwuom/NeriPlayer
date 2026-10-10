package com.mocharealm.accompanist.lyrics.ui.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StringCompatTest {
    @Test
    fun `korean detection covers syllables and jamo only`() {
        assertTrue('가'.isKorean())
        assertTrue('힣'.isKorean())
        assertTrue('ᄀ'.isKorean())
        assertFalse('A'.isKorean())
        assertFalse('中'.isKorean())
        assertFalse('ｱ'.isKorean())
        assertTrue("노래 song".containsKorean())
        assertFalse("song".containsKorean())
    }

    @Test
    fun `pure cjk ignores spaces commas and line breaks`() {
        assertTrue("中文 歌词,\n日本語\r".isPureCjk())
        assertTrue("かなカナ 한국어".isPureCjk())
        assertFalse("中文 lyric".isPureCjk())
        assertFalse(" ,\n\r".isPureCjk())
        assertFalse("".isPureCjk())
    }

    @Test
    fun `punctuation accepts whitespace listed marks and unicode punctuation types`() {
        assertTrue(" \t".isPunctuation())
        assertTrue("。！？…".isPunctuation())
        assertTrue("‰※".isPunctuation())
        assertFalse("".isPunctuation())
        assertFalse("!a".isPunctuation())
    }

    @Test
    fun `script helpers classify japanese arabic and devanagari text`() {
        assertTrue("こんにちは".containsJapanese())
        assertFalse("hello".containsJapanese())
        assertTrue("مرحبا".isRtl())
        assertFalse("hello".isRtl())
        assertTrue('न'.isDevanagari())
        assertFalse('a'.isDevanagari())
    }
}
