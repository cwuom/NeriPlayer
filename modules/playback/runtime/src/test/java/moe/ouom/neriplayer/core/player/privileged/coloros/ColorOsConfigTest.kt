package moe.ouom.neriplayer.core.player.privileged.coloros

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ColorOsConfigTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `xml edits only own attributes and version preserving foreign bytes`() {
        val xml = fixture("<packageName name='moe.ouom.neriplayer' lyricEntrance='7' custom='keep'/>")
        val patched = ColorOsConfigXml.enable(xml, APP, 11)
        assertEquals(xml.replace("<version>10</version>", "<version>11</version>")
            .replace("<whitelist>", "<whitelist><packageName name=\"$APP\"/>")
            .replace("lyricEntrance='7'", "lyricEntrance='52'"), patched)
    }

    @Test fun `existing own policy retains unknown attributes and whitelist is not duplicated`() {
        val xml = fixture("<packageName name='$APP' actionPriority='9' keep='a&#58;b'/>")
            .replace("<whitelist>", "<whitelist><packageName name='$APP'/>")
        val patched = ColorOsConfigXml.enable(xml, APP, 11)
        assertTrue(patched.contains("actionPriority='9' keep='a&#58;b' lyricEntrance=\"52\""))
        assertEquals(2, Regex("name=['\"]$APP['\"]").findAll(patched).count())
        assertTrue(ColorOsConfigXml.isEnabled(patched, APP))
    }

    @Test fun `xxe namespaces exclusions duplicates and unknown schema are rejected`() {
        val inputs = listOf(
            "<!DOCTYPE config [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + fixture(),
            fixture().replace("<config>", "<config xmlns='urn:unknown'>"),
            fixture().replace("<excludeList/>", "<excludeList><packageName name='$APP'/></excludeList>"),
            fixture().replace("</config>", "<policy/></config>"),
            fixture().replace("<whitelist>", "<whitelist><unknown/>"),
            fixture().replace("<policy>", "<policy><packageName name='$APP'/><packageName name='$APP'/>")
        )
        inputs.forEach { xml ->
            try {
                ColorOsConfigXml.enable(xml, APP, 11)
                throw AssertionError("unsafe schema accepted")
            } catch (expected: ColorOsConfigException) {
                assertEquals(ColorOsConfigStatus.UNSUPPORTED, expected.status)
            }
        }
    }

    @Test fun `apply uses current RUS not asset and advances database xml and asset versions`() = runTest {
        val backend = Backend(ColorOsRusRow("39", fixture().replace("<version>10</version>", "<version>35</version>"),
            true, "old", true, "0"))
        val journalFile = journalFile()
        val coordinator = coordinator(journalFile, asset = asset(30))
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator.apply(backend).status)
        assertEquals("40", backend.row?.version)
        assertEquals(40L, ColorOsConfigXml.version(backend.row!!.xml))
        assertTrue(backend.row!!.xml.contains("vendor='retain'"))
        assertEquals("1", backend.row?.isOpen)
        assertEquals(32, backend.row?.md5?.length)
        assertEquals(1, backend.reloads)
        assertTrue(journalFile.exists())
    }

    @Test fun `repeated apply and recreated coordinator retain initial restore baseline`() = runTest {
        val baseline = ColorOsRusRow("10", fixture())
        val backend = Backend(baseline)
        val file = journalFile()
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        val enabled = backend.row
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        assertEquals(enabled, backend.row)
        assertEquals(1, backend.writes)
        assertEquals(2, backend.reloads)
        assertEquals(baseline, ColorOsConfigJournal(file).load()?.baseline)
        assertEquals(ColorOsConfigStatus.RESTORED_RELOAD_REQUESTED, coordinator(file).restore(backend).status)
        assertEquals("12", backend.row?.version)
        assertEquals(baseline.xml.replace("<version>10</version>", "<version>12</version>"), backend.row?.xml)
        assertFalse(file.exists())
    }

    @Test fun `explicit apply retries a previously requested reload without another configuration write`() = runTest {
        val baseline = ColorOsRusRow("10", fixture())
        val file = journalFile()
        val backend = Backend(baseline)
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        val target = backend.row
        backend.reloadFailure = SecurityException("denied")

        assertEquals(ColorOsConfigStatus.RELOAD_FAILED, coordinator(file).apply(backend).status)
        assertEquals(target, backend.row)
        assertEquals(1, backend.writes)
        assertEquals(baseline, ColorOsConfigJournal(file).load()?.baseline)

        backend.reloadFailure = null
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        assertEquals(target, backend.row)
        assertEquals(1, backend.writes)
        assertEquals(2, backend.reloads)
        assertEquals(baseline, ColorOsConfigJournal(file).load()?.baseline)
    }

    @Test fun `inspect of a committed own target never retries reload`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture()))
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        val saved = file.readBytes().toList()

        val result = coordinator(file).inspect(backend)

        assertEquals(ColorOsConfigStatus.ENABLED, result.status)
        assertTrue(result.canRestore)
        assertEquals(1, backend.writes)
        assertEquals(1, backend.reloads)
        assertEquals(saved, file.readBytes().toList())
    }

    @Test fun `already enabled external configuration never acquires a journal or requests reload`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("20", enabledFixture(20)))

        val result = coordinator(file).apply(backend)

        assertEquals(ColorOsConfigStatus.ENABLED, result.status)
        assertFalse(result.canRestore)
        assertEquals(0, backend.writes)
        assertEquals(0, backend.reloads)
        assertFalse(file.exists())
    }

    @Test fun `discard of a settled external writer conflict only clears local recovery`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture()))
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        val external = backend.row!!.copy(xml = backend.row!!.xml.replace("vendor='retain'", "vendor='changed'"))
        backend.row = external
        assertEquals(ColorOsConfigStatus.CONFLICT, coordinator(file).inspect(backend).status)
        val localOnly = ColorOsConfigCoordinator(APP, { throw AssertionError("asset must not be loaded") },
            ColorOsConfigJournal(file))

        val result = localOnly.discardRecovery()

        assertEquals(ColorOsConfigStatus.RECOVERY_DISCARDED, result.status)
        assertEquals(null, result.uid)
        assertFalse(result.canRestore)
        assertFalse(file.exists())
        assertEquals(external, backend.row)
        assertEquals(1, backend.writes)
        assertEquals(1, backend.reloads)
    }

    @Test fun `discard of a settled OTA conflict permits a new confirmed vendor baseline`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture()))
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        val newAsset = asset(20).copy(lastUpdateTime = 2)
        val vendorBaseline = ColorOsRusRow("20", fixture()
            .replace("<version>10</version>", "<version>20</version>")
            .replace("vendor='retain'", "vendor='new'"))
        backend.row = vendorBaseline
        val afterOta = coordinator(file, newAsset)
        assertEquals(ColorOsConfigStatus.CONFLICT, afterOta.apply(backend).status)

        assertEquals(ColorOsConfigStatus.RECOVERY_DISCARDED, afterOta.discardRecovery().status)

        assertEquals(vendorBaseline, backend.row)
        assertEquals(1, backend.writes)
        assertEquals(1, backend.reloads)
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, afterOta.apply(backend).status)
        assertEquals(vendorBaseline, ColorOsConfigJournal(file).load()?.baseline)
        assertEquals(ColorOsConfigStatus.RESTORED_RELOAD_REQUESTED, afterOta.restore(backend).status)
        assertEquals(vendorBaseline.xml.replace("<version>20</version>", "<version>22</version>"), backend.row?.xml)
        assertFalse(file.exists())
    }

    @Test fun `discard refuses every pending or uncertain journal without loading the asset`() = runTest {
        val baseline = ColorOsRusRow("10", fixture())
        val target = ColorOsRusRow("11", enabledFixture(11))
        listOf(true to false, false to true, true to true).forEach { (pending, uncertain) ->
            val file = journalFile()
            ColorOsConfigJournal(file).save(ColorOsConfigJournal.State(APP, asset(10).fingerprint,
                baseline, baseline, target, false, pending, false, uncertain))
            val saved = file.readBytes().toList()
            val localOnly = ColorOsConfigCoordinator(APP, { throw AssertionError("asset must not be loaded") },
                ColorOsConfigJournal(file))

            val result = localOnly.discardRecovery()

            assertEquals(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, result.status)
            assertEquals("write_not_settled", result.detailCode)
            assertEquals(saved, file.readBytes().toList())
        }
    }

    @Test fun `discard of settled restore reload failure never rewrites restored vendor policy`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture()))
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        backend.reloadFailure = SecurityException("denied")
        assertEquals(ColorOsConfigStatus.RELOAD_FAILED, coordinator(file).restore(backend).status)
        val restored = backend.row

        assertEquals(ColorOsConfigStatus.RECOVERY_DISCARDED, coordinator(file).discardRecovery().status)

        assertFalse(file.exists())
        assertEquals(restored, backend.row)
        assertEquals(2, backend.writes)
        assertEquals(1, backend.reloads)
    }

    @Test fun `discard preserves corrupt or differently owned recovery records`() = runTest {
        val corrupt = journalFile().also { it.writeText("corrupt") }
        val corruptResult = coordinator(corrupt).discardRecovery()
        assertEquals(ColorOsConfigStatus.ERROR, corruptResult.status)
        assertEquals("journal_invalid", corruptResult.detailCode)
        assertEquals("corrupt", corrupt.readText())

        val file = journalFile()
        val baseline = ColorOsRusRow("10", fixture())
        ColorOsConfigJournal(file).save(ColorOsConfigJournal.State("other.application", asset(10).fingerprint,
            baseline, baseline, ColorOsRusRow("11", enabledFixture(11)), false, false, true, false))
        val saved = file.readBytes().toList()
        val otherResult = coordinator(file).discardRecovery()
        assertEquals(ColorOsConfigStatus.CONFLICT, otherResult.status)
        assertEquals("asset_or_owner_changed", otherResult.detailCode)
        assertEquals(saved, file.readBytes().toList())
    }

    @Test fun `discard without a recovery record does not load SystemUI`() = runTest {
        val file = journalFile()
        val localOnly = ColorOsConfigCoordinator(APP, { throw AssertionError("asset must not be loaded") },
            ColorOsConfigJournal(file))

        val result = localOnly.discardRecovery()

        assertEquals(ColorOsConfigStatus.NOT_CONFIGURED, result.status)
        assertEquals("no_recovery_record", result.detailCode)
        assertFalse(file.exists())
    }

    @Test fun `no row refuses creation and overflow refuses writing`() = runTest {
        val missing = Backend(null)
        assertEquals(ColorOsConfigStatus.UNSUPPORTED, coordinator(journalFile()).apply(missing).status)
        assertEquals(0, missing.writes)
        val full = Backend(ColorOsRusRow(Int.MAX_VALUE.toString(), fixture()))
        assertEquals(ColorOsConfigStatus.UNSUPPORTED, coordinator(journalFile()).apply(full).status)
        assertEquals(0, full.writes)
    }

    @Test fun `CAS zero or multiple rows retains journal and never reloads`() = runTest {
        for (count in listOf(0, 2)) {
            val file = journalFile()
            val backend = Backend(ColorOsRusRow("10", fixture())).also { it.casCount = count }
            assertEquals(ColorOsConfigStatus.CONFLICT, coordinator(file).apply(backend).status)
            assertTrue(file.exists())
            assertEquals(0, backend.reloads)
        }
    }

    @Test fun `intent is durable before submission and submitted unknown result reconciles`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture())).also {
            it.beforeWrite = { assertTrue(file.exists()) }
            it.writeFailure = ColorOsConfigException(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, "timeout")
        }
        assertEquals(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, coordinator(file).apply(backend).status)
        assertTrue(file.exists())
        backend.writeFailure = null
        assertEquals(ColorOsConfigStatus.ENABLED, coordinator(file).inspect(backend).status)
        assertTrue(coordinator(file).inspect(backend).canRestore)
        assertEquals(ColorOsConfigStatus.RESTORED_RELOAD_REQUESTED, coordinator(file).restore(backend).status)
    }

    @Test fun `unknown outcome that never wrote can reconcile and reapply original baseline`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture())).also {
            it.commitWrite = false
            it.writeFailure = ColorOsConfigException(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, "timeout")
        }
        coordinator(file).apply(backend)
        backend.writeFailure = null
        backend.commitWrite = true
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        assertEquals(ColorOsConfigStatus.RESTORED_RELOAD_REQUESTED, coordinator(file).restore(backend).status)
        assertFalse(backend.row!!.xml.contains(APP))
    }

    @Test fun `readback mismatch or reload denial retains recoverable journal`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture())).also { it.commitWrite = false }
        assertEquals(ColorOsConfigStatus.READBACK_MISMATCH, coordinator(file).apply(backend).status)
        assertTrue(file.exists())
        val otherFile = journalFile()
        val denied = Backend(ColorOsRusRow("10", fixture())).also {
            it.reloadFailure = SecurityException("denied")
        }
        val result = coordinator(otherFile).apply(denied)
        assertEquals(ColorOsConfigStatus.RELOAD_FAILED, result.status)
        assertTrue(result.canRestore)
        assertTrue(otherFile.exists())
    }

    @Test fun `concurrent edits and OTA fingerprint changes block restore without writing`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture()))
        coordinator(file).apply(backend)
        val writes = backend.writes
        assertEquals(ColorOsConfigStatus.CONFLICT,
            coordinator(file, asset = asset(10).copy(lastUpdateTime = 2)).restore(backend).status)
        assertEquals(writes, backend.writes)
        backend.row = backend.row!!.copy(xml = backend.row!!.xml.replace("vendor='retain'", "vendor='changed'"))
        assertEquals(ColorOsConfigStatus.CONFLICT, coordinator(file).restore(backend).status)
        assertEquals(writes, backend.writes)
        assertTrue(file.exists())
    }

    @Test fun `cancel after submission propagates and leaves durable recovery`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture())).also {
            it.writeFailure = CancellationException("cancelled")
        }
        try {
            coordinator(file).apply(backend)
            throw AssertionError("cancellation swallowed")
        } catch (expected: CancellationException) {
            assertNotNull(expected)
        }
        assertTrue(file.exists())
        backend.writeFailure = null
        assertTrue(coordinator(file).inspect(backend).canRestore)
    }

    @Test fun `inspect never writes and permission denial is explicit`() = runTest {
        val backend = Backend(ColorOsRusRow("10", fixture()))
        assertEquals(ColorOsConfigStatus.READY, coordinator(journalFile()).inspect(backend).status)
        assertEquals(0, backend.writes)
        backend.readFailure = ColorOsConfigException(ColorOsConfigStatus.PERMISSION_DENIED, "provider_denied")
        val result = coordinator(journalFile()).apply(backend)
        assertEquals(ColorOsConfigStatus.PERMISSION_DENIED, result.status)
        assertEquals("provider_denied", result.detailCode)
        assertEquals(0, backend.writes)
    }

    @Test fun `a corrupted recovery journal is never bypassed or overwritten`() = runTest {
        val file = journalFile().also { it.writeText("corrupt") }
        val backend = Backend(ColorOsRusRow("10", fixture()))
        assertEquals(ColorOsConfigStatus.ERROR, coordinator(file).apply(backend).status)
        assertEquals(0, backend.writes)
        assertEquals("corrupt", file.readText())
    }

    @Test fun `restore reload failure keeps journal and a retry only resubmits reload`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture()))
        coordinator(file).apply(backend)
        backend.reloadFailure = SecurityException("denied")
        assertEquals(ColorOsConfigStatus.RELOAD_FAILED, coordinator(file).restore(backend).status)
        assertTrue(file.exists())
        assertFalse(backend.row!!.xml.contains(APP))
        val writes = backend.writes
        backend.reloadFailure = null
        assertEquals(ColorOsConfigStatus.RESTORED_RELOAD_REQUESTED, coordinator(file).restore(backend).status)
        assertEquals(writes, backend.writes)
        assertFalse(file.exists())
    }

    @Test fun `asset changing during provider read blocks the first write`() = runTest {
        val file = journalFile()
        var loadedAsset = asset(10)
        val backend = Backend(ColorOsRusRow("10", fixture())).also {
            it.afterRead = { loadedAsset = loadedAsset.copy(lastUpdateTime = 2) }
        }
        val coordinator = ColorOsConfigCoordinator(APP, { loadedAsset }, ColorOsConfigJournal(file))
        assertEquals(ColorOsConfigStatus.CONFLICT, coordinator.apply(backend).status)
        assertEquals(0, backend.writes)
    }

    @Test fun `attribute looking text inside another own attribute is preserved`() {
        val xml = fixture("<packageName name='$APP' custom=\" lyricEntrance='7'\" lyricEntrance='3'/>")
        val target = ColorOsConfigXml.enable(xml, APP, 11)
        assertTrue(target.contains("custom=\" lyricEntrance='7'\" lyricEntrance='52'"))
        assertTrue(ColorOsConfigXml.isEnabled(target, APP))
    }

    @Test fun `non whitespace policy text and nested version are unknown schemas`() {
        listOf(fixture().replace("<policy>", "<policy>unknown"),
            fixture().replace("<version>10</version>", "<nested><version>10</version></nested>"))
            .forEach { xml ->
                try {
                    ColorOsConfigXml.enable(xml, APP, 11)
                    throw AssertionError("unsafe schema accepted")
                } catch (expected: ColorOsConfigException) {
                    assertEquals(ColorOsConfigStatus.UNSUPPORTED, expected.status)
                }
            }
    }

    @Test fun `explicit restore ends an unobserved write chain and permits a new asset baseline`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture())).also { it.casCount = 0 }
        assertEquals(ColorOsConfigStatus.CONFLICT, coordinator(file).apply(backend).status)
        assertTrue(file.exists())
        assertEquals(ColorOsConfigStatus.NOT_CONFIGURED, coordinator(file).restore(backend).status)
        assertFalse(file.exists())
        backend.casCount = 1
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED,
            coordinator(file, asset = asset(10).copy(lastUpdateTime = 2)).apply(backend).status)
    }

    @Test fun `reload structured denial retains its diagnostic code`() = runTest {
        val backend = Backend(ColorOsRusRow("10", fixture())).also {
            it.reloadFailure = ColorOsConfigException(ColorOsConfigStatus.PERMISSION_DENIED, "reload_permission_denied")
        }
        val file = journalFile()
        val result = coordinator(file).apply(backend)
        assertEquals(ColorOsConfigStatus.RELOAD_FAILED, result.status)
        assertEquals("reload_permission_denied", result.detailCode)
        assertTrue(file.exists())
    }

    @Test fun `an unknown write followed by CAS zero cannot discard recovery at baseline`() = runTest {
        val file = journalFile()
        val backend = Backend(ColorOsRusRow("10", fixture())).also {
            it.commitWrite = false
            it.writeFailure = ColorOsConfigException(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, "timeout")
        }
        assertEquals(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, coordinator(file).apply(backend).status)
        backend.writeFailure = null
        backend.casCount = 0
        assertEquals(ColorOsConfigStatus.CONFLICT, coordinator(file).apply(backend).status)
        assertEquals(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, coordinator(file).restore(backend).status)
        assertTrue(file.exists())
        assertEquals(2, backend.writes)
    }

    @Test fun `apply opens an existing disabled own policy and keeps its restore baseline`() = runTest {
        val baseline = ColorOsRusRow("20", enabledFixture(20), hasIsOpen = true, isOpen = "0")
        val backend = Backend(baseline)
        val file = journalFile()
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        assertEquals("1", backend.row?.isOpen)
        assertEquals("21", backend.row?.version)
        assertEquals(21L, ColorOsConfigXml.version(backend.row!!.xml))
        assertEquals(1, backend.writes)
        assertEquals(ColorOsConfigStatus.RESTORED_RELOAD_REQUESTED, coordinator(file).restore(backend).status)
        assertEquals("0", backend.row?.isOpen)
        assertEquals(baseline.xml.replace("<version>20</version>", "<version>22</version>"), backend.row?.xml)
    }

    @Test fun `apply republishes own policy when either RUS version equals the asset`() = runTest {
        val cases = listOf(Triple("50", 40, "51"), Triple("40", 50, "51"), Triple("40", 40, "41"))
        cases.forEach { (rowVersion, xmlVersion, expectedVersion) ->
            val backend = Backend(ColorOsRusRow(rowVersion, enabledFixture(xmlVersion)))
            assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED,
                coordinator(journalFile(), asset = asset(40)).apply(backend).status)
            assertEquals(expectedVersion, backend.row?.version)
            assertEquals(expectedVersion.toLong(), ColorOsConfigXml.version(backend.row!!.xml))
            assertEquals(1, backend.writes)
            assertEquals(1, backend.reloads)
        }
    }

    @Test fun `inspect does not advertise closed or superseded own policy as enabled`() = runTest {
        val cases = listOf(ColorOsRusRow("50", enabledFixture(50), hasIsOpen = true, isOpen = "0"),
            ColorOsRusRow("50", enabledFixture(40)), ColorOsRusRow("40", enabledFixture(50)),
            ColorOsRusRow("40", enabledFixture(40)))
        cases.forEach { row ->
            val backend = Backend(row)
            assertEquals(ColorOsConfigStatus.READY,
                coordinator(journalFile(), asset = asset(40)).inspect(backend).status)
            assertEquals(0, backend.writes)
        }
    }

    @Test fun `stale RUS or XML versions conflict without overriding newer asset policies`() = runTest {
        val cases = listOf("50" to 10, "10" to 50, "10" to 10)
        cases.forEach { (rowVersion, xmlVersion) ->
            val row = ColorOsRusRow(rowVersion, enabledFixture(xmlVersion))
            val backend = Backend(row)
            val file = journalFile()
            assertEquals(ColorOsConfigStatus.CONFLICT, coordinator(file, asset = asset(40)).inspect(backend).status)
            assertEquals(ColorOsConfigStatus.CONFLICT, coordinator(file, asset = asset(40)).apply(backend).status)
            assertEquals(row, backend.row)
            assertEquals(0, backend.writes)
            assertEquals(0, backend.reloads)
            assertFalse(file.exists())
        }
    }

    @Test fun `apply refuses a last remaining version slot to preserve restore headroom`() = runTest {
        val limit = Int.MAX_VALUE - 1
        val cases = listOf(Triple(limit.toString(), 10, 10), Triple("10", limit, 10),
            Triple(limit.toString(), limit, limit))
        cases.forEach { (rowVersion, xmlVersion, assetVersion) ->
            val baseline = ColorOsRusRow(rowVersion,
                fixture().replace("<version>10</version>", "<version>$xmlVersion</version>"))
            val backend = Backend(baseline)
            val file = journalFile()
            assertEquals(ColorOsConfigStatus.UNSUPPORTED,
                coordinator(file, asset = asset(assetVersion)).apply(backend).status)
            assertEquals(baseline, backend.row)
            assertEquals(0, backend.writes)
            assertEquals(0, backend.reloads)
            assertFalse(file.exists())
        }
    }

    @Test fun `version headroom refusal keeps a preexisting recovery journal intact`() = runTest {
        val limit = Int.MAX_VALUE - 1
        val baseline = ColorOsRusRow(limit.toString(),
            fixture().replace("<version>10</version>", "<version>$limit</version>"))
        val intended = ColorOsRusRow(Int.MAX_VALUE.toString(), enabledFixture(Int.MAX_VALUE))
        val file = journalFile()
        ColorOsConfigJournal(file).save(ColorOsConfigJournal.State(APP, asset(10).fingerprint,
            baseline, baseline, intended, false, true, false, false))
        val before = file.readBytes().toList()
        val backend = Backend(baseline)
        assertEquals(ColorOsConfigStatus.UNSUPPORTED, coordinator(file).apply(backend).status)
        assertEquals(0, backend.writes)
        assertEquals(0, backend.reloads)
        assertEquals(before, file.readBytes().toList())
    }

    @Test fun `the final safe apply slot still permits a higher version restore`() = runTest {
        val initialVersion = Int.MAX_VALUE - 2
        val baseline = ColorOsRusRow(initialVersion.toString(),
            fixture().replace("<version>10</version>", "<version>$initialVersion</version>"))
        val backend = Backend(baseline)
        val file = journalFile()
        assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator(file).apply(backend).status)
        assertEquals((Int.MAX_VALUE - 1).toString(), backend.row?.version)
        assertEquals(ColorOsConfigStatus.RESTORED_RELOAD_REQUESTED, coordinator(file).restore(backend).status)
        assertEquals(Int.MAX_VALUE.toString(), backend.row?.version)
        assertEquals(Int.MAX_VALUE.toLong(), ColorOsConfigXml.version(backend.row!!.xml))
        assertFalse(backend.row!!.xml.contains(APP))
        assertFalse(file.exists())
    }

    private fun journalFile(): File = File(temporaryFolder.newFolder(), "config.journal")
    private fun coordinator(file: File, asset: ColorOsSystemUiAsset = asset(10)) =
        ColorOsConfigCoordinator(APP, { asset }, ColorOsConfigJournal(file))

    private class Backend(var row: ColorOsRusRow?) : ColorOsConfigBackend {
        override val uid = 2000
        var writes = 0
        var reloads = 0
        var casCount = 1
        var commitWrite = true
        var beforeWrite: (() -> Unit)? = null
        var writeFailure: Exception? = null
        var reloadFailure: Exception? = null
        var readFailure: Exception? = null
        var afterRead: (() -> Unit)? = null
        override suspend fun read(): ColorOsRusRow? {
            readFailure?.let { throw it }
            afterRead?.invoke()
            return row
        }
        override suspend fun compareAndSet(expected: ColorOsRusRow, replacement: ColorOsRusRow): Int {
            beforeWrite?.invoke()
            check(expected == row)
            writes++
            if (casCount == 1 && commitWrite) row = replacement
            writeFailure?.let { throw it }
            return casCount
        }
        override suspend fun requestReload() {
            reloadFailure?.let { throw it }
            reloads++
        }
        override fun close() = Unit
    }

    companion object {
        private const val APP = "moe.ouom.neriplayer"
        private fun fixture(policy: String = "") =
            "<?xml version='1.0'?><config><version>10</version>" +
                "<whitelist><packageName name='vendor.player' note='a&#58;b'/></whitelist>" +
                "<policy>$policy<packageName name='vendor.player' lyricEntrance='3' vendor='retain'/></policy>" +
                "<excludeList/><!-- preserved --><extra value='a&gt;b'/></config>"
        private fun enabledFixture(version: Int) = fixture("<packageName name='$APP' lyricEntrance='52'/>")
            .replace("<whitelist>", "<whitelist><packageName name='$APP'/>")
            .replace("<version>10</version>", "<version>$version</version>")
        private fun asset(version: Int) = ColorOsSystemUiAsset(
            1600, 1, fixture().replace("<version>10</version>", "<version>$version</version>")
        )
    }
}
