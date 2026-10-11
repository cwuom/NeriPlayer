package moe.ouom.neriplayer.ui.viewmodel.tab

/** 刷新 B 站收藏夹前对账号 mid 的判定结果 */
internal sealed interface LibraryBiliMid {
    data class Ready(val mid: Long) : LibraryBiliMid

    /** nav 查到的 mid 已写回 Cookie, cookieFlow 会带着 DedeUserID 再触发一次刷新 */
    data object Persisted : LibraryBiliMid

    /** nav 请求期间 Cookie 被替换, 查到的 mid 不属于当前账号, 新 Cookie 会另行触发刷新 */
    data object Superseded : LibraryBiliMid

    /** Cookie 没有 DedeUserID, nav 也查不到登录账号 */
    data object Missing : LibraryBiliMid
}

/** 手动导入的 Cookie 可能只有 SESSDATA, 这时通过 nav 接口补齐 DedeUserID */
internal suspend fun resolveLibraryBiliMid(
    cookies: Map<String, String>,
    fetchLoginMid: suspend () -> Long?,
    saveUserMid: (mid: Long, requestedWith: Map<String, String>) -> Boolean
): LibraryBiliMid {
    val storedMid = cookies["DedeUserID"]?.toLongOrNull()
    if (storedMid != null && storedMid > 0L) return LibraryBiliMid.Ready(storedMid)
    val mid = fetchLoginMid() ?: return LibraryBiliMid.Missing
    return if (saveUserMid(mid, cookies)) LibraryBiliMid.Persisted else LibraryBiliMid.Superseded
}
