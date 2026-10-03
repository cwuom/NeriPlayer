package moe.ouom.neriplayer.data.model.comments
/** 网易云官方表情 CDN 模板 (`%s` 替换为映射表里的 id)。 */
private const val NETEASE_EMOTE_URL_TEMPLATE = "https://s1.music.126.net/style/web2/emt/emoji_%s.png"

/**
 * 网易云 web 端内置表情的「名称 -> 官方 CDN 图片 id」映射。
 *
 * 取自 `https://s3.music.126.net/web/s/core.js` 中的 `i5n` 表 (共 59 项, 纯文本约 0.7 KB);
 * 只内置映射, 不上架图片: 表情图仍由网易云官方 CDN 按需加载并走 Coil 缓存,
 * 因此不增加 APK 体积, 也不涉及把官方表情图打包再分发。
 */
private val NeteaseEmoteIds: Map<String, String> = mapOf(
    "大笑" to "86",
    "可爱" to "85",
    "憨笑" to "359",
    "色" to "95",
    "亲亲" to "363",
    "惊恐" to "96",
    "流泪" to "356",
    "亲" to "362",
    "呆" to "352",
    "哀伤" to "342",
    "呲牙" to "343",
    "吐舌" to "348",
    "撇嘴" to "353",
    "怒" to "361",
    "奸笑" to "341",
    "汗" to "97",
    "痛苦" to "346",
    "惶恐" to "354",
    "生病" to "350",
    "口罩" to "351",
    "大哭" to "357",
    "晕" to "355",
    "发怒" to "115",
    "开心" to "360",
    "鬼脸" to "94",
    "皱眉" to "87",
    "流感" to "358",
    "爱心" to "33",
    "心碎" to "34",
    "钟情" to "303",
    "星星" to "309",
    "生气" to "314",
    "便便" to "89",
    "强" to "13",
    "弱" to "372",
    "拜" to "14",
    "牵手" to "379",
    "跳舞" to "380",
    "禁止" to "374",
    "这边" to "262",
    "爱意" to "106",
    "示爱" to "376",
    "嘴唇" to "367",
    "狗" to "81",
    "猫" to "78",
    "猪" to "100",
    "兔子" to "459",
    "小鸡" to "450",
    "公鸡" to "461",
    "幽灵" to "116",
    "圣诞" to "411",
    "外星" to "101",
    "钻石" to "52",
    "礼物" to "107",
    "男孩" to "0",
    "女孩" to "1",
    "蛋糕" to "337",
    "圈" to "312",
    "叉" to "313"
)

/**
 * 网易云表情名称 -> 表情图 CDN 直链; 名称不在表内时返回 null。
 */
fun neteaseEmoteUrl(name: String): String? {
    val id = NeteaseEmoteIds[name] ?: return null
    return NETEASE_EMOTE_URL_TEMPLATE.replace("%s", id)
}

/** 网易云的「名称 -> 表情图 URL」表 (惰性构建一次, 避免每条评论重复拼 59 个地址)。 */
private val NeteaseEmoteUrls: Map<String, String> by lazy {
    NeteaseEmoteIds.mapValues { (_, id) -> NETEASE_EMOTE_URL_TEMPLATE.replace("%s", id) }
}

/**
 * 网易云的「正文标记 -> 表情图 URL」表, 供 Mapper 一次性展开正文里的 `[名称]` 标记。
 *
 * 与 [neteaseEmoteUrl] 一样必须是 public: 平台模块使用它, 不能声明为 `internal`。
 */
fun neteaseEmoteUrlMap(): Map<String, String> = NeteaseEmoteUrls
