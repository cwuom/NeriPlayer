package moe.ouom.neriplayer.data.sync.archive.budget

import java.io.IOException

internal object SyncArchiveManifestWireGuard {
    private val recordFields = setOf(5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 19)

    fun validate(raw: ByteArray, v4: Boolean) {
        if (v4) walk(raw, 0, raw.size, 3) { start, end -> validateOriginal(raw, start, end) }
        else validateOriginal(raw, 0, raw.size)
    }

    private fun validateOriginal(raw: ByteArray, start: Int, end: Int) {
        walk(raw, start, end, 2) { headerStart, headerEnd -> validateHeader(raw, headerStart, headerEnd) }
    }

    private fun validateHeader(raw: ByteArray, start: Int, end: Int) {
        val cursor = SyncArchiveWireCursor(raw, end, start)
        while (cursor.next()) {
            if (cursor.tag in recordFields) throw IOException("Sync manifest header embeds records")
        }
    }

    private inline fun walk(raw: ByteArray, start: Int, end: Int, tag: Int, visit: (Int, Int) -> Unit) {
        val cursor = SyncArchiveWireCursor(raw, end, start)
        while (cursor.next()) {
            if (cursor.tag == tag && cursor.wire == 2) visit(cursor.bodyStart, cursor.bodyEnd)
        }
    }
}
