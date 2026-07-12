package moe.ouom.neriplayer.util

import java.io.File
import moe.ouom.neriplayer.util.io.writeTextAtomically as writeTextAtomicallyImpl

fun File.writeTextAtomically(text: String) {
    this.writeTextAtomicallyImpl(text)
}
