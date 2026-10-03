package moe.ouom.neriplayer.data.sync.runtime

data class SyncProtocolUpgradeChallenge(
    val targetId: String,
    val fingerprint: String,
    val fromVersion: Int = 0,
    val toVersion: Int = 4
) {
    init {
        require(targetId.matches(Sha256) && fingerprint.matches(Sha256)) { "Invalid sync upgrade challenge" }
        require(fromVersion >= 0 && toVersion > fromVersion) { "Invalid sync upgrade versions" }
    }

    private companion object { val Sha256 = Regex("[0-9a-f]{64}") }
}

class SyncProtocolUpgradeRequiredException(
    message: String,
    val challenge: SyncProtocolUpgradeChallenge? = null
) : IllegalStateException(message)
