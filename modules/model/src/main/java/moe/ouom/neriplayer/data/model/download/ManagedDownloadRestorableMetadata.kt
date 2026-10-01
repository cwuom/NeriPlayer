package moe.ouom.neriplayer.data.model.download

data class ManagedDownloadRestorableMetadata(
    val sourceStableKey: String?,
    val baseline: Baseline,
    val overrides: Overrides,
    val baselineCoverAssetHash: String? = null,
    val currentCoverAssetHash: String? = null,
    val baselineCoverAssetFileName: String? = null,
    val currentCoverAssetFileName: String? = null,
    val legacyCoverRecoveryReferences: List<String> = emptyList(),
    val createdAtMs: Long? = null,
    val updatedAtMs: Long? = null
) {
    data class Baseline(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val coverReference: String? = null,
        val originalLyric: String? = null,
        val translatedLyric: String? = null,
        val romanizedLyric: String? = null
    )

    data class Overrides(
        val title: String? = null,
        val artist: String? = null,
        val coverReference: String? = null,
        val userLyricOffsetMs: Long = 0L,
        val originalLyric: String? = null,
        val translatedLyric: String? = null,
        val romanizedLyric: String? = null
    )

    companion object
}
