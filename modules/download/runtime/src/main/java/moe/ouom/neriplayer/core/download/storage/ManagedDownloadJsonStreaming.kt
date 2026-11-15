package moe.ouom.neriplayer.core.download.storage

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal fun JsonWriter.writeJsonValue(item: Any) {
    when (item) {
        is JSONObject -> {
            beginObject()
            item.keys().forEach { key ->
                name(key)
                writeJsonValue(item.get(key))
            }
            endObject()
        }
        is JSONArray -> {
            beginArray()
            for (index in 0 until item.length()) writeJsonValue(item.get(index))
            endArray()
        }
        is String -> value(item)
        is Number -> value(item)
        is Boolean -> value(item)
        JSONObject.NULL -> nullValue()
        else -> error("Unsupported download JSON value")
    }
}

internal fun JsonReader.readJsonObject(): JSONObject = readJsonObject(hashMapOf())

private fun JsonReader.readJsonObject(strings: MutableMap<String, String>): JSONObject = JSONObject().apply {
    beginObject()
    while (hasNext()) put(nextName(), readJsonValue(strings))
    endObject()
}

private fun JsonReader.readJsonValue(strings: MutableMap<String, String>): Any {
    return when (peek()) {
        JsonToken.BEGIN_OBJECT -> readJsonObject(strings)
        JsonToken.BEGIN_ARRAY -> JSONArray().apply {
            beginArray()
            while (hasNext()) put(readJsonValue(strings))
            endArray()
        }
        // 同一首歌的平面字段和恢复元数据可能带着相同歌词，读完这一条后即可丢弃去重表
        JsonToken.STRING -> nextString().let { value -> strings.getOrPut(value) { value } }
        JsonToken.NUMBER -> nextString().let { it.toLongOrNull() ?: it.toDoubleOrNull() ?: it }
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.NULL -> {
            nextNull()
            JSONObject.NULL
        }
        else -> throw IOException("Unexpected token in download JSON")
    }
}
