package moe.ouom.neriplayer.core.download.storage

import android.net.Uri
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadReferenceDeleteExecutor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageTypedDestructiveBoundaryTest {

    @Test
    fun `destructive executor must not expose raw string and uri delete boundary`() {
        val rawDeleteMethods = ManagedDownloadReferenceDeleteExecutor::class.java
            .declaredMethods
            .filter { method ->
                method.name == "deleteContentReference" &&
                    method.parameterTypes.any { it == String::class.java } &&
                    method.parameterTypes.any { it == Uri::class.java }
            }

        assertTrue(
            "destructive SAF cleanup must receive TrustedManagedRef and a typed result",
            rawDeleteMethods.isEmpty()
        )
    }

    @Test
    fun `reference io must not expose boolean destructive delete result`() {
        val rawBooleanDeletes = Class.forName(
            "moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo"
        ).declaredMethods.filter { method ->
            method.name == "deleteContentReference" &&
                method.returnType == Boolean::class.javaPrimitiveType
        }

        assertTrue(
            "provider failure and permission loss must remain typed, not become Boolean false",
            rawBooleanDeletes.isEmpty()
        )
    }

    @Test
    fun `cover materialization does not collapse provider failures to null`() {
        val source = readSource(
            "src/main/java/moe/ouom/neriplayer/core/download/storage/metadata/" +
                "ManagedDownloadCoverAssetStore.kt"
        )

        assertTrue(source.contains("StorageLookupResult.PermissionLost"))
        assertTrue(source.contains("StorageLookupResult.ProviderFailure"))
        assertFalse(source.contains("catch (_: Throwable)"))
    }

    private fun readSource(relativePath: String): String {
        return sequenceOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath")
        ).firstOrNull(File::isFile)?.readText()
            ?: throw IllegalStateException("source file not found: $relativePath")
    }
}
