package moe.ouom.neriplayer.listentogether.protocol

import kotlinx.serialization.json.Json

fun listenTogetherProtocolJson(): Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}
