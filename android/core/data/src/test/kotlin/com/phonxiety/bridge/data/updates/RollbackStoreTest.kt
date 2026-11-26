package com.phonxiety.bridge.data.updates

import com.phonxiety.bridge.root.ProcessRunner
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

/** `/data/adb/phonxiety` through a fake `su`: exact commands, atomic publish, honest reads. */
class RollbackStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val archive = PublishedModuleArchive("0.3.0", 3, "a".repeat(64))

    @Test
    fun `publishing goes through the rollback directory and renames atomically`() = runTest {
        val runner = FakeRunner()
        val store = store(runner)
        val archiveFile = temporary.newFile("module-previous.zip")
        val metadata = temporary.newFile("module-previous.json").apply { writeText("{}") }

        assertTrue(store.publish(archiveFile, metadata))

        assertEquals(
            listOf(
                "mkdir -p /data/adb/phonxiety",
                "cp -f ${archiveFile.path} /data/adb/phonxiety/module-previous.zip.tmp",
                "mv -f /data/adb/phonxiety/module-previous.zip.tmp /data/adb/phonxiety/module-previous.zip",
                "cp -f ${metadata.path} /data/adb/phonxiety/module-previous.json.tmp",
                "mv -f /data/adb/phonxiety/module-previous.json.tmp /data/adb/phonxiety/module-previous.json"
            ),
            runner.commands
        )
    }

    @Test
    fun `a refused copy stops the publish and reports failure`() = runTest {
        val runner = FakeRunner().apply {
            respond = { script -> if (script.startsWith("cp -f")) ShellOutput(exitCode = 1) else ShellOutput() }
        }

        assertFalse(store(runner).publish(temporary.newFile("a.zip"), temporary.newFile("b.json")))

        assertEquals(2, runner.commands.size)
    }

    @Test
    fun `the archive is present only when both files are listed and the metadata parses`() = runTest {
        val present = store(
            FakeRunner().apply {
                respond = { script ->
                    when {
                        script == "ls -1 /data/adb" -> ShellOutput(stdout = "modules\nphonxiety\n")

                        script == "ls -1 /data/adb/phonxiety" ->
                            ShellOutput(stdout = "module-previous.zip\nmodule-previous.json\n")

                        script.startsWith("cat ") -> ShellOutput(stdout = ModuleArchiveMeta.write(archive))

                        else -> ShellOutput()
                    }
                }
            }
        ).read()

        assertEquals(RollbackArchiveState.Present(archive), present)
        assertEquals(
            RollbackArchiveState.Missing,
            store(FakeRunner().apply { respond = { ShellOutput(stdout = "modules\npost-fs-data.d\n") } }).read()
        )
        assertEquals(RollbackArchiveState.Missing, store(FakeRunner()).read())
        assertEquals(
            RollbackArchiveState.Unreadable,
            store(FakeRunner().apply { respond = { ShellOutput(exitCode = 1) } }).read()
        )
        assertEquals(
            RollbackArchiveState.Unreadable,
            store(
                FakeRunner().apply {
                    respond = { script ->
                        when {
                            script == "ls -1 /data/adb" -> ShellOutput(stdout = "phonxiety\n")

                            script == "ls -1 /data/adb/phonxiety" ->
                                ShellOutput(stdout = "module-previous.zip\nmodule-previous.json\n")

                            else -> ShellOutput(stdout = "not a document")
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
    fun `starting the removal publishes the script and the journal, then starts it detached`() = runTest {
        val runner = FakeRunner()
        var publishedJournal: RollbackJournal? = null
        runner.onCommand = { script ->
            if (script.startsWith("cp -f") && script.contains("/data/adb/phonxiety/rollback.json.tmp")) {
                val source = File(script.split(' ')[2])
                publishedJournal = RollbackJournalCodec.parse(source.readText())
            }
        }

        val result = store(runner).startScript(journal())

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

        val result = store(runner).startScript(journal())

        assertFalse(result.ok)
        assertTrue(result.detail!!.contains("script"))
        assertFalse(runner.commands.any { it.startsWith("nohup") })
    }

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
