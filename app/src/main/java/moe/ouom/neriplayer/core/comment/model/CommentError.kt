package moe.ouom.neriplayer.core.comment.model

/**
 * 评论加载失败的原因分类。
 *
 * 用于在 UI 上区分「暂无评论」与「加载失败」, 并给出对应的提示文案。
 */
enum class CommentError {
    /** 网络不可用 / 连接失败 / 超时 */
    NETWORK,

    /** 需要登录、Cookie 失效或被风控拦截 */
    PERMISSION,

    /** 资源不存在或已被删除 */
    NOT_FOUND,

    /** 评论区已关闭 */
    CLOSED,

    /** 平台服务端异常 */
    SERVER,

    /** 评论接口不可用或返回了无法解析的数据 */
    API,

    /** 其他未知错误 */
    UNKNOWN
}
