@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Parcelize
@Serializable
data class SyncCausalToken(
    @ProtoNumber(1) val deviceId: String,
    @ProtoNumber(2) val counter: Long
) : Parcelable {
    init {
        require(deviceId.isNotBlank()) { "SyncCausalToken.deviceId must not be blank" }
        require(counter > 0L) { "SyncCausalToken.counter must be positive" }
    }

    companion object {
        val DETERMINISTIC_COMPARATOR: Comparator<SyncCausalToken> =
            compareBy<SyncCausalToken>(SyncCausalToken::deviceId)
                .thenBy(SyncCausalToken::counter)
    }
}

internal fun Iterable<SyncCausalToken>?.normalizedSyncCausalTokens(): List<SyncCausalToken> {
    return this
        ?.distinct()
        ?.sortedWith(SyncCausalToken.DETERMINISTIC_COMPARATOR)
        .orEmpty()
}
