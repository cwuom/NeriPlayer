package moe.ouom.neriplayer.core.api.lyrics

import java.lang.reflect.Method

private val traditionalToSimplifiedFallback = mapOf(
    '愛' to '爱',
    '樂' to '乐',
    '國' to '国',
    '學' to '学',
    '風' to '风',
    '雲' to '云',
    '臺' to '台',
    '灣' to '湾',
    '聲' to '声',
    '聽' to '听',
    '說' to '说',
    '時' to '时',
    '間' to '间',
    '來' to '来',
    '為' to '为',
    '這' to '这',
    '與' to '与',
    '開' to '开',
    '關' to '关',
    '發' to '发',
    '現' to '现',
    '見' to '见',
    '後' to '后',
    '頭' to '头',
    '擱' to '搁',
    '淺' to '浅',
    '夢' to '梦',
    '無' to '无',
    '還' to '还',
    '讓' to '让',
    '麼' to '么',
    '個' to '个',
    '會' to '会',
    '嗎' to '吗',
    '對' to '对',
    '應' to '应',
    '種' to '种',
    '張' to '张',
    '長' to '长',
    '點' to '点',
    '遠' to '远',
    '別' to '别',
    '變' to '变',
    '體' to '体',
    '書' to '书',
    '畫' to '画',
    '買' to '买',
    '賣' to '卖',
    '傷' to '伤',
    '誰' to '谁',
    '習' to '习',
    '氣' to '气',
    '話' to '话',
    '結' to '结',
    '實' to '实',
    '從' to '从',
    '樣' to '样',
    '響' to '响',
    '戲' to '戏',
    '錄' to '录',
    '詞' to '词',
    '讀' to '读',
    '寫' to '写',
    '獨' to '独',
    '簡' to '简',
    '選' to '选',
    '專' to '专',
    '華' to '华',
    '機' to '机',
    '標' to '标',
    '題' to '题',
    '導' to '导',
    '演' to '演',
    '團' to '团',
    '隊' to '队',
    '賽' to '赛',
    '滿' to '满',
    '並' to '并',
    '業' to '业',
    '經' to '经',
    '濟' to '济',
    '進' to '进',
    '動' to '动',
    '靜' to '静',
    '復' to '复',
    '節' to '节',
    '級' to '级',
    '萬' to '万',
    '億' to '亿',
    '廳' to '厅',
    '廣' to '广',
    '場' to '场',
    '網' to '网',
    '頁' to '页',
    '項' to '项',
    '順' to '顺',
    '預' to '预',
    '熱' to '热',
    '燈' to '灯',
    '燦' to '灿',
    '戀' to '恋',
    '憶' to '忆',
    '憂' to '忧',
    '歡' to '欢',
    '邊' to '边',
    '漢' to '汉',
    '語' to '语',
    '英' to '英',
    '傑' to '杰',
    '倫' to '伦',
    '妳' to '你',
    '們' to '们',
    '裡' to '里',
    '裏' to '里',
    '輯' to '辑'
)

private data class ChineseTransliterator(
    val instance: Any,
    val transliterate: Method
)

private val traditionalToSimplifiedTransliterator: ChineseTransliterator? by lazy {
    runCatching {
        val transliteratorClass = Class.forName("android.icu.text.Transliterator")
        val instance = transliteratorClass
            .getMethod("getInstance", String::class.java)
            .invoke(null, "Traditional-Simplified")
            ?: return@runCatching null
        ChineseTransliterator(
            instance = instance,
            transliterate = transliteratorClass.getMethod("transliterate", String::class.java)
        )
    }.getOrNull()
}

internal fun toSimplifiedChineseForDomesticSearch(value: String): String {
    if (value.isBlank()) return value
    return traditionalToSimplifiedTransliterator?.let { transliterator ->
        runCatching {
            transliterator.transliterate.invoke(transliterator.instance, value) as? String
        }.getOrNull()
    } ?: value.map { traditionalToSimplifiedFallback[it] ?: it }.joinToString("")
}
