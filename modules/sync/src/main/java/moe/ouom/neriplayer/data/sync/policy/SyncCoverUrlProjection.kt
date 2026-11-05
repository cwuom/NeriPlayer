package moe.ouom.neriplayer.data.sync.policy

object SyncCoverUrlProjection {
    private class Binding(
        val prepare: suspend () -> Unit,
        val project: (String) -> String?
    )

    @Volatile private var binding = Binding({}, { it })

    fun bind(prepare: suspend () -> Unit = {}, project: (String) -> String? = { it }) {
        binding = Binding(prepare, project)
    }

    suspend fun prepare() {
        binding.prepare()
    }

    fun project(raw: String): String? = binding.project(raw)
}
