package moe.ouom.neriplayer.ui.component.comment

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.data.model.comments.CommentEmote
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

/**
 * 内联表情占位符的边长 (评论媒体增强)。
 *
 * 占位符尺寸与表情图片的实际绘制尺寸保持一致, 避免含表情的行高与纯文本行不一致。
 */
private val EmotePlaceholderSize = 20.sp

/** 内联表情图片的实际绘制尺寸, 与 [EmotePlaceholderSize] 一一对应 */
private val EmoteImageSize = 20.dp

/**
 * 评论正文按表情标记切分后的最小单元。
 *
 * 纯函数 [splitCommentContent] 负责产出该模型, UI 层只按段渲染 (追加文本 / 内联表情图片),
 * 因此切分规则可以脱离 Compose 单独做单元测试。
 */
internal sealed interface CommentTextSegment {

    /** 正文里未被任何表情标记占用的普通文本 */
    data class Text(val text: String) : CommentTextSegment

    /** 正文里命中的一条内联表情 */
    data class Emote(val emote: CommentEmote) : CommentTextSegment
}

/**
 * 把评论正文 [content] 按 [emotes] 中的标记切分为有序的 [CommentTextSegment] 列表。
 *
 * 规则:
 * - 标记按长度降序优先匹配, 避免 ``[do]`` 抢先命中 ``[doge]`` 的前缀; 长度相同时按 [emotes] 原有顺序;
 * - 未命中任何标记的原文原样保留为 [CommentTextSegment.Text];
 * - 相邻或空的文本段被合并 / 丢弃, 即相邻两个 [CommentTextSegment.Emote] 之间不会出现空文本段;
 * - [emotes] 为空 (或标记全为空白串) 时退化为单个文本段; [content] 为空时返回空列表;
 * - 重复的标记只保留 [emotes] 中第一个出现的地址。
 *
 * @param content 平台返回的评论正文原文, 其中可能混有 ``[doge]`` / ``[大笑]`` 之类的标记
 * @param emotes 该评论携带的表情清单, 标记与图片地址的对应关系
 * @return 按正文出现顺序排布的文本 / 表情段
 */
internal fun splitCommentContent(
    content: String,
    emotes: List<CommentEmote>
): List<CommentTextSegment> {
    if (content.isEmpty()) return emptyList()

    // 空标记无法推进游标, 必须过滤; 按长度降序保证长标记优先命中
    val candidates = emotes
        .filter { it.placeholder.isNotEmpty() }
        .distinctBy { it.placeholder }
        .sortedByDescending { it.placeholder.length }
    if (candidates.isEmpty()) return listOf(CommentTextSegment.Text(content))

    val segments = ArrayList<CommentTextSegment>()
    val pending = StringBuilder()
    var index = 0
    while (index < content.length) {
        val matched = candidates.firstOrNull { content.startsWith(it.placeholder, index) }
        if (matched == null) {
            pending.append(content[index])
            index++
            continue
        }
        if (pending.isNotEmpty()) {
            segments += CommentTextSegment.Text(pending.toString())
            pending.clear()
        }
        segments += CommentTextSegment.Emote(matched)
        index += matched.placeholder.length
    }
    if (pending.isNotEmpty()) {
        segments += CommentTextSegment.Text(pending.toString())
    }
    return segments
}

/**
 * 内联表情在 [androidx.compose.ui.text.AnnotatedString] 与 `inlineContent` 映射里的唯一 key。
 *
 * 切分结果的下标天然稳定且唯一, 直接以它作为 key 即可保证两处一一对应。
 */
private fun emoteInlineId(index: Int): String = "neriplayer_emote_$index"

/**
 * 渲染带内联表情的评论正文。
 *
 * 纯文本正文直接走 [Text] 的字符串重载; 只有正文里确实命中表情时才构建
 * [androidx.compose.ui.text.AnnotatedString] 并挂载 `inlineContent`, 表情图沿用项目既有的
 * 离线缓存图片请求, 保证离线模式下只读缓存不发新请求。
 *
 * @param content 评论正文原文
 * @param emotes 正文里可能出现的表情清单
 * @param style 正文文本样式, 与所在列表的排版保持一致
 * @param offlineMode 离线模式, 决定表情图是否允许联网
 * @param modifier 施加在正文 [Text] 上的修饰符
 */
@Composable
internal fun CommentRichText(
    content: String,
    emotes: List<CommentEmote>,
    style: TextStyle,
    offlineMode: Boolean,
    modifier: Modifier = Modifier
) {
    val segments = remember(content, emotes) { splitCommentContent(content, emotes) }
    // 空正文 (或仅含无法渲染的内容) 不渲染任何东西, 避免产生空的 Text 节点
    if (segments.isEmpty()) return

    // 纯文本路径: 直接显示原文, 不退化成空 AnnotatedString
    if (segments.none { it is CommentTextSegment.Emote }) {
        Text(text = content, style = style, modifier = modifier)
        return
    }

    val context = LocalContext.current
    val emoteSizePx = with(LocalDensity.current) { EmoteImageSize.roundToPx() }
    val annotated = remember(segments) {
        buildAnnotatedString {
            segments.forEachIndexed { index, segment ->
                when (segment) {
                    is CommentTextSegment.Text -> append(segment.text)
                    is CommentTextSegment.Emote ->
                        appendInlineContent(emoteInlineId(index), segment.emote.placeholder)
                }
            }
        }
    }

    val inlineContent = mutableMapOf<String, InlineTextContent>()
    for (index in segments.indices) {
        val segment = segments[index]
        if (segment !is CommentTextSegment.Emote) continue
        val emoteUrl = segment.emote.url
        inlineContent[emoteInlineId(index)] = InlineTextContent(
            placeholder = Placeholder(
                width = EmotePlaceholderSize,
                height = EmotePlaceholderSize,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
            )
        ) { _ ->
            // alternateText 已由 appendInlineContent 提供无障碍描述, 图片本身无需再设置
            AsyncImage(
                model = remember(context, emoteUrl, offlineMode, emoteSizePx) {
                    offlineCachedImageRequest(
                        context,
                        emoteUrl,
                        sizePx = emoteSizePx,
                        offlineMode = offlineMode
                    )
                },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(EmoteImageSize)
            )
        }
    }

    Text(
        text = annotated,
        style = style,
        modifier = modifier,
        inlineContent = inlineContent
    )
}
