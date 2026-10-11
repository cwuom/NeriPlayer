package moe.ouom.neriplayer.lyrics.parser

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry

/**
 * 歌词里的制作信息与版权声明行
 *
 * 角色行 (作词/制作人/OP 等) 只在开头或结尾连续的制作信息区内删除, 正文中间的冒号行一律保留;
 * 翻唱/版权声明这类不可能是歌词的句子在任何位置都删除
 */
internal enum class LyricCreditLineKind {
    BLANK,

    /** 已知制作角色 + 分隔符 + 内容, 如"制作人 Producer：某某" */
    CREDIT,

    /** 形似"未知角色：内容"或首行标题, 只有被已知角色行包夹在边缘区内时才删除 */
    WEAK_CREDIT,

    /** 翻唱、版权、翻译来源等声明 */
    NOTICE,
    LYRIC
}

fun List<LyricEntry>.withoutLyricCredits(): List<LyricEntry> = withoutLyricCredits { it.text }

fun <T> List<T>.withoutLyricCredits(textOf: (T) -> String): List<T> {
    val kinds = classifyLyricCreditLines(map(textOf))
    val removed = lyricCreditRemovalMask(kinds)
    if (removed.none { it } || !hasRemainingLyric(kinds, removed)) {
        return this
    }
    return filterIndexed { index, _ -> !removed[index] }
}

fun isLyricCreditOrNoticeLine(text: String): Boolean {
    return classifyLyricCreditLine(text).isStrongCredit()
}

internal fun classifyLyricCreditLines(texts: List<String>): List<LyricCreditLineKind> {
    val kinds = texts.map(::classifyLyricCreditLine)
    if (!isLeadingTitleLine(texts, kinds)) {
        return kinds
    }
    return listOf(LyricCreditLineKind.WEAK_CREDIT) + kinds.drop(1)
}

/** 首行"歌名 - 歌手", 或紧跟两行制作信息的短标题 */
private fun isLeadingTitleLine(texts: List<String>, kinds: List<LyricCreditLineKind>): Boolean {
    if (kinds.firstOrNull() != LyricCreditLineKind.LYRIC || texts[0].trim().length > MAX_TITLE_LINE_LENGTH) {
        return false
    }
    val followedByCredits = kinds.size > 2 && kinds[1].isStrongCredit() && kinds[2].isStrongCredit()
    return followedByCredits || TitleArtistSeparatorRegex.containsMatchIn(texts[0])
}

internal fun lyricCreditRemovalMask(kinds: List<LyricCreditLineKind>): BooleanArray {
    val removed = BooleanArray(kinds.size) { kinds[it] == LyricCreditLineKind.NOTICE }
    for (index in 0..leadingCreditBlockEnd(kinds)) {
        removed[index] = true
    }
    for (index in trailingCreditBlockStart(kinds) until kinds.size) {
        removed[index] = true
    }
    return removed
}

private fun hasRemainingLyric(kinds: List<LyricCreditLineKind>, removed: BooleanArray): Boolean {
    for (index in kinds.indices) {
        if (!removed[index] && kinds[index] != LyricCreditLineKind.BLANK) {
            return true
        }
    }
    return false
}

/** 开头制作信息区最后一个强制作信息行的下标, 没有时返回 -1 */
private fun leadingCreditBlockEnd(kinds: List<LyricCreditLineKind>): Int {
    var lastStrong = -1
    for (index in kinds.indices) {
        val kind = kinds[index]
        if (kind.isStrongCredit()) {
            lastStrong = index
        } else if (!kind.isCreditGap()) {
            break
        }
    }
    return lastStrong
}

/** 结尾制作信息区第一个强制作信息行的下标, 没有时返回 size */
private fun trailingCreditBlockStart(kinds: List<LyricCreditLineKind>): Int {
    var firstStrong = kinds.size
    for (index in kinds.lastIndex downTo 0) {
        val kind = kinds[index]
        if (kind.isStrongCredit()) {
            firstStrong = index
        } else if (!kind.isCreditGap()) {
            break
        }
    }
    return firstStrong
}

