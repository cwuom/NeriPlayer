package moe.ouom.neriplayer.data.sync.runtime

data class SyncProtocolUpgradeChallenge(val targetId: String, val fingerprint: String) {
    init {
        require(targetId.matches(Sha256) && fingerprint.matches(Sha256)) { "Invalid sync upgrade challenge" }
    }

    private companion object { val Sha256 = Regex("[0-9a-f]{64}") }
}

class SyncProtocolUpgradeRequiredException(
    message: String,
    val challenge: SyncProtocolUpgradeChallenge? = null
) : IllegalStateException(message)
