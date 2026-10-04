package moe.ouom.neriplayer.activity.car

import android.app.Activity
import android.os.Bundle

class BluetoothMediaValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统蓝牙栈通过此入口确认媒体浏览能力，实际播放仍由媒体会话处理
        finish()
    }
}
