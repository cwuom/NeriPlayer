package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import android.content.ClipData
import android.content.Intent
import moe.ouom.neriplayer.data.local.media.external.LocalMediaExternalTagEditTarget

/** Lyrico 的正式包名；调试版用于接入自测，两个包都没有安装时不会启动任何页面 */
private const val LYRICO_RELEASE_PACKAGE = "com.lonx.lyrico"
private const val LYRICO_DEBUG_PACKAGE = "com.lonx.lyrico.debug"

/** Lyrico 单曲标签编辑入口，见其「外部编辑接入」文档 */
private const val LYRICO_EDIT_TAG_ACTION = "com.lonx.lyrico.action.EDIT_TAG"

/**
 * 按 Lyrico 的接入约定构造单曲编辑 Intent：data 指向原始音频，ClipData 让接收方在
 * 各版本系统上都能拿到授权，受控目录里的文件同时授予写权限；
 * MediaStore 条目只授予读权限，由 Lyrico 自己申请系统写授权。
 *
 * 正式包排在调试包前面，调用方按顺序尝试，第一个能启动的就够用。
 */
internal fun lyricoTagEditIntents(target: LocalMediaExternalTagEditTarget): List<Intent> {
    return listOf(LYRICO_RELEASE_PACKAGE, LYRICO_DEBUG_PACKAGE).map { packageName ->
        Intent(LYRICO_EDIT_TAG_ACTION).apply {
            setPackage(packageName)
            setDataAndType(target.uri, target.mimeType)
            clipData = ClipData.newRawUri("audio", target.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (target.grantWrite) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}
