package moe.ouom.neriplayer.data.sync.cover

import java.util.concurrent.ConcurrentHashMap

internal class InMemoryCoverUrlMappingStore(initialMappings: Map<String, String>) : CoverUrlMappingStore {
    private val mappings = ConcurrentHashMap(initialMappings)
    override fun load(): Map<String, String> = mappings.toMap()
    override fun save(localUrl: String, networkUrl: String) { mappings[localUrl] = networkUrl }
    override fun delete(localUrls: Collection<String>) { localUrls.forEach(mappings::remove) }
}
