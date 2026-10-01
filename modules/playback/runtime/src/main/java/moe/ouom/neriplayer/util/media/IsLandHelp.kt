package moe.ouom.neriplayer.util.media

import android.os.Bundle
import org.json.JSONObject

object IsLandHelp {
    /**
     * Xiaomi Super Island media share extras for notification injection.
     * Only call when [shareContent] is a validated public catalog URL.
     */
    fun isLandMusicShare(
        addpic: Bundle? = null,
        pic: String = "miui_media_album_icon",
        content: String,
        title: String,
        shareContent: String,
        sharePic: String? = null,
    ): Bundle {
        val nfBundle = Bundle()
        val param = JSONObject()
        val paramV2 = JSONObject()
        val island = JSONObject()
        island.put(
            "shareData",
            shareData(
                title = title,
                content = content,
                pic = pic,
                sharePic = sharePic,
                shareContent = shareContent,
            ),
        )

        paramV2.put("param_island", island)
        param.put("param_v2", paramV2)

        if (addpic != null) {
            nfBundle.putBundle("miui.focus.pics", addpic)
        }
        nfBundle.putString("miui.focus.param.media", param.toString())
        return nfBundle
    }

    fun shareData(
        content: String,
        title: String,
        pic: String,
        shareContent: String,
        sharePic: String? = null,
    ): JSONObject {
        val json = JSONObject()
        json.put("content", content)
        json.put("title", title)
        json.put("pic", pic)
        json.put("shareContent", shareContent)
        sharePic?.let { json.put("sharePic", it) }
        return json
    }
}
