package moe.ouom.neriplayer.platform.subsonic.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import org.json.JSONArray
import org.json.JSONObject

internal const val CATALOG_PAGE_SIZE = 500

internal fun invalidCatalogResponse() = SubsonicException(-1, "音乐服务器曲库响应不完整")

internal fun JSONObject.requiredCollection(name: String): JSONObject =
    optJSONObject(name) ?: throw invalidCatalogResponse()

internal fun JSONObject.requiredEntity(name: String, id: String): JSONObject =
    requiredCollection(name).also {
        if (it.opt("id") != id) throw invalidCatalogResponse()
    }

internal fun JSONObject.collectionRows(name: String, requireId: Boolean = true): List<JSONObject> {
    if (!has(name) || isNull(name)) return emptyList()
    val rows = opt(name) as? JSONArray ?: throw invalidCatalogResponse()
    return (0 until rows.length()).map { index ->
        val row = rows.optJSONObject(index) ?: throw invalidCatalogResponse()
        if (requireId && (row.opt("id") !is String || row.optString("id").isEmpty())) {
            throw invalidCatalogResponse()
        }
        row
    }
}

internal suspend fun <T> completeServerCatalog(id: (T) -> String, load: suspend (Int) -> List<T>): List<T> {
    val items = linkedMapOf<String, T>()
    var offset = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = load(offset)
        if (page.size > CATALOG_PAGE_SIZE) throw invalidCatalogResponse()
        val previousSize = items.size
        page.forEach { items[id(it)] = it }
        if (page.size < CATALOG_PAGE_SIZE) return items.values.toList()
        // 拒绝忽略 offset 的服务，避免无限请求以及把重复页当成完整曲库
        if (items.size == previousSize || offset > Int.MAX_VALUE - CATALOG_PAGE_SIZE) {
            throw invalidCatalogResponse()
        }
        offset += CATALOG_PAGE_SIZE
    }
}
