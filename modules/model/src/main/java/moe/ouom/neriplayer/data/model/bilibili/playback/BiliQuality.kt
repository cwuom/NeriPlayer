package moe.ouom.neriplayer.data.model.bilibili.playback

enum class BiliQuality(val key: String, val minBitrateKbps: Int) {
    DOLBY("dolby",      0),     // 标签优先
    HIRES("hires",    1000),
    LOSSLESS("lossless", 500),
    HIGH("high",       180),
    MEDIUM("medium",   120),
    LOW("low",          60);

    companion object {
        private val order = listOf(DOLBY, HIRES, LOSSLESS, HIGH, MEDIUM, LOW)

        fun fromKey(key: String): BiliQuality =
            order.find { it.key == key.trim().lowercase() } ?: HIGH

        /** 返回从当前到更低的一条降级链 */
        fun degradeChain(from: BiliQuality): List<BiliQuality> {
            val startIdx = order.indexOf(from).coerceAtLeast(0)
            return order.drop(startIdx)
        }
    }
}
