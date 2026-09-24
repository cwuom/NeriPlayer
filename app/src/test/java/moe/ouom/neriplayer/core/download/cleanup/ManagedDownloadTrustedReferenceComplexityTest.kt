package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadDeletePolicy
import moe.ouom.neriplayer.core.download.storage.operation.content.resolveTrustedManagedReferences
import org.junit.Assert.*
import org.junit.Test

class ManagedDownloadTrustedReferenceComplexityTest {
    @Test fun `five thousand trusted references are indexed in one traversal`() {
        val entries = (0 until 5000).map { TrustedManagedRef(StorageReference.FileRef("$it.mp3"), "/library/$it.mp3") }
        var visits = 0
        val counted = object : AbstractSet<TrustedManagedRef>() {
            override val size get() = entries.size
            override fun iterator(): Iterator<TrustedManagedRef> = object : Iterator<TrustedManagedRef> {
                val delegate = entries.iterator()
                override fun hasNext() = delegate.hasNext()
                override fun next(): TrustedManagedRef { visits++; return delegate.next() }
            }
        }
        val resolved = ManagedDownloadStorage.resolveTrustedManagedReferences(entries.map { it.externalReference },
            ManagedDownloadDeletePolicy(listOf("/library"), emptyList(), counted))
        assertEquals(entries, resolved)
        assertEquals(5000, visits)
    }
}
