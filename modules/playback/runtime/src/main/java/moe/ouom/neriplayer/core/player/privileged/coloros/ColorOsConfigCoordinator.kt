package moe.ouom.neriplayer.core.player.privileged.coloros

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ColorOsConfigCoordinator(
    private val applicationId: String,
    private val assetLoader: () -> ColorOsSystemUiAsset,
    private val journal: ColorOsConfigJournal
) {
    suspend fun discardRecovery(): ColorOsConfigResult = withContext(Dispatchers.IO) {
        journal.serialized {
            try {
                val state = journal.load()
                    ?: return@serialized ColorOsConfigResult(ColorOsConfigStatus.NOT_CONFIGURED,
                        detailCode = "no_recovery_record")
                if (state.applicationId != applicationId) {
                    return@serialized ColorOsConfigResult(ColorOsConfigStatus.CONFLICT,
                        detailCode = "asset_or_owner_changed")
                }
                if (state.pending || state.uncertain) {
                    return@serialized ColorOsConfigResult(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN,
                        detailCode = "write_not_settled")
                }
                journal.clear()
                ColorOsConfigResult(ColorOsConfigStatus.RECOVERY_DISCARDED)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ColorOsConfigException) {
                ColorOsConfigResult(exception.status, detailCode = exception.detailCode)
            } catch (_: Exception) {
                ColorOsConfigResult(ColorOsConfigStatus.ERROR, detailCode = "recovery_discard_failed")
            }
        }
    }

    suspend fun inspect(backend: ColorOsConfigBackend): ColorOsConfigResult = run(backend) { asset, current, state ->
        if (current == null) return@run result(backend, ColorOsConfigStatus.NOT_CONFIGURED)
        val enabled = isEffectivelyEnabled(current, asset)
        result(backend, if (enabled) ColorOsConfigStatus.ENABLED else ColorOsConfigStatus.READY,
            state != null && current != state.baseline && asset.fingerprint == state.assetFingerprint)
    }

    suspend fun apply(backend: ColorOsConfigBackend): ColorOsConfigResult = run(backend) { asset, current, state ->
        if (current == null) return@run result(backend, ColorOsConfigStatus.UNSUPPORTED, detail = "row_creation_unsupported")
        if (isEffectivelyEnabled(current, asset)) {
            if (state != null && !state.restoring && !state.pending) {
                return@run reload(backend, state)
            }
            return@run result(backend, ColorOsConfigStatus.ENABLED, state != null)
        }
        val version = nextVersion(current, asset, reserveRestoreVersion = true)
        val xml = ColorOsConfigXml.enable(current.xml, applicationId, version)
        val target = replacement(current, xml, version, true)
        write(backend, asset, ColorOsConfigJournal.State(applicationId, asset.fingerprint,
            state?.baseline ?: current, current, target, false, true, false, state?.uncertain ?: false))
    }

    suspend fun restore(backend: ColorOsConfigBackend): ColorOsConfigResult = run(backend) { asset, current, state ->
        if (state == null) return@run result(backend, ColorOsConfigStatus.NOT_CONFIGURED, detail = "no_recovery_record")
        if (current == null) return@run result(backend, ColorOsConfigStatus.CONFLICT, detail = "row_missing")
        if (state.restoring && !state.pending && current == state.target) return@run reload(backend, state)
        if (current == state.baseline) {
            if (state.uncertain) return@run result(backend, ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, detail = "write_not_observed")
            journal.clear()
            return@run result(backend, ColorOsConfigStatus.NOT_CONFIGURED, detail = "unsubmitted_intent_cleared")
        }
        val version = maxOf(nextVersion(current, asset), ColorOsConfigXml.version(state.baseline.xml) + 1)
        val xml = ColorOsConfigXml.withVersion(state.baseline.xml, version)
        val target = replacement(state.baseline, xml, version, false)
        write(backend, asset, state.copy(expected = current, target = target,
            restoring = true, pending = true, reloadRequested = false))
    }

    private suspend fun run(
        backend: ColorOsConfigBackend,
        operation: suspend (ColorOsSystemUiAsset, ColorOsRusRow?, ColorOsConfigJournal.State?) -> ColorOsConfigResult
    ): ColorOsConfigResult = withContext(Dispatchers.IO) {
        journal.serialized {
            try {
                val asset = assetLoader()
                val saved = journal.load()
                if (saved != null && (saved.applicationId != applicationId || saved.assetFingerprint != asset.fingerprint)) {
                    return@serialized result(backend, ColorOsConfigStatus.CONFLICT, detail = "asset_or_owner_changed")
                }
                ColorOsConfigXml.version(asset.xml)
                val current = backend.read()
                val state = reconcile(saved, current)
                verifyAsset(asset)
                operation(asset, current, state)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ColorOsConfigException) {
                result(backend, exception.status, detail = exception.detailCode)
            } catch (_: SecurityException) {
                result(backend, ColorOsConfigStatus.PERMISSION_DENIED, detail = "permission_denied")
            } catch (_: Exception) {
                result(backend, ColorOsConfigStatus.ERROR, detail = "configuration_failed")
            }
        }
    }

    private fun reconcile(saved: ColorOsConfigJournal.State?, current: ColorOsRusRow?): ColorOsConfigJournal.State? {
        if (saved == null) return null
        if (current == saved.target) {
            if (!saved.pending) return saved
            return saved.copy(pending = false, uncertain = false).also(journal::save)
        }
        if (saved.pending && current == saved.expected) return saved
        throw ColorOsConfigException(ColorOsConfigStatus.CONFLICT, "recovery_value_changed")
    }

    private suspend fun write(
        backend: ColorOsConfigBackend,
        asset: ColorOsSystemUiAsset,
        state: ColorOsConfigJournal.State
    ): ColorOsConfigResult {
        verifyAsset(asset)
        // a lost response can leave an in-flight provider write even when a later read still sees the baseline
        journal.save(state.copy(uncertain = true))
        verifyAsset(asset)
        val count = try {
            backend.compareAndSet(state.expected, state.target)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ColorOsConfigException) {
            if (exception.status == ColorOsConfigStatus.UNSUPPORTED ||
                exception.status == ColorOsConfigStatus.PERMISSION_DENIED ||
                exception.status == ColorOsConfigStatus.CONFLICT) journal.save(state)
            return result(backend, exception.status, detail = exception.detailCode)
        } catch (_: Exception) {
            return result(backend, ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, detail = "submission_failed")
        }
        if (count != 1) {
            if (count == 0) journal.save(state)
            return result(backend, ColorOsConfigStatus.CONFLICT, detail = "cas_count_$count")
        }
        val actual = try {
            backend.read()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return result(backend, ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, detail = "readback_failed")
        }
        if (actual != state.target) return result(backend, ColorOsConfigStatus.READBACK_MISMATCH, detail = "row_readback_mismatch")
        val committed = state.copy(pending = false, uncertain = false)
        journal.save(committed)
        return reload(backend, committed)
    }

    private suspend fun reload(backend: ColorOsConfigBackend, state: ColorOsConfigJournal.State): ColorOsConfigResult {
        try {
            backend.requestReload()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ColorOsConfigException) {
            return result(backend, ColorOsConfigStatus.RELOAD_FAILED, true, exception.detailCode)
        } catch (_: SecurityException) {
            return result(backend, ColorOsConfigStatus.RELOAD_FAILED, true, "reload_permission_denied")
        } catch (_: Exception) {
            return result(backend, ColorOsConfigStatus.RELOAD_FAILED, true, "reload_request_failed")
        }
        if (state.restoring) journal.clear() else journal.save(state.copy(reloadRequested = true))
        return result(backend, if (state.restoring) ColorOsConfigStatus.RESTORED_RELOAD_REQUESTED
            else ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, !state.restoring)
    }

    private fun nextVersion(current: ColorOsRusRow, asset: ColorOsSystemUiAsset, reserveRestoreVersion: Boolean = false): Long {
        val maximum = maxOf(ColorOsConfigXml.checkedVersion(current.version),
            ColorOsConfigXml.version(current.xml), ColorOsConfigXml.version(asset.xml))
        val ceiling = Int.MAX_VALUE.toLong() - if (reserveRestoreVersion) 1 else 0
        if (maximum >= ceiling) throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "version_overflow")
        return maximum + 1
    }

    private fun isEffectivelyEnabled(current: ColorOsRusRow, asset: ColorOsSystemUiAsset): Boolean {
        val rowVersion = ColorOsConfigXml.checkedVersion(current.version)
        val xmlVersion = ColorOsConfigXml.version(current.xml)
        val assetVersion = ColorOsConfigXml.version(asset.xml)
        if (rowVersion < assetVersion || xmlVersion < assetVersion) {
            throw ColorOsConfigException(ColorOsConfigStatus.CONFLICT, "rus_older_than_asset")
        }
        return ColorOsConfigXml.isEnabled(current.xml, applicationId) &&
            (!current.hasIsOpen || current.isOpen == "1") && rowVersion > assetVersion && xmlVersion > assetVersion
    }

    private fun replacement(source: ColorOsRusRow, xml: String, version: Long, enabling: Boolean): ColorOsRusRow = source.copy(
        version = version.toString(), xml = xml,
        md5 = if (source.hasMd5) MessageDigest.getInstance("MD5").digest(xml.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } else source.md5,
        isOpen = if (source.hasIsOpen && enabling) "1" else source.isOpen
    )

    private fun verifyAsset(expected: ColorOsSystemUiAsset) {
        if (assetLoader().fingerprint != expected.fingerprint) throw ColorOsConfigException(ColorOsConfigStatus.CONFLICT, "asset_changed")
    }

    private fun result(backend: ColorOsConfigBackend, status: ColorOsConfigStatus, canRestore: Boolean = false,
        detail: String? = null) = ColorOsConfigResult(status, backend.uid, canRestore, detail)
}
