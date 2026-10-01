package moe.ouom.neriplayer.core.download.storage

const val COVER_SUBDIRECTORY = "Covers"
/** 应用级下载提交凭据统一放在隐藏临时目录，避免污染媒体根目录 */
const val DOWNLOAD_TEMPORARY_DIR_NAME = ".tmp"
const val PENDING_AUDIO_WRITE_MARKER = ".npdl_pending"
const val METADATA_SUFFIX = ".npmeta.json"
const val PENDING_METADATA_SUFFIX = ".npmeta.pending.json"
// Provider 在批内串行删除，小批次才能及时确认进度并让空闲 worker 接续工作
const val SAF_REFERENCE_DELETE_BATCH_SIZE = 16
val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "webm", "eac3")
val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
