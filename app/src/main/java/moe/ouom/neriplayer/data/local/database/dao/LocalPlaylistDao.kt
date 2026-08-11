package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistSummaryProjection
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberTokenEntity
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity

@Dao
internal interface LocalPlaylistDao {
    @Query(
        """
        SELECT
            playlist.playlist_id AS playlist_id,
            playlist.name AS name,
            playlist.custom_cover_url AS custom_cover_url,
            COUNT(member.identity_key) AS song_count,
            playlist.modified_at AS modified_at,
            playlist.song_order_version AS song_order_version
        FROM local_playlist AS playlist
        LEFT JOIN playlist_member AS member
            ON member.playlist_id = playlist.playlist_id
        GROUP BY
            playlist.playlist_id,
            playlist.name,
            playlist.custom_cover_url,
            playlist.modified_at,
            playlist.song_order_version,
            playlist.display_position
        ORDER BY playlist.display_position ASC, playlist.playlist_id ASC
        """
    )
    fun observePlaylistSummaries(): Flow<List<LocalPlaylistSummaryProjection>>

    @Query("SELECT COUNT(*) FROM local_playlist")
    suspend fun countPlaylists(): Int

    @Query(
        "SELECT * FROM local_playlist ORDER BY display_position ASC, playlist_id ASC"
    )
    suspend fun getPlaylists(): List<LocalPlaylistEntity>

    @Query(
        "SELECT * FROM playlist_member ORDER BY playlist_id ASC, display_position ASC, " +
            "identity_key ASC"
    )
    suspend fun getMembers(): List<PlaylistMemberEntity>

    @Query(
        "SELECT * FROM playlist_member_token ORDER BY playlist_id ASC, identity_key ASC, " +
            "token_index ASC, device_id ASC, counter ASC"
    )
    suspend fun getMemberTokens(): List<PlaylistMemberTokenEntity>

    @Query("SELECT * FROM track ORDER BY identity_key ASC")
    suspend fun getTracks(): List<TrackEntity>

    @Upsert
    suspend fun insertPlaylists(playlists: List<LocalPlaylistEntity>)

    @Upsert
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Upsert
    suspend fun insertMembers(members: List<PlaylistMemberEntity>)

    @Upsert
    suspend fun insertMemberTokens(tokens: List<PlaylistMemberTokenEntity>)

    @Query("DELETE FROM playlist_member_token")
    suspend fun deleteMemberTokens()

    @Query("DELETE FROM playlist_member_token WHERE playlist_id = :playlistId")
    suspend fun deleteMemberTokensForPlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_member")
    suspend fun deleteMembers()

    @Query("DELETE FROM playlist_member WHERE playlist_id = :playlistId")
    suspend fun deleteMembersForPlaylist(playlistId: Long)

    @Query("DELETE FROM local_playlist")
    suspend fun deletePlaylists()

    @Query("DELETE FROM local_playlist WHERE playlist_id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM track")
    suspend fun deleteTracks()

    @Query(
        "DELETE FROM track WHERE identity_key NOT IN " +
            "(SELECT DISTINCT identity_key FROM playlist_member)"
    )
    suspend fun deleteOrphanTracks()

    @Transaction
    suspend fun replaceSnapshot(
        playlists: List<LocalPlaylistEntity>,
        tracks: List<TrackEntity>,
        members: List<PlaylistMemberEntity>,
        memberTokens: List<PlaylistMemberTokenEntity>
    ) {
        deleteMemberTokens()
        deleteMembers()
        deletePlaylists()
        deleteTracks()
        insertTracks(tracks)
        insertPlaylists(playlists)
        insertMembers(members)
        insertMemberTokens(memberTokens)
    }
}
