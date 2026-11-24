package moe.ouom.neriplayer.data.stats

import androidx.sqlite.db.SupportSQLiteDatabase

private const val TRACK_FIELDS = "identity_key, id, name, artist, album, album_id, cover_url, duration_ms, " +
    "total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, " +
    "custom_name, custom_artist, custom_cover_url"

private const val COUNTER_FIELDS = "identity_key, device_id, epoch_started_at, total_listen_ms, play_count, " +
    "first_played_at, last_played_at"

internal fun SupportSQLiteDatabase.seedLegacyDailyPlaybackRows(dayStartAt: Long) {
    execSQL(
        "INSERT INTO playback_stat_bucket (day_start_at, $TRACK_FIELDS) SELECT ?, $TRACK_FIELDS FROM playback_stat",
        arrayOf(dayStartAt)
    )
    execSQL(
        "INSERT INTO playback_stat_daily_counter_shard (day_start_at, $COUNTER_FIELDS) " +
            "SELECT ?, $COUNTER_FIELDS FROM playback_stat_counter_shard",
        arrayOf(dayStartAt)
    )
}
