package moe.ouom.neriplayer.data.model.download.execution

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadOperationStateTest {

    @Test
    fun `every known state round trips through its wire name`() {
        DownloadOperationState.entries
            .filterNot { it == DownloadOperationState.UNKNOWN }
            .forEach { state -> assertEquals(state, DownloadOperationState.parse(state.wireName)) }
        assertEquals(
            DownloadOperationState.METADATA_ACTION_REQUIRED,
            DownloadOperationState.parse(" $METADATA_ACTION_REQUIRED_OPERATION_STATE ")
        )
    }

    @Test
    fun `missing or foreign wire names map to unknown`() {
        assertEquals(DownloadOperationState.UNKNOWN, DownloadOperationState.parse(null))
        assertEquals(DownloadOperationState.UNKNOWN, DownloadOperationState.parse(""))
        assertEquals(DownloadOperationState.UNKNOWN, DownloadOperationState.parse("running"))
    }
}
