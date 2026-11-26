package com.phonxiety.bridge.data.updates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs [RollbackScript.TEXT] with fake `pm`, `reboot` and `sync` on `PATH`. The AOSP command's
 * inverted result (exit 1 and `Success` on success, exit 0 on failure) and the verification before
 * the reboot are exactly the parts a device test cannot repeat cheaply.
 */
class RollbackScriptTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var state: File
    private lateinit var after: File
    private lateinit var bin: File
    private lateinit var log: File
    private lateinit var rebootMarker: File

    private val dataApk = "package:/data/app/~~abc==/com.phonxiety.bridge-xyz==/base.apk"
    private val systemApk = "package:/system/priv-app/phonxiety-bridge/phonxiety-bridge.apk"

    @Before
    fun setUp() {
        state = temporary.newFolder("state")
        after = temporary.newFolder("after")
        bin = temporary.newFolder("bin")
        log = File(temporary.root, "pm.log")
        rebootMarker = File(temporary.root, "rebooted")
        File(bin, "sync").write(sh("exit 0"))
        File(bin, "reboot").write(sh("touch \"\$FAKE_REBOOT_MARKER\""))
        File(bin, "pm").write(
            sh(
                """
                echo "${'$'}1" >> "${'$'}FAKE_PM_LOG"
                case "${'$'}1" in
                  path)
                    cat "${'$'}FAKE_PM_STATE/paths" 2>/dev/null
                    exit "$(cat "${'$'}FAKE_PM_STATE/path_status" 2>/dev/null || echo 0)" ;;
                  uninstall-system-updates)
                    cp -f "${'$'}FAKE_PM_AFTER/paths" "${'$'}FAKE_PM_STATE/paths"
                    cp -f "${'$'}FAKE_PM_AFTER/path_status" "${'$'}FAKE_PM_STATE/path_status"
                    printf '%s' "${'$'}FAKE_PM_UNINSTALL_OUTPUT"
                    exit "${'$'}FAKE_PM_UNINSTALL_STATUS" ;;
                esac
                exit 2
                """.trimIndent()
            )
        )
        statePaths(dataApk, status = 0)
        afterPaths(systemApk, status = 0)
    }

    @Test
    fun `AOSP success is exit 1 plus Success, and the removal is verified before the reboot`() {
        val exit = runScript(uninstallOutput = "Success", uninstallStatus = 1)

        assertEquals(0, exit)
        assertEquals(step(), "removed")
        assertTrue(rebootMarker.isFile)
        assertEquals(listOf("path", "uninstall-system-updates", "path"), pmCalls())
    }

    @Test
    fun `exit 0 is a reported failure and nothing is rebooted`() {
        val exit = runScript(uninstallOutput = "Failure [DELETE_FAILED_INTERNAL_ERROR]", uninstallStatus = 0)

        assertEquals(1, exit)
        assertEquals(step(), "failed")
        assertFalse(rebootMarker.isFile)
        assertEquals(listOf("path", "uninstall-system-updates"), pmCalls())
    }

    @Test
    fun `an already absent update is recorded and only the reboot is left`() {
        statePaths(systemApk, status = 0)

        val exit = runScript(uninstallOutput = "Success", uninstallStatus = 1)

        assertEquals(0, exit)
        assertEquals(step(), "already_absent")
        assertTrue(rebootMarker.isFile)
        assertEquals(listOf("path"), pmCalls())
    }

    @Test
    fun `a removal that cannot be verified leaves the update unknown and does not reboot`() {
        afterPaths("", status = 1)

        val exit = runScript(uninstallOutput = "Success", uninstallStatus = 1)

        assertEquals(1, exit)
        assertEquals(step(), "unknown")
        assertFalse(rebootMarker.isFile)
    }

    @Test
    fun `a reported success that leaves the data apk in place is a failure`() {
        afterPaths(dataApk, status = 0)

        val exit = runScript(uninstallOutput = "Success", uninstallStatus = 1)

        assertEquals(1, exit)
        assertEquals(step(), "failed")
        assertFalse(rebootMarker.isFile)
    }

    private fun runScript(uninstallOutput: String, uninstallStatus: Int): Int {
        File(temporary.root, "rollback.json").writeText(
            RollbackJournalCodec.write(
                RollbackJournal(
                    createdAtMillis = 1L,
                    step = RollbackJournalStep.MODULE_STAGED,
                    targets = RollbackTargets(
                        packageName = "com.phonxiety.bridge",
                        moduleVersionName = "0.3.0",
                        moduleVersionCode = 3,
                        moduleSha256 = "0".repeat(64),
                        fromAppVersionName = "0.4.0",
                        fromAppVersionCode = 4
                    )
                )
            )
        )
        val script = File(temporary.root, "rollback.sh").apply { writeText(RollbackScript.TEXT) }
        val builder = ProcessBuilder("/bin/sh", script.absolutePath).redirectErrorStream(true)
        builder.environment().apply {
            put("PATH", "${bin.absolutePath}:/usr/bin:/bin:/usr/sbin:/sbin")
            put("PHONXIETY_ROLLBACK_DIR", temporary.root.absolutePath)
            put("PHONXIETY_ROLLBACK_PACKAGE", "com.phonxiety.bridge")
            put("FAKE_REBOOT_MARKER", rebootMarker.absolutePath)
            put("FAKE_PM_LOG", log.absolutePath)
            put("FAKE_PM_STATE", state.absolutePath)
            put("FAKE_PM_AFTER", after.absolutePath)
            put("FAKE_PM_UNINSTALL_OUTPUT", uninstallOutput)
            put("FAKE_PM_UNINSTALL_STATUS", uninstallStatus.toString())
        }
        return builder.start().waitFor()
    }

    private fun statePaths(paths: String, status: Int) {
        File(state, "paths").writeText(paths)
        File(state, "path_status").writeText(status.toString())
    }

    private fun afterPaths(paths: String, status: Int) {
        File(after, "paths").writeText(paths)
        File(after, "path_status").writeText(status.toString())
    }

    private fun step(): String? {
        val text = File(temporary.root, "rollback.json").readText()
        return RollbackJournalCodec.parse(text)?.step?.wire
    }

    private fun pmCalls(): List<String> = if (log.isFile) log.readLines().filter { it.isNotBlank() } else emptyList()

    private fun File.write(text: String) {
        writeText(text)
        setExecutable(true)
    }

    private fun sh(body: String): String = "#!/bin/sh\n$body\n"
}
