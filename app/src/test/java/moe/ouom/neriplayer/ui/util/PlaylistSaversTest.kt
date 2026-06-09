package moe.ouom.neriplayer.ui.util

import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlaylistSaversTest {
    @Test
    fun biliPlaylistRoundTrip_keepsKindAndSubtitle() {
        val original = BiliPlaylist(
            mediaId = 9988L,
            fid = 7766L,
            mid = 5544L,
            title = "收藏的合集",
            count = 42,
            coverUrl = "https://example.test/cover.jpg",
            kind = BiliPlaylistKind.COLLECTION,
            subtitle = "哔哩哔哩拜年纪"
        )

        val restored = restoreBiliPlaylist(original.toSaveMap())
        assertNotNull(restored)
        assertEquals(original, restored)
    }
}