private fun LyricCreditLineKind.isStrongCredit(): Boolean {
    return this == LyricCreditLineKind.CREDIT || this == LyricCreditLineKind.NOTICE
}

private fun LyricCreditLineKind.isCreditGap(): Boolean {
    return this == LyricCreditLineKind.BLANK || this == LyricCreditLineKind.WEAK_CREDIT
}

internal fun classifyLyricCreditLine(text: String): LyricCreditLineKind {
    val trimmed = text.trim()
    return when {
        trimmed.isEmpty() -> LyricCreditLineKind.BLANK
        isLyricNoticeText(trimmed) -> LyricCreditLineKind.NOTICE
        else -> classifyLyricRoleLine(stripEnclosingBrackets(trimmed))
    }
}

private fun classifyLyricRoleLine(line: String): LyricCreditLineKind {
    val prefix = creditPrefixOf(line) ?: return classifyEnglishByLine(line)
    return when {
        isCreditRolePrefix(prefix) -> LyricCreditLineKind.CREDIT
        isGenericCreditPrefix(prefix) -> LyricCreditLineKind.WEAK_CREDIT
        else -> LyricCreditLineKind.LYRIC
    }
}

/** "角色：内容"里的角色; 没有分隔符、角色过长或内容为空时返回 null */
private fun creditPrefixOf(line: String): String? {
    val separatorIndex = line.indexOfAny(CreditSeparators)
    val hasRoleAndValue = separatorIndex in 1..MAX_CREDIT_PREFIX_LENGTH &&
        line.substring(separatorIndex + 1).isNotBlank()
    return if (hasRoleAndValue) line.substring(0, separatorIndex).trim() else null
}

/** "Produced by 某某" 这类没有冒号的英文署名 */
private fun classifyEnglishByLine(line: String): LyricCreditLineKind {
    val roles = EnglishByCreditRegex.matchEntire(line)?.groupValues?.get(1) ?: return LyricCreditLineKind.LYRIC
    val roleWords = EnglishWordRegex.findAll(roles.lowercase()).map { it.value }.toList()
    if (!roleWords.all(::isEnglishCreditWord)) {
        return LyricCreditLineKind.LYRIC
    }
    return if (roleWords.any(EnglishStrictByRoles::contains)) {
        LyricCreditLineKind.CREDIT
    } else {
        LyricCreditLineKind.WEAK_CREDIT
    }
}

private fun isEnglishCreditWord(word: String): Boolean {
    return word in EnglishCreditRoleWords || word in EnglishCreditStopWords
}

private fun isLyricNoticeText(line: String): Boolean {
    val lower = line.lowercase()
    return LyricNoticePhrases.any { lower.contains(it) } || LyricNoticePrefixRegex.containsMatchIn(lower)
}

private fun stripEnclosingBrackets(line: String): String {
    val bracketIndex = OpeningBrackets.indexOf(line.first())
    val enclosed = bracketIndex >= 0 && line.length > 2 && line.last() == ClosingBrackets[bracketIndex]
    return if (enclosed) line.substring(1, line.length - 1).trim() else line
}

internal fun isCreditRolePrefix(prefix: String): Boolean {
    val roleTokens = tokenizeCreditPrefix(prefix)?.filterNot(EnglishCreditStopWords::contains) ?: return false
    return roleTokens.isNotEmpty() && roleTokens.all(::isCreditRoleToken)
}

private fun isCreditRoleToken(token: String): Boolean {
    return token in EnglishCreditRoleWords || isSegmentableCjkRole(token)
}

private fun isGenericCreditPrefix(prefix: String): Boolean {
    return prefix.lowercase() !in LyricPartMarkers && GenericCreditPrefixRegex.matches(prefix)
}

/** 把前缀拆成连续的 CJK 段与英文单词; 出现数字或其它符号时返回 null */
private fun tokenizeCreditPrefix(prefix: String): List<String>? {
    val normalized = prefix.lowercase().replace("b站", " bilibili ")
    if (!CreditPrefixCharsRegex.matches(normalized)) {
        return null
    }
    return CreditPrefixTokenRegex.findAll(normalized).map { it.value }.toList()
        .takeIf { it.size <= MAX_CREDIT_PREFIX_TOKENS }
}

