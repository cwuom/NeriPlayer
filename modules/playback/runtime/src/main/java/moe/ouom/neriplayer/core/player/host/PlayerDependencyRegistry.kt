package moe.ouom.neriplayer.core.player.host

internal class PlayerDependencyRegistry {
    @Volatile
    private var environment: PlayerEnvironment? = null

    @Synchronized
    fun install(environment: PlayerEnvironment) {
        val previous = this.environment
        check(previous == null || previous.application === environment.application) {
            "Player dependencies are already installed for another application"
        }
        this.environment = environment
    }

    fun requireEnvironment(): PlayerEnvironment = checkNotNull(environment) {
        "Install player dependencies before initializing playback"
    }

    fun isReady(): Boolean = environment?.isReady?.invoke() == true
}
