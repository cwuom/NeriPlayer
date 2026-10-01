package moe.ouom.neriplayer.data.model.sync

enum class SyncWorkerOutcome { SUCCESS, RETRY, FAILURE }
enum class SyncWorkerFailureKind { OTHER, AUTHENTICATION, MISSING_CONDITION, ALREADY_RUNNING }
enum class SyncProvider { GITHUB, WEBDAV }

data class SyncWorkerFailureDecision(val outcome: SyncWorkerOutcome, val notify: Boolean)
