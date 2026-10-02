package moe.ouom.neriplayer.data.sync.archive

internal object SyncArchiveManifestValidation {
    fun validate(manifest: SyncArchiveManifest) {
        require(manifest.protocol == 3) { "Unsupported sync protocol" }
        validateNonnegativeTotals(manifest)
        validateCounts(manifest)
        validateRoot(manifest)
        require(SyncArchiveRecords.header(manifest.header) == manifest.header) { "Sync manifest embeds unexpected records" }
        manifest.legacyLyrics?.validate()
    }

    private fun validateNonnegativeTotals(manifest: SyncArchiveManifest) {
        require(manifest.recordCount >= 0) { "Invalid sync manifest totals" }
        require(manifest.rawDataBytes >= 0) { "Invalid sync manifest totals" }
        require(manifest.chunkCount >= 0) { "Invalid sync manifest totals" }
    }

    private fun validateCounts(manifest: SyncArchiveManifest) {
        require(manifest.chunkCount <= manifest.rawDataBytes) { "Invalid sync manifest counts" }
        require(manifest.recordCount <= manifest.rawDataBytes / 5) { "Invalid sync manifest counts" }
    }

    private fun validateRoot(manifest: SyncArchiveManifest) {
        if (manifest.root == null) {
            validateEmpty(manifest)
        } else {
            require(manifest.chunkCount > 0L) { "Invalid sync manifest root" }
        }
    }

    private fun validateEmpty(manifest: SyncArchiveManifest) {
        require(manifest.chunkCount == 0L) { "Invalid sync manifest root" }
        require(manifest.rawDataBytes == 0L) { "Empty sync manifest contains bytes" }
    }
}
