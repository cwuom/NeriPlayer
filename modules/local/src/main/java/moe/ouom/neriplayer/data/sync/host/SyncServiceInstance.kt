package moe.ouom.neriplayer.data.sync.host

internal class SyncServiceInstance<T : Any> {
    @Volatile
    private var instance: T? = null

    fun get(create: () -> T): T = instance ?: synchronized(this) {
        instance ?: create().also { instance = it }
    }
}
