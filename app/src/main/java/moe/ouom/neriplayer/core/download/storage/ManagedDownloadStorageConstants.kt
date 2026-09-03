package moe.ouom.neriplayer.core.download.storage

internal const val ROOT_DIR_NAME = "NeriPlayer"
internal const val COVER_SUBDIRECTORY = "Covers"
internal const val LYRIC_SUBDIRECTORY = "Lyrics"
internal const val DOWNLOAD_STAGING_DIR_NAME = "download_staging"
/** 应用级下载提交凭据统一放在隐藏临时目录，避免污染媒体根目录 */
internal const val DOWNLOAD_TEMPORARY_DIR_NAME = ".tmp"
internal const val DOWNLOAD_STAGING_FILE_PREFIX = "npdl_"
internal const val DOWNLOAD_STAGING_FILE_SUFFIX = ".download"
internal const val DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX = ".hls.json"
internal const val DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX = ".resume.json"
internal const val DOWNLOAD_STAGING_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
internal const val PENDING_AUDIO_WRITE_MARKER = ".npdl_pending"
internal const val NO_MEDIA_FILE_NAME = ".nomedia"
internal const val SNAPSHOT_CACHE_FILE_NAME = "managed_download_snapshot_v1.json"
internal const val PENDING_DOWNLOAD_QUEUE_FILE_NAME = "pending_download_queue_v1.json"
internal const val CANCELLED_DOWNLOAD_KEYS_FILE_NAME = "cancelled_download_keys_v1.json"
internal const val PENDING_DOWNLOAD_QUEUE_VERSION = 1
internal const val CANCELLED_DOWNLOAD_KEYS_VERSION = 1
internal const val SNAPSHOT_CACHE_PERSIST_DEBOUNCE_MS = 1_200L
internal const val TREE_ROOT_CACHE_VALIDATE_INTERVAL_MS = 1_500L
internal const val TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS = 2_000L
internal const val TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS = 60_000L
internal const val FILE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS = 60_000L
internal const val SAF_PARENT_DOCUMENT_CACHE_VALIDATE_INTERVAL_MS = 30_000L
internal const val MIGRATION_PROGRESS_EMIT_INTERVAL_MS = 150L
@Suppress("SpellCheckingInspection")
internal const val METADATA_SUFFIX = ".npmeta.json"
internal const val PENDING_METADATA_SUFFIX = ".npmeta.pending.json"
internal const val MANAGED_LIBRARY_MANIFEST_FILE_NAME = "NeriLibrary.json"
internal const val MANAGED_LIBRARY_INDEX_DIR_NAME = "NeriIndex"
internal const val OPERATION_MANIFEST_NAME = "operation.json"
// 迁移 I/O 有界并发，避免大曲库被单文件调度开销拖慢
internal const val MIGRATION_COPY_PARALLELISM = 16
internal const val MIGRATION_TREE_COPY_PARALLELISM = 8
internal const val MIGRATION_REWRITE_PARALLELISM = 8
internal const val MIGRATION_TREE_REWRITE_PARALLELISM = 8
internal const val MIGRATION_DELETE_PARALLELISM = 16
// SAF 删除是独立文档操作，使用与普通引用删除相同的有界并发
internal const val MIGRATION_TREE_DELETE_PARALLELISM = 16
internal const val MIGRATION_PENDING_ARTIFACT_LOG_SAMPLE_SIZE = 4
/** 迁移被未收敛下载临时文件阻断时使用的稳定原因标识 */
internal const val MIGRATION_PENDING_ARTIFACT_BLOCKED_ERROR_CODE =
    "MIGRATION_PENDING_ARTIFACT_BLOCKED"
internal const val MIGRATION_IO_MAX_ATTEMPTS = 3
internal const val MIGRATION_IO_RETRY_DELAY_MS = 150L
internal const val SAF_DELETE_MAX_ATTEMPTS = 3
internal const val SAF_DELETE_RETRY_DELAY_MS = 80L
internal const val SAF_REFERENCE_DELETE_PARALLELISM = 8
internal const val STREAM_COPY_BUFFER_SIZE_BYTES = 1 * 1024 * 1024
internal const val SAF_COMMITTED_SIZE_TOLERANCE_BYTES = 1L

internal val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "webm", "eac3")
internal val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
