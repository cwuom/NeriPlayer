package moe.ouom.neriplayer.ui.component.comment

import moe.ouom.neriplayer.data.model.comments.CommentEmote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论正文富文本切分 [splitCommentContent] 的单元测试。
 *
 * Composable 渲染不进单测, 这里只覆盖纯函数: 正文切成文本段与表情段的行为是 UI 排版的前提,
 * 尤其是「长标记优先」与「未命中原文保留」两条规则。
 */
class CommentRichTextTest {

    @Test
    fun `blank content yields no segments`() {
        assertTrue(splitCommentContent("", emptyList()).isEmpty())
        assertTrue(splitCommentContent("", listOf(emoteOf("[doge]"))).isEmpty())
    }

    @Test
    fun `plain text without emotes yields one text segment`() {
        assertEquals(
            listOf<CommentTextSegment>(textSegment("这是一条纯文本评论")),
            splitCommentContent("这是一条纯文本评论", emptyList())
        )
    }

    @Test
    fun `content is kept verbatim when no emote is declared`() {
        assertEquals(
            listOf<CommentTextSegment>(textSegment("[doge] 也算原文")),
            splitCommentContent("[doge] 也算原文", emptyList())
        )
    }

    @Test
    fun `declared emotes that never appear keep the content as one text segment`() {
        assertEquals(
            listOf<CommentTextSegment>(textSegment("正文里没有那个标记")),
            splitCommentContent("正文里没有那个标记", listOf(emoteOf("[doge]"), emoteOf("[大笑]")))
        )
    }

    @Test
    fun `single emote in the middle splits into three segments`() {
        assertEquals(
            listOf<CommentTextSegment>(
                textSegment("前面"),
                emoteSegment("[doge]"),
                textSegment("后面")
            ),
            splitCommentContent("前面[doge]后面", listOf(emoteOf("[doge]")))
        )
    }

    @Test
    fun `multiple different emotes split into alternating segments`() {
        assertEquals(
            listOf<CommentTextSegment>(
                textSegment("好"),
                emoteSegment("[doge]"),
                textSegment("的"),
                emoteSegment("[大笑]"),
                textSegment("啊")
            ),
            splitCommentContent("好[doge]的[大笑]啊", listOf(emoteOf("[doge]"), emoteOf("[大笑]")))
        )
    }

    @Test
    fun `unknown marker is preserved as plain text`() {
        assertEquals(
            listOf<CommentTextSegment>(textSegment("a[unknown]b")),
            splitCommentContent("a[unknown]b", listOf(emoteOf("[doge]")))
        )
    }

    @Test
    fun `longest placeholder wins when markers share a prefix`() {
        // 列表把短标记放在前面: 仍然必须由更长的 [doge] 命中
        assertEquals(
            listOf<CommentTextSegment>(emoteSegment("[doge]")),
            splitCommentContent("[doge]", listOf(emoteOf("[do]"), emoteOf("[doge]")))
        )
    }

    @Test
    fun `longer placeholder does not match a shorter marker in the content`() {
        // 正文写了 [do], 这里只声明了更长的 [doge]: 不应误命中
        assertEquals(
            listOf<CommentTextSegment>(textSegment("[do]")),
            splitCommentContent("[do]", listOf(emoteOf("[doge]")))
        )
    }

    @Test
    fun `marker only matches where it appears completely`() {
        // [doge] 不是 [do] 的位置, 只有结尾完整的 [do] 才命中
        assertEquals(
            listOf<CommentTextSegment>(textSegment("看[doge]和"), emoteSegment("[do]")),
            splitCommentContent("看[doge]和[do]", listOf(emoteOf("[do]")))
        )
    }

    @Test
    fun `leading emote yields a leading emote segment`() {
        assertEquals(
            listOf<CommentTextSegment>(emoteSegment("[doge]"), textSegment("你好")),
            splitCommentContent("[doge]你好", listOf(emoteOf("[doge]")))
        )
    }

    @Test
    fun `trailing emote yields a trailing emote segment`() {
        assertEquals(
            listOf<CommentTextSegment>(textSegment("你好"), emoteSegment("[doge]")),
            splitCommentContent("你好[doge]", listOf(emoteOf("[doge]")))
        )
    }

