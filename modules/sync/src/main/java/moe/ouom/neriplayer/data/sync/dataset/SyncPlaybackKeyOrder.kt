package moe.ouom.neriplayer.data.sync.dataset

object SyncPlaybackKeyOrder : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        if (left == right) return 0
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftPoint = left.codePointAt(leftIndex)
            val rightPoint = right.codePointAt(rightIndex)
            val difference = utf8Scalar(leftPoint).compareTo(utf8Scalar(rightPoint))
            if (difference != 0) return difference
            leftIndex += Character.charCount(leftPoint)
            rightIndex += Character.charCount(rightPoint)
        }
        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }

    // UTF8 字节次序与码点次序相同，未配对代理字符遵循 Java 编码器的问号替换
    private fun utf8Scalar(point: Int): Int = if (point in 0xd800..0xdfff) '?'.code else point

    internal fun compareBytes(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val difference = (left[index].toInt() and 255) - (right[index].toInt() and 255)
            if (difference != 0) return difference
        }
        return left.size.compareTo(right.size)
    }
}
