package moe.ouom.neriplayer.common.format

import java.util.Locale

private const val KIBIBYTE_BYTES = 1024L
private const val MEBIBYTE_BYTES = KIBIBYTE_BYTES * 1024L
private const val GIBIBYTE_BYTES = MEBIBYTE_BYTES * 1024L

fun formatFileSize(bytes: Long): String = when {
    bytes >= GIBIBYTE_BYTES -> String.format(Locale.getDefault(), "%.1f GB", bytes / GIBIBYTE_BYTES.toDouble())
    bytes >= MEBIBYTE_BYTES -> String.format(Locale.getDefault(), "%.1f MB", bytes / MEBIBYTE_BYTES.toDouble())
    bytes >= KIBIBYTE_BYTES -> String.format(Locale.getDefault(), "%.1f KB", bytes / KIBIBYTE_BYTES.toDouble())
    else -> "$bytes B"
}