    @Test
    fun `adjacent emotes produce consecutive emote segments without empty text`() {
        assertEquals(
            listOf<CommentTextSegment>(emoteSegment("[doge]"), emoteSegment("[大笑]")),
            splitCommentContent("[doge][大笑]", listOf(emoteOf("[doge]"), emoteOf("[大笑]")))
        )
    }

    @Test
    fun `blank placeholders are ignored`() {
        assertEquals(
            listOf<CommentTextSegment>(textSegment("abc")),
            splitCommentContent("abc", listOf(CommentEmote("", "https://cdn.example.com/blank.png")))
        )
    }

    @Test
    fun `duplicate placeholders resolve to the first entry`() {
        val first = CommentEmote("[doge]", "https://cdn.example.com/first.png")
        val second = CommentEmote("[doge]", "https://cdn.example.com/second.png")

        assertEquals(
            listOf<CommentTextSegment>(textSegment("x"), CommentTextSegment.Emote(first)),
            splitCommentContent("x[doge]", listOf(first, second))
        )
    }

    @Test
    fun `timestamp h m s converts to milliseconds`() {
        assertEquals(4_341_000L, commentTimestampMs("1", "12", "21"))
        assertEquals(4_343_000L, commentTimestampMs("01", "12", "23"))
        assertEquals(3_723_000L, commentTimestampMs("1", "2", "3"))
    }

    @Test
    fun `two part timestamp is interpreted as minutes and seconds`() {
        assertEquals(754_000L, commentTimestampMs("12", "34", null))
        assertEquals(83_000L, commentTimestampMs("1", "23", null))
    }

    @Test
    fun `out of range timestamp is rejected`() {
        assertEquals(null, commentTimestampMs("12", "75", null))
        assertEquals(null, commentTimestampMs("1", "12", "88"))
    }

    @Test
    fun `h m s timestamp in the middle splits into three segments`() {
        assertEquals(
            listOf<CommentTextSegment>(
                textSegment("高潮在"),
                timestampSegment("1:12:21", 4_341_000L),
                textSegment("这里")
            ),
            splitCommentContent("高潮在1:12:21这里", emptyList())
        )
    }

    @Test
    fun `plain m s timestamp yields a single timestamp segment`() {
        assertEquals(
            listOf<CommentTextSegment>(timestampSegment("12:34", 754_000L)),
            splitCommentContent("12:34", emptyList())
        )
        assertEquals(
            listOf<CommentTextSegment>(timestampSegment("1:2:3", 3_723_000L)),
            splitCommentContent("1:2:3", emptyList())
        )
    }

    @Test
    fun `timestamp mixes with emote into alternating segments`() {
        assertEquals(
            listOf<CommentTextSegment>(
                textSegment("前面"),
                emoteSegment("[doge]"),
                timestampSegment("1:23", 83_000L),
                textSegment("后面")
            ),
            splitCommentContent("前面[doge]1:23后面", listOf(emoteOf("[doge]")))
        )
    }

    @Test
    fun `invalid or embedded digits are kept as plain text`() {
        assertEquals(
            listOf<CommentTextSegment>(textSegment("12:75")),
            splitCommentContent("12:75", emptyList())
        )
        assertEquals(
            listOf<CommentTextSegment>(textSegment("123:456")),
            splitCommentContent("123:456", emptyList())
        )
    }

    /** 构造测试用表情: 标记形如 `[doge]`, 地址由标记推导以便逐条区分 */
    private fun emoteOf(marker: String): CommentEmote =
        CommentEmote(marker, "https://cdn.example.com/${marker.trim('[', ']')}.png")

    /** 期望值里的文本段 */
    private fun textSegment(value: String): CommentTextSegment = CommentTextSegment.Text(value)

    /** 期望值里的时间坐标段 */
    private fun timestampSegment(value: String, positionMs: Long): CommentTextSegment =
        CommentTextSegment.Timestamp(value, positionMs)

    /** 期望值里的表情段, 地址规则与 [emoteOf] 一致 */
    private fun emoteSegment(marker: String): CommentTextSegment =
        CommentTextSegment.Emote(emoteOf(marker))
}
