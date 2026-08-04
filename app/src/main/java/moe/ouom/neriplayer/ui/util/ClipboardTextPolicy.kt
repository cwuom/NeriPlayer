package moe.ouom.neriplayer.ui.util

internal data class ClipboardTextPayload(
    val text: String,
    val wasTruncated: Boolean
)

internal object ClipboardTextPolicy {
    const val MAX_TEXT_CODE_UNITS = 128 * 1024

    fun prepare(text: String): ClipboardTextPayload {
        if (text.length <= MAX_TEXT_CODE_UNITS) {
            return ClipboardTextPayload(text = text, wasTruncated = false)
        }

        val endIndex = if (
            Character.isHighSurrogate(text[MAX_TEXT_CODE_UNITS - 1]) &&
            Character.isLowSurrogate(text[MAX_TEXT_CODE_UNITS])
        ) {
            MAX_TEXT_CODE_UNITS - 1
        } else {
            MAX_TEXT_CODE_UNITS
        }
        return ClipboardTextPayload(
            text = text.substring(0, endIndex),
            wasTruncated = true
        )
    }
}
