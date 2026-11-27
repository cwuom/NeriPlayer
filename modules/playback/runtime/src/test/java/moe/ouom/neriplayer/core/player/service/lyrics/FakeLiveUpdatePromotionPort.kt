package moe.ouom.neriplayer.core.player.service.lyrics

/** Robolectric 没有真实 SystemUI，提升结果由测试直接指定 */
internal class FakeLiveUpdatePromotionPort(
    var canPost: Boolean? = true,
    var promoted: Boolean? = true,
) : LiveUpdatePromotionPort {
    var promotionQueries = 0
        private set

    override fun canPostPromoted(): Boolean? = canPost

    override fun isPromoted(notificationId: Int): Boolean? {
        promotionQueries++
        return promoted
    }
}