/** 按角色词典切分 CJK 段, 能完整切开才算角色, 例如"配唱制作人" = 配唱 + 制作人 */
private fun isSegmentableCjkRole(token: String): Boolean {
    val reachable = BooleanArray(token.length + 1)
    reachable[0] = true
    for (end in 1..token.length) {
        reachable[end] = hasRoleWordEndingAt(token, end, reachable)
    }
    return reachable[token.length]
}

private fun hasRoleWordEndingAt(token: String, end: Int, reachable: BooleanArray): Boolean {
    for (start in maxOf(0, end - MAX_CJK_ROLE_WORD_LENGTH) until end) {
        if (reachable[start] && token.substring(start, end) in CjkCreditRoleWords) {
            return true
        }
    }
    return false
}

private const val MAX_TITLE_LINE_LENGTH = 40
private const val MAX_CREDIT_PREFIX_LENGTH = 40
private const val MAX_CREDIT_PREFIX_TOKENS = 8
private const val MAX_CJK_ROLE_WORD_LENGTH = 5

private val CreditSeparators = charArrayOf(':', '：')
private const val CjkRoleChars = """\u4e00-\u9fff\u3400-\u4dbf\uac00-\ud7a3"""
private val CreditPrefixCharsRegex = Regex("""[a-z$CjkRoleChars \u3000/／&＆·・.\-、,，|+_]+""")
private val CreditPrefixTokenRegex = Regex("""[a-z]+|[$CjkRoleChars]+""")
private val GenericCreditPrefixRegex = Regex("""\p{L}[\p{L} /]{0,11}""")
private val EnglishByCreditRegex = Regex("""^([A-Za-z][A-Za-z&/ ]{1,40}?)\s+by\s*[:：\-]?\s+\S.*$""", RegexOption.IGNORE_CASE)
private val EnglishWordRegex = Regex("""[a-z]+""")
private val TitleArtistSeparatorRegex = Regex("""\s[-–—]\s""")
private val LyricNoticePrefixRegex = Regex("""^(?:©|℗|copyright\s*(?:©|\(c\)))""")

private const val OpeningBrackets = "【[(（「『〖<《"
private const val ClosingBrackets = "】])）」』〗>》"

private val LyricPartMarkers = setOf(
    "男", "女", "合", "齐", "全", "男女", "女男", "合唱", "独白", "旁白", "rap", "all", "both", "a", "b"
)

private val LyricNoticePhrases = listOf(
    "未经著作权人", "未經著作權人", "不得翻唱", "不得翻录", "不得翻錄", "请勿翻唱", "請勿翻唱", "禁止翻唱",
    "严禁翻唱", "不得转载", "禁止转载", "不得商用", "禁止商用", "版权所有", "版權所有", "享有本翻译作品",
    "翻译作品的著作权", "歌词翻译由", "大模型提供", "歌词贡献者", "翻译贡献者", "all rights reserved",
    "unauthorized reproduction"
)

private val EnglishCreditStopWords = setOf("by", "and", "of", "the", "feat", "ft", "featuring", "co", "with", "for")

private val EnglishStrictByRoles = setOf(
    "lyrics", "produced", "composed", "arranged", "mixed", "mastered", "recorded", "engineered", "programmed"
)

