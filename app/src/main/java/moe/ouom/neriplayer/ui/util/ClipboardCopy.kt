package moe.ouom.neriplayer.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.os.TransactionTooLargeException
import androidx.annotation.StringRes
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import moe.ouom.neriplayer.common.R as CoreCommonR

internal sealed interface ClipboardCopyResult {
    data class Copied(val wasTruncated: Boolean) : ClipboardCopyResult

    data object TransactionTooLarge : ClipboardCopyResult
}

@StringRes
internal fun ClipboardCopyResult.copyMessageRes(): Int = when (this) {
    is ClipboardCopyResult.Copied -> if (wasTruncated) {
        CoreCommonR.string.toast_copy_truncated
    } else {
        CoreCommonR.string.toast_copied
    }
    ClipboardCopyResult.TransactionTooLarge -> CoreCommonR.string.toast_copy_failed
}

internal fun ClipboardManager.copyPlainTextSafely(
    label: String,
    text: String
): ClipboardCopyResult {
    val payload = ClipboardTextPolicy.prepare(text)
    return try {
        setPrimaryClip(ClipData.newPlainText(label, payload.text))
        ClipboardCopyResult.Copied(wasTruncated = payload.wasTruncated)
    } catch (error: RuntimeException) {
        if (!error.isCausedByTransactionTooLarge()) {
            throw error
        }
        ClipboardCopyResult.TransactionTooLarge
    }
}

internal suspend fun Clipboard.copyPlainTextSafely(
    label: String,
    text: String
): ClipboardCopyResult {
    val payload = ClipboardTextPolicy.prepare(text)
    return try {
        setClipEntry(ClipEntry(ClipData.newPlainText(label, payload.text)))
        ClipboardCopyResult.Copied(wasTruncated = payload.wasTruncated)
    } catch (error: RuntimeException) {
        if (!error.isCausedByTransactionTooLarge()) {
            throw error
        }
        ClipboardCopyResult.TransactionTooLarge
    }
}

private fun Throwable.isCausedByTransactionTooLarge(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is TransactionTooLargeException) {
            return true
        }
        current = current.cause
    }
    return false
}
