package moe.ouom.neriplayer.data.model.youtube.music

import org.json.JSONObject

data class YouTubeMusicBrowseResponse(
    val bootstrap: YouTubeMusicBootstrapConfig,
    val root: JSONObject,
    val requestLocale: YouTubeMusicRequestLocale
)
