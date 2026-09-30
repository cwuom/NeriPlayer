package moe.ouom.neriplayer.data.sync.remote

import java.io.IOException

object WebDavConditionalWrite {
    fun <T> execute(
        createOnly: Boolean,
        expectedFingerprint: String?,
        write: (allowUnconditionalWrite: Boolean) -> Result<T>,
        readFingerprint: () -> Result<String>,
        isMissingToken: (Throwable?) -> Boolean,
        conflictError: () -> Exception
    ): Result<T> {
        val direct = write(false)
        if (createOnly || !isMissingToken(direct.exceptionOrNull())) return direct
        if (expectedFingerprint.isNullOrBlank()) return direct
        return writeAfterRevalidation(expectedFingerprint, write, readFingerprint, conflictError)
    }

    private fun <T> writeAfterRevalidation(
        expectedFingerprint: String,
        write: (Boolean) -> Result<T>,
        readFingerprint: () -> Result<String>,
        conflictError: () -> Exception
    ): Result<T> {
        val fingerprint = readFingerprint()
        if (fingerprint.isFailure) {
            return Result.failure(fingerprint.exceptionOrNull() ?: IOException("Failed to revalidate remote data"))
        }
        if (!shouldAllowUnconditionalWebDavWrite(expectedFingerprint, fingerprint.getOrThrow())) {
            return Result.failure(conflictError())
        }
        return write(true)
    }
}
