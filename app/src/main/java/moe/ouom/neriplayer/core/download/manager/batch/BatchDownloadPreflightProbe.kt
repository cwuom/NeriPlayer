package moe.ouom.neriplayer.core.download.manager.batch

import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup

/** 同一次启动的两轮预检共享预算和已取得的引用证据 */
internal class BatchDownloadPreflightProbe(
    private val maxReferences: Int = 64,
    private val budgetNanos: Long = 500_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val inspectReference: (String) -> ManagedDownloadReferenceLookup.Result
) {
    private val evidenceByReference = mutableMapOf<String, ManagedDownloadReferenceLookup.Result>()
    private var startedAtNanos: Long? = null
    private var inspectedReferences = 0

    val isExhausted: Boolean
        get() = inspectedReferences >= maxReferences ||
            startedAtNanos?.let { nanoTime() - it >= budgetNanos } == true

    fun inspect(reference: String): ManagedDownloadReferenceLookup.Result? {
        val normalized = reference.trim()
        evidenceByReference[normalized]?.let { return it }
        if (isExhausted) return null
        if (startedAtNanos == null) startedAtNanos = nanoTime()
        inspectedReferences++
        // 同步 Provider 调用不能强制中止，时间预算只阻止后续调用
        return inspectReference(normalized).also { evidenceByReference[normalized] = it }
    }
}
