package com.phonxiety.bridge.root

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDetectorTest {
    private fun runner(responder: (String) -> ShellOutput) = RecordingRunner(responder)

    private suspend fun detect(
        environment: FakeEnvironment,
        responder: (String) -> ShellOutput = { ShellOutput(stdout = "uid=0(root) gid=0(root)") }
    ): RootDetection {
        val shell = RootShell(runner(responder))
        return RootDetector(shell, environment).detect()
    }

    @Test
    fun magiskFromArtefactsAndVersion() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.ADB_ROOT] = listOf("magisk", "modules")
            packages += MagiskProvider.MANAGER_PACKAGE
        }
        val detection = detect(environment) { script ->
            if (script == "magisk -v") ShellOutput(stdout = "31.1:MAGISK\n") else ShellOutput(stdout = "uid=0(root)")
        }

        assertEquals(SuStatus.GRANTED, detection.su)
        assertEquals(1, detection.providers.size)
        val provider = detection.providers.single()
        assertEquals(RootId.MAGISK, provider.id)
        assertEquals("31.1:MAGISK", provider.version)
        assertEquals(MagiskProvider.MANAGER_PACKAGE, provider.managerPackage)
        assertTrue(provider.managerInstalled)
    }

    @Test
    fun kernelSuFromArtefactsAndManagerPackage() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.ADB_ROOT] = listOf("ksu", "ksud", "modules")
            packages += KernelSuProvider.KERNELSU_MANAGER_PACKAGE
        }
        val detection = detect(environment) { script ->
            if (script == "ksud -V") ShellOutput(stdout = "3.0.0\n") else ShellOutput(stdout = "uid=0")
        }

        val provider = detection.providers.single()
        assertEquals(RootId.KERNELSU, provider.id)
        assertEquals("3.0.0", provider.version)
    }

    @Test
    fun kernelSuNextByManagerPackage() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.ADB_ROOT] = listOf("ksu", "ksud", "modules")
            packages += KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE
        }
        val detection = detect(environment) { script ->
            if (script == "ksud -V") ShellOutput(stdout = "1.0.0\n") else ShellOutput(stdout = "uid=0")
        }

        val provider = detection.providers.single()
        assertEquals(RootId.KERNELSU_NEXT, provider.id)
        assertEquals(null, provider.note)
    }

    @Test
    fun kernelSuVariantUnknownWithoutManagerPackage() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.ADB_ROOT] = listOf("ksu", "ksud", "modules")
        }
        val detection = detect(environment) { script ->
            if (script == "ksud -V") ShellOutput(stdout = "2.0.0\n") else ShellOutput(stdout = "uid=0")
        }

        val provider = detection.providers.single()
        assertEquals(RootId.KERNELSU, provider.id)
        assertTrue(provider.note!!.contains("variant unknown"))
    }

    @Test
    fun apatchFromArtefacts() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.ADB_ROOT] = listOf("ap", "apd", "modules")
            packages += ApatchProvider.MANAGER_PACKAGE
        }
        val detection = detect(environment) { script ->
            if (script == "apd -V") ShellOutput(stdout = "0.10.0\n") else ShellOutput(stdout = "uid=0")
        }

        val provider = detection.providers.single()
        assertEquals(RootId.APATCH, provider.id)
        assertEquals("0.10.0", provider.version)
    }

    @Test
    fun twoProvidersAreReportedAsConflict() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.ADB_ROOT] = listOf("magisk", "ksu", "ksud", "modules")
            packages += MagiskProvider.MANAGER_PACKAGE
            packages += KernelSuProvider.KERNELSU_MANAGER_PACKAGE
        }
        val detection = detect(environment) { script ->
            when (script) {
                "magisk -v" -> ShellOutput(stdout = "31.1:MAGISK\n")
                "ksud -V" -> ShellOutput(stdout = "3.0.0\n")
                else -> ShellOutput(stdout = "uid=0")
            }
        }

        assertTrue(detection.conflict)
        assertEquals(listOf(RootId.MAGISK, RootId.KERNELSU), detection.providers.map { it.id })
    }

    @Test
    fun deniedSuFallsBackToManagerPackages() = runTest {
        val environment = FakeEnvironment().apply {
            packages += MagiskProvider.MANAGER_PACKAGE
        }
        val detection = detect(environment) {
            ShellOutput(stderr = "Permission denied", exitCode = 1)
        }

        assertEquals(SuStatus.DENIED, detection.su)
        val provider = detection.providers.single()
        assertEquals(RootId.MAGISK, provider.id)
        assertTrue(detection.suOutput!!.contains("Permission denied"))
    }

    @Test
    fun suTimeoutIsNotAnAnswer() = runTest {
        val detection = detect(FakeEnvironment()) {
            ShellOutput(timedOut = true, stdout = "", timeoutMs = RootShell.DEFAULT_TIMEOUT_MS)
        }

        assertEquals(SuStatus.TIMED_OUT, detection.su)
        assertTrue(detection.providers.isEmpty())
    }

    @Test
    fun missingSuBinaryMeansNoRoot() = runTest {
        val detection = detect(FakeEnvironment()) {
            ShellOutput(launchFailed = true, exitCode = 126, stderr = "Cannot run program \"su\"")
        }

        assertEquals(SuStatus.NOT_ROOTED, detection.su)
        assertTrue(detection.providers.isEmpty())
    }

    @Test
    fun deniedRootStillNamesTheManagerVersion() = runTest {
        val environment = FakeEnvironment().apply {
            packages += MagiskProvider.MANAGER_PACKAGE
            packageVersions[MagiskProvider.MANAGER_PACKAGE] = "31.1 (31100)"
        }
        val detection = detect(environment) {
            ShellOutput(stderr = "Permission denied", exitCode = 13)
        }

        assertEquals(SuStatus.DENIED, detection.su)
        assertEquals("31.1 (31100)", detection.providers.single().version)
    }

    @Test
    fun grantedWithoutTheProviderBinaryFallsBackToTheManagerVersion() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.ADB_ROOT] = listOf("magisk", "modules")
            packages += MagiskProvider.MANAGER_PACKAGE
            packageVersions[MagiskProvider.MANAGER_PACKAGE] = "31.1 (31100)"
        }
        val detection = detect(environment) { script ->
            if (script == "magisk -v" || script == "magisk -V") {
                ShellOutput(stderr = "magisk: not found", exitCode = 127)
            } else {
                ShellOutput(stdout = "uid=0(root)")
            }
        }

        assertEquals("31.1 (31100)", detection.providers.single().version)
    }

    @Test
    fun detectsKernelSuPackageOnlyWhenRootDenied() = runTest {
        val environment = FakeEnvironment().apply {
            packages += KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE
        }
        val detection = detect(environment) { ShellOutput(exitCode = 1) }

        assertEquals(SuStatus.DENIED, detection.su)
        assertEquals(RootId.KERNELSU_NEXT, detection.providers.single().id)
    }
}
