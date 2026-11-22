package moe.ouom.neriplayer.lanltw.media

internal data class LanMediaByteRange(val first: Long?, val last: Long?) {
    fun resolve(byteLength: Long): LongRange? {
        if (byteLength <= 0) return null
        val start = first ?: (byteLength - checkNotNull(last)).coerceAtLeast(0)
        val end = if (first == null) byteLength - 1 else minOf(last ?: Long.MAX_VALUE, byteLength - 1)
        return if (start <= end) start..end else null
    }

    companion object {
        private val singleRange = Regex("bytes=([0-9]*)-([0-9]*)", RegexOption.IGNORE_CASE)

        fun parse(header: String?): LanMediaByteRange? {
            val (first, last) = header?.let(singleRange::matchEntire)?.destructured ?: return null
            val start = if (first.isEmpty()) null else first.toLongOrNull() ?: return null
            val end = if (last.isEmpty()) null else last.toLongOrNull() ?: return null
            if (start == null && end == null) return null
            if (start != null && end != null && start > end) return null
            return LanMediaByteRange(start, end)
        }
    }
}
