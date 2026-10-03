package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PlaybackStatsPagedMigration : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS playback_stats_event_receipt (id TEXT NOT NULL PRIMARY KEY, accepted_at INTEGER NOT NULL, payload_hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS playback_stats_snapshot (id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, cleared_at INTEGER NOT NULL, counter_epoch_started_at INTEGER NOT NULL, created_at INTEGER NOT NULL, sealed INTEGER NOT NULL, owner_process_id TEXT NOT NULL, is_diff INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS playback_stat_snapshot_deleted_track (snapshot_id TEXT NOT NULL, identity_key TEXT NOT NULL, PRIMARY KEY(snapshot_id, identity_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS playback_stat_snapshot_deleted_bucket (snapshot_id TEXT NOT NULL, day_start_at INTEGER NOT NULL, identity_key TEXT NOT NULL, PRIMARY KEY(snapshot_id, day_start_at, identity_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS playback_stat_snapshot_track (snapshot_id TEXT NOT NULL, identity_key TEXT NOT NULL, id INTEGER NOT NULL, name TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, album_id INTEGER NOT NULL, cover_url TEXT, duration_ms INTEGER NOT NULL, total_listen_ms INTEGER NOT NULL, play_count INTEGER NOT NULL, last_played_at INTEGER NOT NULL, first_played_at INTEGER NOT NULL, media_uri TEXT, local_file_path TEXT, local_file_name TEXT, custom_name TEXT, custom_artist TEXT, custom_cover_url TEXT, PRIMARY KEY(snapshot_id, identity_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS playback_stat_snapshot_bucket (snapshot_id TEXT NOT NULL, day_start_at INTEGER NOT NULL, identity_key TEXT NOT NULL, id INTEGER NOT NULL, name TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, album_id INTEGER NOT NULL, cover_url TEXT, duration_ms INTEGER NOT NULL, total_listen_ms INTEGER NOT NULL, play_count INTEGER NOT NULL, last_played_at INTEGER NOT NULL, first_played_at INTEGER NOT NULL, media_uri TEXT, local_file_path TEXT, local_file_name TEXT, custom_name TEXT, custom_artist TEXT, custom_cover_url TEXT, PRIMARY KEY(snapshot_id, day_start_at, identity_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS playback_stat_snapshot_counter (snapshot_id TEXT NOT NULL, identity_key TEXT NOT NULL, device_id TEXT NOT NULL, epoch_started_at INTEGER NOT NULL, total_listen_ms INTEGER NOT NULL, play_count INTEGER NOT NULL, first_played_at INTEGER NOT NULL, last_played_at INTEGER NOT NULL, PRIMARY KEY(snapshot_id, identity_key, device_id, epoch_started_at))")
        db.execSQL("CREATE TABLE IF NOT EXISTS playback_stat_snapshot_daily_counter (snapshot_id TEXT NOT NULL, day_start_at INTEGER NOT NULL, identity_key TEXT NOT NULL, device_id TEXT NOT NULL, epoch_started_at INTEGER NOT NULL, total_listen_ms INTEGER NOT NULL, play_count INTEGER NOT NULL, first_played_at INTEGER NOT NULL, last_played_at INTEGER NOT NULL, PRIMARY KEY(snapshot_id, day_start_at, identity_key, device_id, epoch_started_at))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_snapshot_bucket_identity ON playback_stat_snapshot_bucket (snapshot_id, identity_key, day_start_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS playback_stats_pending_delta (id TEXT NOT NULL PRIMARY KEY, sequence INTEGER NOT NULL, track_json TEXT NOT NULL, listened_ms INTEGER NOT NULL, play_count_increment INTEGER, played_at INTEGER NOT NULL, epoch_started_at INTEGER NOT NULL, device_id TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_playback_pending_sequence ON playback_stats_pending_delta (sequence)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_stat_play_count ON playback_stat (play_count DESC, identity_key ASC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_stat_listen_time ON playback_stat (total_listen_ms DESC, identity_key ASC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_stat_recent_identity ON playback_stat (last_played_at DESC, identity_key ASC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_stat_first_identity ON playback_stat (first_played_at, identity_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_stat_bucket_latest ON playback_stat_bucket (identity_key ASC, last_played_at DESC, day_start_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_stat_bucket_identity_day ON playback_stat_bucket (identity_key, day_start_at)")
        db.execSQL("ALTER TABLE playlist_usage ADD COLUMN usage_deletion_tokens_json TEXT")
    }
}