private val EnglishCreditRoleWords = setOf(
    "lyrics", "lyric", "lyricist", "lyricists", "words", "written", "writer", "writers", "composed", "composer",
    "composers", "composition", "music", "arranged", "arranger", "arrangers", "arrangement", "produced",
    "producer", "producers", "production", "executive", "vocal", "vocals", "vocalist", "backing", "background",
    "chorus", "harmony", "harmonies", "recorded", "recording", "engineer", "engineers", "engineered",
    "engineering", "assistant", "studio", "studios", "mixed", "mixing", "mix", "mixer", "mastered", "mastering",
    "master", "guitar", "guitars", "guitarist", "acoustic", "electric", "bass", "bassist", "drum", "drums",
    "drummer", "percussion", "keyboard", "keyboards", "keys", "piano", "pianist", "synth", "synthesizer",
    "strings", "string", "violin", "viola", "cello", "orchestra", "conductor", "programming", "programmed",
    "programmer", "editing", "editor", "edited", "tuning", "cover", "art", "artwork", "design", "designer",
    "director", "visual", "photography", "photographer", "op", "sp", "publisher", "publishing", "published",
    "label", "distribution", "distributed", "copyright", "special", "thanks", "supervisor", "coordinator",
    "manager", "marketing", "promotion", "planning", "planner", "presented", "original", "singer", "artist",
    "performed", "sound", "project", "video", "mv", "bilibili", "weibo", "wechat", "email", "contact", "qq",
    "douyin", "tiktok", "instagram", "youtube", "twitter"
)

private val CjkCreditRoleWords = setOf(
    "作词", "作詞", "作曲", "词曲", "詞曲", "填词", "填詞", "词", "詞", "曲", "歌", "作词人", "作曲人", "编曲人",
    "词作者", "曲作者", "作者", "歌词", "歌詞", "翻译", "翻譯", "校对", "校對",
    "编曲", "編曲", "编配", "編配", "配器", "编写", "編寫", "制作", "製作", "制作人", "製作人", "制作方", "製作方",
    "出品", "出品人", "出品方", "联合", "聯合", "发行", "發行", "发行方", "發行方", "出版", "版权", "版權",
    "版权方", "版權方", "授权", "授權", "代理", "统筹", "統籌", "策划", "策劃", "企划", "企劃", "监制", "監製",
    "总监", "總監", "总", "總", "执行", "執行", "艺人", "藝人", "经纪", "經紀", "宣传", "宣傳", "推广", "推廣",
    "宣发", "宣發", "宣推", "营销", "營銷", "市场", "市場", "商务", "商務", "合作", "媒介", "媒体", "媒體",
    "运营", "運營", "项目", "項目", "负责人", "負責人", "助理", "制片", "製片", "制片人", "製片人", "全网", "全網",
    "数字", "數字", "音乐", "音樂", "人声", "人聲", "和声", "和聲", "和音", "伴唱", "配唱", "演唱", "演唱者",
    "原唱", "歌手", "主唱", "录音", "錄音", "录音师", "錄音師", "录音棚", "錄音棚", "录音室", "錄音室", "录制",
    "錄製", "混音", "混音师", "混音師", "缩混", "縮混", "混缩", "混縮", "母带", "母帶", "处理", "處理", "工程师",
    "工程師", "后期", "後期", "编辑", "編輯", "修音", "调音", "調音", "音频", "音頻", "音效", "采样", "採樣",
    "编程", "編程", "节奏", "節奏", "鼓", "鼓手", "吉他", "吉他手", "贝斯", "貝斯", "贝司", "键盘", "鍵盤",
    "钢琴", "鋼琴", "合成器", "弦乐", "弦樂", "弦乐团", "弦樂團", "乐团", "樂團", "乐队", "樂隊", "管乐", "管樂",
    "铜管", "銅管", "木管", "打击乐", "打擊樂", "提琴", "小提琴", "中提琴", "大提琴", "长笛", "長笛", "萨克斯",
    "薩克斯", "小号", "小號", "古筝", "古箏", "二胡", "琵琶", "笛子", "竹笛", "唢呐", "嗩吶", "扬琴", "揚琴",
    "口琴", "手风琴", "手風琴", "指挥", "指揮", "首席", "演奏", "乐手", "樂手", "器乐", "器樂", "封面", "设计",
    "設計", "视觉", "視覺", "美术", "美術", "摄影", "攝影", "插画", "插畫", "海报", "海報", "视频", "視頻",
    "影像", "导演", "導演", "文案", "鸣谢", "鳴謝", "特别", "特別", "感谢", "感謝", "工作室", "公司", "厂牌",
    "廠牌", "监听", "監聽", "微博", "公众号", "公眾號", "抖音", "快手", "微信", "邮箱", "郵箱", "联系", "聯繫",
    "群", "작사", "작곡", "편곡", "보컬"
)
