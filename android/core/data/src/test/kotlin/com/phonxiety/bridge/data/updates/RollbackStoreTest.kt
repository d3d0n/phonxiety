package com.phonxiety.bridge.data.updates

import com.phonxiety.bridge.root.ProcessRunner
import com.phonxiety.bridge.root.RootCommand
import com.phonxiety.bridge.root.RootShell
import com.phonxiety.bridge.root.ShellOutput
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `/data/adb/phonxiety` through a fake `su`: generations, the pointer switch, honest reads. */
class RollbackStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val archive = PublishedModuleArchive("0.3.0", 3, "a".repeat(64))
    private val generation = RootCommand.rollbackGenerationName(archive.sha256)!!

    @Test
    fun `publishing fills one generation, then moves the pointer once`() = runTest {
        val runner = FakeRunner()
        val store = store(runner)
        val archiveFile = temporary.newFile("module-previous.zip").apply { writeText("zip") }
        val metadata = temporary.newFile("module-previous.json").apply { writeText(ModuleArchiveMeta.write(archive)) }
        var pointer: String? = null
        runner.onCommand = { script ->
            if (script.startsWith("cp -f") && script.endsWith("/data/adb/phonxiety/current.tmp")) {
                pointer = File(script.split(' ')[2]).readText()
            }
        }

        assertTrue(store.publish(archiveFile, metadata))

        val generationPath = "/data/adb/phonxiety/$generation"
        assertEquals(
            listOf(
                "mkdir -p /data/adb/phonxiety",
                "mkdir -p $generationPath",
                "cp -f ${archiveFile.path} $generationPath/module-previous.zip.tmp",
                "mv -f $generationPath/module-previous.zip.tmp $generationPath/module-previous.zip",
                "cp -f ${metadata.path} $generationPath/module-previous.json.tmp",
                "mv -f $generationPath/module-previous.json.tmp $generationPath/module-previous.json",
                "cp -f ${File(temporary.root, "workspace/current").path} /data/adb/phonxiety/current.tmp",
                "mv -f /data/adb/phonxiety/current.tmp /data/adb/phonxiety/current",
                "ls -1 /data/adb/phonxiety"
            ),
            runner.commands
        )
        assertEquals("$generation\n", pointer)
        // Nothing was removed yet: the switch is the last write, never the first.
        assertTrue(runner.commands.indexOfFirst { it.startsWith("mv -f /data/adb/phonxiety/current.tmp") } > 3)
    }

    @Test
    fun `a generation the pointer no longer names is cleaned up`() = runTest {
        val runner = FakeRunner().apply {
            respond = { script ->
                if (script == "ls -1 /data/adb/phonxiety") {
                    ShellOutput(stdout = "g-${"b".repeat(64)}\n$generation\nrollback.json\n")
                } else {
                    ShellOutput()
                }
            }
        }

        assertTrue(store(runner).publish(temporary.newFile("a.zip"), metadataFile()))

        assertTrue(runner.commands.contains("rm -rf /data/adb/phonxiety/g-${"b".repeat(64)}"))
        assertFalse(runner.commands.contains("rm -rf /data/adb/phonxiety/$generation"))
        assertFalse(runner.commands.any { it.startsWith("rm -rf") && it.endsWith("rollback.json") })
    }

    @Test
    fun `a refused copy stops the publish before the pointer moves`() = runTest {
        val runner = FakeRunner().apply {
            respond = { script -> if (script.startsWith("cp -f")) ShellOutput(exitCode = 1) else ShellOutput() }
        }

        assertFalse(store(runner).publish(temporary.newFile("a.zip"), metadataFile()))

        assertFalse(runner.commands.any { it.contains("/data/adb/phonxiety/current") })
    }

    @Test
    fun `the archive is present only when root's digest matches the published identity`() = runTest {
        val present = store(
            FakeRunner().apply {
                respond = { script ->
                    when {
                        script == "ls -1 /data/adb" -> ShellOutput(stdout = "modules\nphonxiety\n")
                        script.endsWith("/current") -> ShellOutput(stdout = "$generation\n")
                        script.endsWith("module-previous.json") -> ShellOutput(stdout = ModuleArchiveMeta.write(archive))
                        script.startsWith("sha256sum") -> ShellOutput(stdout = "${archive.sha256}  /data/adb/phonxiety/$generation/module-previous.zip\n")
                        else -> ShellOutput()
                    }
                }
            }
        ).read()

        assertEquals(RollbackArchiveState.Present(archive), present)
    }

    @Test
    fun `bytes that do not hash to the published identity are a mismatch, not an archive`() = runTest {
        val other = "b".repeat(64)
        val mismatch = store(
            FakeRunner().apply {
                respond = { script ->
                    when {
                        script == "ls -1 /data/adb" -> ShellOutput(stdout = "phonxiety\n")
                        script.endsWith("/current") -> ShellOutput(stdout = "$generation\n")
                        script.endsWith("module-previous.json") -> ShellOutput(stdout = ModuleArchiveMeta.write(archive))
                        script.startsWith("sha256sum") -> ShellOutput(stdout = "$other  x\n")
                        else -> ShellOutput()
                    }
                }
            }
        ).read()

        assertEquals(RollbackArchiveState.Mismatch(archive.sha256, other), mismatch)
    }

    @Test
    fun `an unreadable directory, an absent pointer and a garbled one are told apart`() = runTest {
        assertEquals(
            RollbackArchiveState.Missing,
            store(FakeRunner().apply { respond = { ShellOutput(stdout = "modules\npost-fs-data.d\n") } }).read()
        )
        assertEquals(RollbackArchiveState.Missing, store(FakeRunner()).read())
        assertEquals(
            RollbackArchiveState.Unreadable,
            store(FakeRunner().apply { respond = { ShellOutput(exitCode = 1) } }).read()
        )
        // The pointer names something no generation can be built from.
        assertEquals(
            RollbackArchiveState.Unreadable,
            store(
                FakeRunner().apply {
                    respond = { script ->
                        when {
                            script == "ls -1 /data/adb" -> ShellOutput(stdout = "phonxiety\n")
                            else -> ShellOutput(stdout = "../../etc\n")
                        }
                    }
                }
            ).read()
        )
        // The metadata inside the generation names a different digest than its own directory.
        assertEquals(
            RollbackArchiveState.Unreadable,
            store(
                FakeRunner().apply {
                    respond = { script ->
                        when {
                            script == "ls -1 /data/adb" -> ShellOutput(stdout = "phonxiety\n")
                            script.endsWith("/current") -> ShellOutput(stdout = "$generation\n")
                            else -> ShellOutput(stdout = ModuleArchiveMeta.write(archive.copy(sha256 = "c".repeat(64))))
                        }
                    }
                }
            ).read()
        )
    }

    @Test
    fun `the journal is read only when root can cat it`() = runTest {
        val journal = journal()
        val readable = store(
            FakeRunner().apply { respond = { ShellOutput(stdout = RollbackJournalCodec.write(journal)) } }
        )
        val missing = store(FakeRunner().apply { respond = { ShellOutput(exitCode = 1) } })

        assertEquals(journal, readable.readJournal())
        assertNull(missing.readJournal())
    }

    @Test
    fun `preparation writes the script and the journal into app storage`() = runTest {
        val runner = FakeRunner()

        val result = store(runner).prepareScript(journal())

        assertTrue(result.ok)
        val workspace = File(temporary.root, "workspace")
        assertEquals(RollbackScript.TEXT, File(workspace, RollbackFiles.SCRIPT).readText())
        assertEquals(journal(), RollbackJournalCodec.parse(File(workspace, RollbackFiles.JOURNAL).readText()))
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `a workspace that cannot be created is an explained failure, never a throw`() = runTest {
        val blocked = temporary.newFile("blocked")

        val result = RootRollbackStore(RootShell(FakeRunner()), blocked).prepareScript(journal())

        assertFalse(result.ok)
        assertTrue(result.detail!!.contains(blocked.path))
    }

    @Test
    fun `starting publishes the prepared files and then starts the script detached`() = runTest {
        val runner = FakeRunner()
        var publishedJournal: RollbackJournal? = null
        runner.onCommand = { script ->
            if (script.startsWith("cp -f") && script.endsWith("/data/adb/phonxiety/rollback.json.tmp")) {
                publishedJournal = RollbackJournalCodec.parse(File(script.split(' ')[2]).readText())
            }
        }
        val store = store(runner)

        assertTrue(store.prepareScript(journal()).ok)
        val result = store.startScript()

        assertTrue(result.ok)
        publishedJournal?.let {
            assertEquals(RollbackJournalStep.MODULE_STAGED, it.step)
            assertEquals(3L, it.targets.moduleVersionCode)
        }
        assertEquals(
            "nohup sh /data/adb/phonxiety/rollback.sh >/data/adb/phonxiety/rollback.log 2>&1 &",
            runner.commands.last()
        )
        assertTrue(runner.commands.contains("mkdir -p /data/adb/phonxiety"))
        assertFalse(File(File(temporary.root, "workspace"), RollbackFiles.SCRIPT).exists())
    }

    @Test
    fun `a script that cannot be published is reported and never started`() = runTest {
        val runner = FakeRunner().apply {
            respond = { script ->
                if (script.startsWith("cp -f") && script.contains("rollback.sh")) {
                    ShellOutput(exitCode = 1)
                } else {
                    ShellOutput()
                }
            }
        }
        val store = store(runner)

        assertTrue(store.prepareScript(journal()).ok)
        val result = store.startScript()

        assertFalse(result.ok)
        assertTrue(result.detail!!.contains("script"))
        assertFalse(runner.commands.any { it.startsWith("nohup") })
    }

    @Test
    fun `nothing is started that was not prepared`() = runTest {
        val runner = FakeRunner()

        val result = store(runner).startScript()

        assertFalse(result.ok)
        assertTrue(result.detail!!.contains("prepared"))
        assertTrue(runner.commands.isEmpty())
    }

    private fun metadataFile(): File = temporary.newFile("module-previous.json")
        .apply { writeText(ModuleArchiveMeta.write(archive)) }

    private fun store(runner: FakeRunner): RootRollbackStore = RootRollbackStore(RootShell(runner), File(temporary.root, "workspace"))

    private fun journal(): RollbackJournal = RollbackJournal(
        createdAtMillis = 5L,
        step = RollbackJournalStep.MODULE_STAGED,
        targets = RollbackTargets(
            packageName = "com.phonxiety.bridge",
            moduleVersionName = "0.3.0",
            moduleVersionCode = 3,
            moduleSha256 = archive.sha256,
            fromAppVersionName = "0.4.0",
            fromAppVersionCode = 4
        )
    )

    private class FakeRunner : ProcessRunner {
        val commands = mutableListOf<String>()
        var respond: (String) -> ShellOutput = { ShellOutput() }
        var onCommand: (String) -> Unit = {}

        override suspend fun run(command: List<String>, timeoutMs: Long): ShellOutput {
            val script = command.last()
            commands += script
            onCommand(script)
            return respond(script)
        }
    }
}
