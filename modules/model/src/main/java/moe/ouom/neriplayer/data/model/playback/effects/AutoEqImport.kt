package moe.ouom.neriplayer.data.model.playback.effects

sealed interface AutoEqImportResult {
    data class Parametric(val preampDb: Float, val bands: List<ParametricEqBand>, val skippedFilters: Int) : AutoEqImportResult
    data class Graphic(val preampDb: Float, val bandsDb: List<Float>) : AutoEqImportResult
    data object Invalid : AutoEqImportResult
}

private val PreampPattern = Regex("""(?i)^\s*preamp\s*:\s*([-+]?\d+(?:\.\d+)?)\s*db""")
private val FilterPattern = Regex("""(?i)^\s*filter\s*\d*\s*:\s*(on|off)\s+([a-z]+)\b(.*)$""")
private val FrequencyPattern = Regex("""(?i)\bfc\s+([-+]?\d+(?:\.\d+)?)\s*hz""")
private val GainPattern = Regex("""(?i)\bgain\s+([-+]?\d+(?:\.\d+)?)\s*db""")
private val QPattern = Regex("""(?i)\bq\s+(\d+(?:\.\d+)?)""")
private val GraphicPattern = Regex("""(?i)^\s*graphiceq\s*:(.*)$""")

private val FilterTypeAliases = mapOf(
    "PK" to ParametricEqBandType.PEAK,
    "PEQ" to ParametricEqBandType.PEAK,
    "LS" to ParametricEqBandType.LOW_SHELF,
    "LSC" to ParametricEqBandType.LOW_SHELF,
    "LSQ" to ParametricEqBandType.LOW_SHELF,
    "HS" to ParametricEqBandType.HIGH_SHELF,
    "HSC" to ParametricEqBandType.HIGH_SHELF,
    "HSQ" to ParametricEqBandType.HIGH_SHELF,
    "LP" to ParametricEqBandType.LOW_PASS,
    "LPQ" to ParametricEqBandType.LOW_PASS,
    "HP" to ParametricEqBandType.HIGH_PASS,
    "HPQ" to ParametricEqBandType.HIGH_PASS,
    "NO" to ParametricEqBandType.NOTCH,
    "BP" to ParametricEqBandType.BAND_PASS
)

/** 解析 AutoEq / Equalizer APO 导出的 ParametricEQ.txt 或 GraphicEQ 文本 */
fun parseAutoEqText(text: String): AutoEqImportResult {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    val preampDb = lines.firstNotNullOfOrNull { PreampPattern.find(it)?.groupValues?.get(1)?.toFloatOrNull() } ?: 0f
    lines.firstNotNullOfOrNull { GraphicPattern.find(it)?.groupValues?.get(1) }?.let { body ->
        return parseGraphicBody(body, preampDb)
    }
    var skipped = 0
    val bands = mutableListOf<ParametricEqBand>()
    for (line in lines) {
        val match = FilterPattern.find(line) ?: continue
        val band = parseFilter(match)
        if (band == null || bands.size >= AUDIO_EFFECTS_PARAMETRIC_BAND_LIMIT) {
            skipped += 1
        } else {
            bands += band
        }
    }
    if (bands.isEmpty()) return AutoEqImportResult.Invalid
    return AutoEqImportResult.Parametric(preampDb.coerceIn(-24f, 6f), bands, skipped)
}

private fun parseFilter(match: MatchResult): ParametricEqBand? {
    val enabled = match.groupValues[1].equals("on", ignoreCase = true)
    val type = FilterTypeAliases[match.groupValues[2].uppercase()] ?: return null
    val rest = match.groupValues[3]
    val frequency = FrequencyPattern.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
    val gain = GainPattern.find(rest)?.groupValues?.get(1)?.toFloatOrNull()
    if (type.usesGain && gain == null) return null
    val q = QPattern.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.707f
    return ParametricEqBand(
        enabled = enabled,
        type = type.storageValue,
        frequencyHz = frequency,
        gainDb = gain ?: 0f,
        q = q
    ).normalized()
}

private fun parseGraphicBody(body: String, preampDb: Float): AutoEqImportResult {
    val points = body.split(';').mapNotNull { entry ->
        val parts = entry.trim().split(Regex("\\s+"))
        val frequency = parts.getOrNull(0)?.toFloatOrNull()
        val gain = parts.getOrNull(1)?.toFloatOrNull()
        if (frequency == null || gain == null || frequency <= 0f || !gain.isFinite()) null else frequency to gain
    }.sortedBy { it.first }
    if (points.size < 2) return AutoEqImportResult.Invalid
    val anchors = points.map { it.first }
    val values = points.map { it.second }
    val bands = AudioEffectsGraphicBandFrequenciesHz.map { center ->
        interpolateLogFrequency(center, anchors, values)
            .coerceIn(-AUDIO_EFFECTS_EQ_BAND_LIMIT_DB, AUDIO_EFFECTS_EQ_BAND_LIMIT_DB)
    }
    return AutoEqImportResult.Graphic(preampDb.coerceIn(-24f, 6f), bands)
}

/** 把导入结果写入当前音色；参数均衡表示耳机校正，图形均衡直接替换十段增益 */
fun AudioEffectsSound.withAutoEqImport(result: AutoEqImportResult): AudioEffectsSound = when (result) {
    is AutoEqImportResult.Parametric -> copy(
        parametricEnabled = true,
        parametricBands = result.bands,
        preampDb = result.preampDb.coerceIn(AUDIO_EFFECTS_PREAMP_MIN_DB, AUDIO_EFFECTS_PREAMP_MAX_DB)
    )
    is AutoEqImportResult.Graphic -> copy(
        equalizerEnabled = true,
        equalizerBandsDb = result.bandsDb,
        preampDb = result.preampDb.coerceIn(AUDIO_EFFECTS_PREAMP_MIN_DB, AUDIO_EFFECTS_PREAMP_MAX_DB)
    )
    AutoEqImportResult.Invalid -> this
}
