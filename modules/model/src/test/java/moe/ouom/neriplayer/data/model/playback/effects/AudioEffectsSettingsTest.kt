package moe.ouom.neriplayer.data.model.playback.effects

import moe.ouom.neriplayer.data.model.playback.PlaybackEqualizerPresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectsSettingsTest {
    @Test
    fun `normalization clamps unsafe values and repairs band lists`() {
        val sound = AudioEffectsSound(
            preampDb = 40f,
            equalizerBandsDb = listOf(30f, Float.NaN),
            parametricBands = List(14) { ParametricEqBand(type = "unknown", q = 99f) },
            stereoWidth = 5f,
            monoBassHz = 10f,
            limiterCeilingDb = 3f
        ).normalized()
        assertEquals(AUDIO_EFFECTS_PREAMP_MAX_DB, sound.preampDb)
        assertEquals(AUDIO_EFFECTS_GRAPHIC_BAND_COUNT, sound.equalizerBandsDb.size)
        assertEquals(AUDIO_EFFECTS_EQ_BAND_LIMIT_DB, sound.equalizerBandsDb[0])
        assertEquals(0f, sound.equalizerBandsDb[1])
        assertEquals(AUDIO_EFFECTS_PARAMETRIC_BAND_LIMIT, sound.parametricBands.size)
        assertEquals(ParametricEqBandType.PEAK.storageValue, sound.parametricBands[0].type)
        assertEquals(20f, sound.parametricBands[0].q)
        assertEquals(2f, sound.stereoWidth)
        assertEquals(0f, sound.monoBassHz)
        assertEquals(0f, sound.limiterCeilingDb)
    }

    @Test
    fun `codec round trips and rejects malformed json`() {
        val settings = AudioEffectsSettings(
            main = AudioEffectsProfile(enabled = true, presetId = "pop", sound = AudioEffectsSound(bassDb = 3f)),
            perOutputEnabled = true,
            outputProfiles = mapOf("bluetooth" to AudioEffectsProfile(enabled = true), "bogus" to AudioEffectsProfile()),
            powerMode = "high"
        )
        val decoded = AudioEffectsSettingsCodec.decodeOrNull(AudioEffectsSettingsCodec.encode(settings))
        assertEquals(settings.normalized(), decoded)
        assertEquals(setOf("bluetooth"), decoded?.outputProfiles?.keys)
        assertNull(AudioEffectsSettingsCodec.decodeOrNull("{not json"))
        assertNull(AudioEffectsSettingsCodec.decodeOrNull(" "))
        val tolerant = AudioEffectsSettingsCodec.decodeOrNull("""{"futureField":1,"powerMode":"turbo"}""")
        assertEquals(AudioEffectsPowerMode.BALANCED.storageValue, tolerant?.powerMode)
    }

    @Test
    fun `pitch follows speed by default and older saves are moved to the new default`() {
        assertTrue(AudioEffectsSettings().speedPitchLinked)

        val firstVersion = AudioEffectsSettingsCodec.decodeOrNull("""{"version":1,"speedPitchLinked":false}""")
        assertTrue(firstVersion?.speedPitchLinked == true)
        assertEquals(AUDIO_EFFECTS_SETTINGS_VERSION, firstVersion?.version)

        val chosenLater = AudioEffectsSettingsCodec.decodeOrNull(
            """{"version":$AUDIO_EFFECTS_SETTINGS_VERSION,"speedPitchLinked":false}"""
        )
        assertFalse(chosenLater?.speedPitchLinked == true)
    }

    @Test
    fun `legacy system equalizer settings migrate to the native engine`() {
        assertEquals(AudioEffectsSettings(), migrateLegacyAudioEffects(false, PlaybackEqualizerPresetId.ROCK, emptyList(), 0))

        val rock = migrateLegacyAudioEffects(true, PlaybackEqualizerPresetId.ROCK, emptyList(), 0)
        assertTrue(rock.main.enabled)
        assertEquals(AudioEffectsPresetIds.CUSTOM, rock.main.presetId)
        assertEquals(6f, rock.main.sound.equalizerBandsDb.first(), 0.01f)

        val custom = migrateLegacyAudioEffects(true, PlaybackEqualizerPresetId.CUSTOM, listOf(300, 0, 0, 0, -600), 900)
        assertEquals(3f, custom.main.sound.equalizerBandsDb.first(), 0.01f)
        assertEquals(-6f, custom.main.sound.equalizerBandsDb.last(), 0.01f)
        assertEquals(9f, custom.main.sound.outputGainDb, 0.01f)
        assertTrue(custom.main.sound.limiterEnabled)

        val resolved = resolveAudioEffectsSettings("{broken", true, PlaybackEqualizerPresetId.FLAT, emptyList(), 0)
        assertEquals(AudioEffectsPresetIds.FLAT, resolved.main.presetId)
        assertTrue(resolved.main.enabled)
    }

    @Test
    fun `built in presets are unique, already normalized and only flat is neutral`() {
        val ids = AudioEffectsBuiltInPresets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        AudioEffectsBuiltInPresets.forEach { preset ->
            assertEquals(preset.id, preset.sound, preset.sound.normalized())
            assertEquals(preset.id, preset.id == AudioEffectsPresetIds.FLAT, preset.sound.isNeutral())
        }
    }

    @Test
    fun `built in presets keep headphone correction while user presets restore everything`() {
        val correction = listOf(ParametricEqBand(frequencyHz = 3_000f, gainDb = -4f))
        val profile = AudioEffectsProfile(sound = AudioEffectsSound(parametricEnabled = true, parametricBands = correction))
        val jazz = profile.applyBuiltInPreset(requireNotNull(findAudioEffectsBuiltInPreset("jazz")))
        assertTrue(jazz.enabled)
        assertEquals(correction, jazz.sound.parametricBands)
        assertEquals(0.25f, jazz.sound.warmth)

        val saved = AudioEffectsSettings().saveUserPreset("user_1", "  My sound  ", jazz.sound)
        assertEquals("My sound", saved.userPresets.single().name)
        val replaced = saved.saveUserPreset("user_2", "My sound", AudioEffectsSound())
        assertEquals(listOf("user_2"), replaced.userPresets.map { it.id })
        val restored = AudioEffectsProfile().applyUserPreset(saved.userPresets.single())
        assertEquals("user_1", restored.presetId)
        assertEquals(jazz.sound, restored.sound)
        assertTrue(replaced.deleteUserPreset("user_2").userPresets.isEmpty())
        assertEquals(saved, saved.saveUserPreset("user_3", "   ", AudioEffectsSound()))
    }

    @Test
    fun `compressor amount maps to friendly settings and back`() {
        val sound = AudioEffectsSound().withCompressorAmount(0.5f)
        assertTrue(sound.compressorEnabled)
        assertEquals(0.5f, sound.compressorAmount(), 0.001f)
        assertTrue(sound.compressorMakeupDb > 0f)
        assertFalse(sound.withCompressorAmount(0f).compressorEnabled)
        assertEquals(0f, AudioEffectsSound().compressorAmount())
    }

    @Test
    fun `frequency response follows band gains and estimates headroom`() {
        val bands = MutableList(AUDIO_EFFECTS_GRAPHIC_BAND_COUNT) { 0f }.also { it[5] = 6f }
        val sound = AudioEffectsSound(equalizerBandsDb = bands)
        assertEquals(6f, sound.responseDb(1_000f), 0.05f)
        assertEquals(0f, sound.responseDb(60f), 0.3f)
        assertEquals(5.1f, sound.estimatedHeadroomDb(), 0.1f)
        assertEquals(0f, AudioEffectsSound().estimatedHeadroomDb())
        val curve = sound.responseCurve(points = 32)
        assertEquals(32, curve.size)
        assertEquals(20f, curve.first().first, 0.01f)
        assertEquals(20_000f, curve.last().first, 1f)
    }

    @Test
    fun `auto eq parametric and graphic text imports`() {
        val parametric = parseAutoEqText(
            """
            Preamp: -6.2 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.6 dB Q 0.70
            Filter 2: ON PK Fc 2341 Hz Gain -2.3 dB Q 1.23
            Filter 3: OFF HSC Fc 10000 Hz Gain -3.1 dB Q 0.70
            Filter 4: ON XX Fc 10 Hz Gain 1 dB Q 1
            """.trimIndent()
        ) as AutoEqImportResult.Parametric
        assertEquals(-6.2f, parametric.preampDb, 0.001f)
        assertEquals(3, parametric.bands.size)
        assertEquals(1, parametric.skippedFilters)
        assertEquals(ParametricEqBandType.LOW_SHELF.storageValue, parametric.bands[0].type)
        assertFalse(parametric.bands[2].enabled)
        val applied = AudioEffectsSound().withAutoEqImport(parametric)
        assertTrue(applied.parametricEnabled)
        assertEquals(-6.2f, applied.preampDb, 0.001f)

        val graphic = parseAutoEqText("GraphicEQ: 20 2; 1000 0; 20000 -4") as AutoEqImportResult.Graphic
        assertEquals(1.77f, graphic.bandsDb.first(), 0.01f)
        assertEquals(0f, graphic.bandsDb[5], 0.001f)
        assertTrue(graphic.bandsDb.last() < 0f)

        assertEquals(AutoEqImportResult.Invalid, parseAutoEqText("hello"))
        assertEquals(AudioEffectsSound(), AudioEffectsSound().withAutoEqImport(AutoEqImportResult.Invalid))
    }

    @Test
    fun `user preset share text round trips`() {
        val preset = AudioEffectsUserPreset("user_9", "Share", AudioEffectsSound(trebleDb = 2f))
        assertEquals(preset, AudioEffectsSettingsCodec.decodeUserPresetOrNull(AudioEffectsSettingsCodec.encodeUserPreset(preset)))
        assertNull(AudioEffectsSettingsCodec.decodeUserPresetOrNull("""{"id":"","name":"x","sound":{}}"""))
    }
}
