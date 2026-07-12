package moe.ouom.neriplayer.util

import android.app.Activity
import moe.ouom.neriplayer.util.platform.lockPortraitIfPhone as lockPortraitIfPhoneImpl

fun Activity.lockPortraitIfPhone() {
    this.lockPortraitIfPhoneImpl()
}
