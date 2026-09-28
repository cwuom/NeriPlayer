package moe.ouom.neriplayer.util.json

import org.json.JSONArray
import org.json.JSONObject

/** 只跳过非对象成员和映射返回的 null，业务校验失败仍由调用方处理 */
inline fun <T : Any> JSONArray.mapObjectsNotNull(
    transform: (JSONObject) -> T?
): List<T> = buildList(length()) {
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        transform(item)?.let(::add)
    }
}
