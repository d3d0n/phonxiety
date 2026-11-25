package com.phonxiety.bridge.root

/** Records every `su -c` invocation and answers from a script matcher. */
class RecordingRunner(
    private val responder: (String) -> ShellOutput = { ShellOutput() }
) : ProcessRunner {
    data class Invocation(val argv: List<String>, val timeoutMs: Long) {
        val script: String get() = argv[2]
    }

    val invocations: MutableList<Invocation> = mutableListOf()

    val scripts: List<String> get() = invocations.map { it.script }

    override suspend fun run(command: List<String>, timeoutMs: Long): ShellOutput {
        invocations += Invocation(command, timeoutMs)
        return responder(command[2])
    }
}

/** In-memory `/data/adb` tree and package list. */
class FakeEnvironment(
    val dirs: MutableMap<String, List<String>> = mutableMapOf(),
    val files: MutableMap<String, String> = mutableMapOf(),
    val packages: MutableSet<String> = mutableSetOf(),
    val packageVersions: MutableMap<String, String> = mutableMapOf()
) : RootEnvironment {
    override suspend fun listDir(path: String): List<String>? = dirs[path]

    override suspend fun readFile(path: String): String? = files[path]

    override fun installedPackages(): Set<String> = packages.toSet()

    override fun packageVersion(packageName: String): String? = packageVersions[packageName]

    fun module(
        id: String,
        version: String = "0.3.0",
        versionCode: Int = 3,
        flags: Set<ModuleFlag> = emptySet()
    ): FakeEnvironment {
        dirs[RootCommand.MODULES_DIR] = (dirs[RootCommand.MODULES_DIR].orEmpty() + id)
        val dir = "${RootCommand.MODULES_DIR}/$id"
        dirs[dir] = flags.map { it.fileName }
        files["$dir/module.prop"] =
            "id=$id\nname=Phonxiety\nversion=$version\nversionCode=$versionCode\nauthor=phonxiety\n"
        return this
    }
}

fun passingReport(): VerifyReport = VerifyReport(VerifyCheck.entries.associateWith { VerifyOutcome.PASS })

fun failingReport(vararg failed: VerifyCheck): VerifyReport = VerifyReport(
    VerifyCheck.entries.associateWith {
        if (it in failed) VerifyOutcome.FAIL else VerifyOutcome.PASS
    }
)

fun magiskProvider(
    version: String = "31.1:MAGISK",
    managerInstalled: Boolean = true
): DetectedProvider = DetectedProvider(
    id = RootId.MAGISK,
    version = version,
    managerPackage = MagiskProvider.MANAGER_PACKAGE,
    managerInstalled = managerInstalled,
    artefacts = listOf("${RootCommand.ADB_ROOT}/magisk")
)

fun kernelSuProvider(
    id: RootId = RootId.KERNELSU,
    version: String = "3.0.0"
): DetectedProvider = DetectedProvider(
    id = id,
    version = version,
    managerPackage = KernelSuProvider.defaultManagerPackage(id),
    managerInstalled = true,
    artefacts = listOf("${RootCommand.ADB_ROOT}/ksu")
)

fun apatchProvider(version: String = "0.10.0"): DetectedProvider = DetectedProvider(
    id = RootId.APATCH,
    version = version,
    managerPackage = ApatchProvider.MANAGER_PACKAGE,
    managerInstalled = true,
    artefacts = listOf("${RootCommand.ADB_ROOT}/ap")
)
