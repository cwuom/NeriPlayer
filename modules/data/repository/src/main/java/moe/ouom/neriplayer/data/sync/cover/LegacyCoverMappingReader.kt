package moe.ouom.neriplayer.data.sync.cover

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

internal class LegacyCoverMappingReader(private val file: File) {
    private val gson = Gson()
    private val type = object : TypeToken<Map<String, String?>>() {}.type

    fun read(): LegacyCoverMappingResult {
        if (!file.exists()) return LegacyCoverMappingResult.Missing
        return runCatching {
            val loaded = gson.fromJson<Map<String, String?>>(file.readText(Charsets.UTF_8), type).orEmpty()
            LegacyCoverMappingResult.Loaded(loaded.mapNotNull { (localUrl, networkUrl) ->
                if (localUrl.isBlank() || networkUrl.isNullOrBlank()) null else localUrl to networkUrl
            }.toMap())
        }.getOrElse(LegacyCoverMappingResult::Failed)
    }
}

internal sealed interface LegacyCoverMappingResult {
    data object Missing : LegacyCoverMappingResult
    data class Loaded(val mappings: Map<String, String>) : LegacyCoverMappingResult
    data class Failed(val error: Throwable) : LegacyCoverMappingResult
}
