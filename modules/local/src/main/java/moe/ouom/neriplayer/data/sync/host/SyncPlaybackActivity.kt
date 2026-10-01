package moe.ouom.neriplayer.data.sync.host

/** 同步只关心播放意图，播放器实例由宿主持有 */
object SyncPlaybackActivity {
    @Volatile
    private var readPlaybackIntent: () -> Boolean = { false }

    fun bind(readPlaybackIntent: () -> Boolean) {
        this.readPlaybackIntent = readPlaybackIntent
    }

    val isActive: Boolean
        get() = readPlaybackIntent()
}
