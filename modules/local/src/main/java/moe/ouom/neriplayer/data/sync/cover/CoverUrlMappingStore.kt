package moe.ouom.neriplayer.data.sync.cover

internal interface CoverUrlMappingStore {
    fun load(): Map<String, String>
    fun save(localUrl: String, networkUrl: String)
    fun delete(localUrls: Collection<String>)
}
