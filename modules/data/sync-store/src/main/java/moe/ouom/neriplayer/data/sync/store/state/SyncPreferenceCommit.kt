package moe.ouom.neriplayer.data.sync.store.state

import android.annotation.SuppressLint
import android.content.SharedPreferences

@SuppressLint("UseKtx")
internal inline fun SharedPreferences.commitEdit(action: SharedPreferences.Editor.() -> Unit): Boolean {
    // KTX 的 edit 不返回提交结果，墓碑与版本必须能够检查持久化失败
    val editor = edit()
    editor.action()
    return editor.commit()
}
