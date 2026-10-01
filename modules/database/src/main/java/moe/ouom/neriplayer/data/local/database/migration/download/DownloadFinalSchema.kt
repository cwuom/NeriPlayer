package moe.ouom.neriplayer.data.local.database.migration.download

import androidx.sqlite.db.SupportSQLiteDatabase
import moe.ouom.neriplayer.data.local.database.migration.addIntegerColumnIfMissing
import moe.ouom.neriplayer.data.local.database.migration.addTextColumnIfMissing

internal fun addFinalDownloadColumns(db: SupportSQLiteDatabase) {
    addTextColumnIfMissing(db, "downloaded_song_catalog", "matched_romanized_lyric")
    addTextColumnIfMissing(db, "downloaded_song_catalog", "original_romanized_lyric")
    addTextColumnIfMissing(db, "download_snapshot_metadata", "album")
    addTextColumnIfMissing(db, "download_snapshot_metadata", "matched_romanized_lyric")
    addTextColumnIfMissing(db, "download_snapshot_metadata", "original_romanized_lyric")
    addIntegerColumnIfMissing(db, "download_snapshot_metadata", "created_at_ms")
    addTextColumnIfMissing(db, "download_snapshot_metadata", "created_at_source")
    addTextColumnIfMissing(db, "download_pending_queue", "matched_romanized_lyric")
    addTextColumnIfMissing(db, "download_pending_queue", "original_romanized_lyric")
}

internal fun createFinalDownloadTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `download_operation` (
            `operation_id` TEXT NOT NULL PRIMARY KEY,
            `stable_key` TEXT NOT NULL,
            `library_id` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `queue_order` INTEGER NOT NULL,
            `source_hint_json` TEXT NOT NULL,
            `staging_dir_name` TEXT NOT NULL,
            `bytes_written` INTEGER NOT NULL,
            `total_bytes` INTEGER,
            `resume_json` TEXT,
            `retry_count` INTEGER NOT NULL,
            `next_retry_at_ms` INTEGER,
            `last_error_code` TEXT,
            `stop_requested_by_user` INTEGER NOT NULL DEFAULT 0,
            `created_at_ms` INTEGER NOT NULL,
            `updated_at_ms` INTEGER NOT NULL,
            `host_process_token` TEXT,
            `host_admitted_at_ms` INTEGER
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_download_operation_state_queue`
        ON `download_operation` (`state`, `queue_order`)
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_download_operation_host_process_library`
        ON `download_operation` (`host_process_token`, `library_id`)
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `managed_library_item` (
            `library_id` TEXT NOT NULL,
            `stable_key` TEXT NOT NULL,
            `artifact_id` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `lease_id` TEXT,
            `audio_reference` TEXT,
            `audio_name` TEXT,
            `file_size` INTEGER,
            `content_hash` TEXT,
            `library_added_at_ms` INTEGER,
            `source_created_at_ms` INTEGER,
            `source_modified_at_ms` INTEGER,
            `migrated_at_ms` INTEGER,
            `finalized_at_ms` INTEGER,
            `updated_at_ms` INTEGER NOT NULL DEFAULT 0,
            `needs_reconcile` INTEGER NOT NULL DEFAULT 0,
            `last_error_code` TEXT,
            `metadata_name` TEXT,
            `locator_hint` TEXT,
            `title_preview` TEXT,
            `artist_preview` TEXT,
            `cover_key_preview` TEXT,
            `downloaded_at_ms` INTEGER,
            `metadata_revision` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`library_id`, `stable_key`)
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_managed_library_item_artifact`
        ON `managed_library_item` (`artifact_id`)
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `legacy_download_upgrade_payload` (
            `stable_key` TEXT NOT NULL PRIMARY KEY,
            `payload_json` TEXT NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `legacy_download_upgrade_quarantine` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `stable_key` TEXT NOT NULL,
            `payload_json` TEXT NOT NULL,
            `reason` TEXT NOT NULL,
            `quarantined_at_ms` INTEGER NOT NULL,
            UNIQUE(`stable_key`, `payload_json`)
        )
        """.trimIndent()
    )
}
