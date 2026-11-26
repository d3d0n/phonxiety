package com.phonxiety.bridge.root

/**
 * Detection from artefacts and manager packages, never from the kernel string.
 *
 * Artefacts are read through [RootShell] because `/data/adb` is root-only; when `su` is denied the
 * detector falls back to the installed manager packages, which is enough to name the provider in
 * the [SuStatus.DENIED] screen. More than one provider is reported as-is: the caller must refuse to
 * install ([ProviderConflictException]).
 */
class RootDetector(
    private val shell: RootShell,
    private val environment: RootEnvironment
) {
    suspend fun detect(): RootDetection {
        val probe = shell.exec(RootCommand.id())
        val su = when {
            probe.launchFailed -> SuStatus.NOT_ROOTED
            probe.timedOut -> SuStatus.TIMED_OUT
            probe.exitCode == 0 && ROOT_ID.containsMatchIn(probe.stdout) -> SuStatus.GRANTED
            else -> SuStatus.DENIED
        }
        val packages = environment.installedPackages()
        // The answerer names itself. Read that before the artefacts: a phone migrated between root
        // solutions keeps the old provider's files and manager app for good.
        val banner = if (su == SuStatus.GRANTED) versionOf { RootCommand.suVersion() } else null
        val declared = activeFromSuVersion(banner) ?: activeFromKernelSuContext(probe.combined)
        val providers = if (su == SuStatus.GRANTED) {
            detectWithRoot(packages, declared)
        } else {
            detectByPackages(packages)
        }
        return RootDetection(
            su = su,
            providers = providers.sortedBy { it.id.ordinal },
            suOutput = probe.combined.takeIf { it.isNotEmpty() },
            // A family from the banner or the context resolves to the variant the artefacts named.
            active = declared?.let { family -> providers.firstOrNull { it.id.sameFamily(family) }?.id } ?: declared
        )
    }

    private suspend fun detectWithRoot(
        packages: Set<String>,
        active: RootId?
    ): List<DetectedProvider> {
        val adbEntries = environment.listDir(RootCommand.ADB_ROOT).orEmpty()
        val found = mutableListOf<DetectedProvider>()

        val magiskArtefacts = buildList {
            if ("magisk" in adbEntries) add("${RootCommand.ADB_ROOT}/magisk")
        }
        val magiskVersion = if (
            "magisk" in adbEntries || MagiskProvider.MANAGER_PACKAGE in packages
        ) {
            // `magisk -v` names the version, `-V` the build code: both are needed for `31.1 (31100)`.
            parseProviderVersion(
                versionOf { RootCommand.magiskVersionName() },
                versionOf { RootCommand.magiskVersionCode() }
            ) ?: environment.packageVersion(MagiskProvider.MANAGER_PACKAGE)
        } else {
            null
        }
        if (magiskArtefacts.isNotEmpty() || magiskVersion != null) {
            val (manager, installed) = managerPackage(
                listOf(MagiskProvider.MANAGER_PACKAGE),
                packages,
                MagiskProvider.MANAGER_PACKAGE
            )
            found += DetectedProvider(
                id = RootId.MAGISK,
                version = magiskVersion ?: UNKNOWN_VERSION,
                managerPackage = manager,
                managerInstalled = installed,
                artefacts = magiskArtefacts
            )
        }

        val ksuArtefacts = buildList {
            if ("ksu" in adbEntries) add("${RootCommand.ADB_ROOT}/ksu")
            if ("ksud" in adbEntries) add("${RootCommand.ADB_ROOT}/ksud")
        }
        val ksuManagers = listOf(
            KernelSuProvider.KERNELSU_MANAGER_PACKAGE,
            KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE
        )
        val ksuRawVersion = if (
            ksuArtefacts.isNotEmpty() || ksuManagers.any { it in packages }
        ) {
            versionOf { RootCommand.ksudVersion() }
        } else {
            null
        }
        val ksuVersion = parseProviderVersion(ksuRawVersion)
            ?: ksuManagers.firstNotNullOfOrNull { environment.packageVersion(it) }
        if (ksuArtefacts.isNotEmpty() || ksuVersion != null) {
            val installedKsuManagers = ksuManagers.filter { it in packages }
            val manager = installedKsuManagers.firstOrNull()
                ?: KernelSuProvider.KERNELSU_MANAGER_PACKAGE
            val nextByPackage = KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE in packages
            val nextByVersion = ksuRawVersion?.lowercase()?.contains("next") == true
            val id = if (nextByPackage || nextByVersion) RootId.KERNELSU_NEXT else RootId.KERNELSU
            val note = when {
                nextByPackage || nextByVersion -> null

                installedKsuManagers.isEmpty() ->
                    "KernelSU variant unknown (no manager package); using commands both variants accept"

                else -> null
            }
            found += DetectedProvider(
                id = id,
                version = ksuVersion ?: UNKNOWN_VERSION,
                managerPackage = manager,
                managerInstalled = manager in packages,
                artefacts = ksuArtefacts,
                note = note
            )
        }

        val apatchArtefacts = buildList {
            if ("ap" in adbEntries) add("${RootCommand.ADB_ROOT}/ap")
            if ("apd" in adbEntries) add("${RootCommand.ADB_ROOT}/apd")
        }
        val apatchVersion = if (
            apatchArtefacts.isNotEmpty() || ApatchProvider.MANAGER_PACKAGE in packages
        ) {
            parseProviderVersion(versionOf { RootCommand.apdVersion() })
                ?: environment.packageVersion(ApatchProvider.MANAGER_PACKAGE)
        } else {
            null
        }
        if (apatchArtefacts.isNotEmpty() || apatchVersion != null) {
            val (manager, installed) = managerPackage(
                listOf(ApatchProvider.MANAGER_PACKAGE),
                packages,
                ApatchProvider.MANAGER_PACKAGE
            )
            found += DetectedProvider(
                id = RootId.APATCH,
                version = apatchVersion ?: UNKNOWN_VERSION,
                managerPackage = manager,
                managerInstalled = installed,
                artefacts = apatchArtefacts
            )
        }

        // A provider proven by its own banner but with nothing readable under /data/adb still
        // exists; the leftovers of another one must not decide for it.
        if (active != null && found.none { it.id.sameFamily(active) }) {
            found += namedOnlyByTheBanner(active, packages)
        }

        // Root is granted but no artefact or package is recognisable (e.g. a stripped build).
        // Version probes only run when there is a candidate, so nothing prompts here.
        return found
    }

    /**
     * A provider proven by its own answer but with nothing readable under `/data/adb` is still the
     * one that runs this app's root commands. Naming it from the banner beats falling back to
     * leftovers of a provider the phone migrated away from.
     */
    private fun namedOnlyByTheBanner(id: RootId, packages: Set<String>): DetectedProvider {
        val candidates = when (id) {
            RootId.MAGISK -> listOf(MagiskProvider.MANAGER_PACKAGE)

            RootId.KERNELSU ->
                listOf(KernelSuProvider.KERNELSU_MANAGER_PACKAGE, KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE)

            RootId.KERNELSU_NEXT ->
                listOf(KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE, KernelSuProvider.KERNELSU_MANAGER_PACKAGE)

            RootId.APATCH -> listOf(ApatchProvider.MANAGER_PACKAGE)
        }
        val (manager, installed) = managerPackage(candidates, packages, candidates.first())
        return DetectedProvider(
            id = id,
            version = if (installed) environment.packageVersion(manager) ?: UNKNOWN_VERSION else UNKNOWN_VERSION,
            managerPackage = manager,
            managerInstalled = installed,
            note = "named by its own su version; nothing under ${RootCommand.ADB_ROOT} named it"
        )
    }

    private fun detectByPackages(packages: Set<String>): List<DetectedProvider> {
        val found = mutableListOf<DetectedProvider>()
        if (MagiskProvider.MANAGER_PACKAGE in packages) {
            found += DetectedProvider(
                id = RootId.MAGISK,
                version = environment.packageVersion(MagiskProvider.MANAGER_PACKAGE) ?: UNKNOWN_VERSION,
                managerPackage = MagiskProvider.MANAGER_PACKAGE,
                managerInstalled = true
            )
        }
        val nameSuPackages = listOf(
            KernelSuProvider.KERNELSU_MANAGER_PACKAGE,
            KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE
        ).filter { it in packages }
        if (nameSuPackages.isNotEmpty()) {
            val next = KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE in packages
            found += DetectedProvider(
                id = if (next) RootId.KERNELSU_NEXT else RootId.KERNELSU,
                version = environment.packageVersion(nameSuPackages.first()) ?: UNKNOWN_VERSION,
                managerPackage = nameSuPackages.first(),
                managerInstalled = true
            )
        }
        if (ApatchProvider.MANAGER_PACKAGE in packages) {
            found += DetectedProvider(
                id = RootId.APATCH,
                version = environment.packageVersion(ApatchProvider.MANAGER_PACKAGE) ?: UNKNOWN_VERSION,
                managerPackage = ApatchProvider.MANAGER_PACKAGE,
                managerInstalled = true
            )
        }
        return found
    }

    private suspend fun versionOf(command: () -> RootCommand): String? {
        val output = shell.exec(command())
        if (!output.ok) return null
        return output.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    private fun managerPackage(
        candidates: List<String>,
        packages: Set<String>,
        fallback: String
    ): Pair<String, Boolean> {
        val installed = candidates.firstOrNull { it in packages }
        return (installed ?: fallback) to (installed != null)
    }

    companion object {
        const val UNKNOWN_VERSION: String = "unknown"

        private val ROOT_ID = Regex("(^|\\s)uid=0($|\\s|\\()")

        private val SELINUX_CONTEXT = Regex("context=(\\S+)")

        /**
         * The provider from its own `su -v` banner, the primary signal: `<version>:<SUFFIX>`.
         * Magisk prints `31.1:MAGISKSU`, APatch `11282:APatch`, and the KernelSU family its own
         * name (KernelSU-Next says which). An unrecognised suffix names nothing: the artefacts
         * decide instead of a guess.
         */
        internal fun activeFromSuVersion(banner: String?): RootId? {
            val line = banner?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
            val suffix = line.substringAfterLast(':').lowercase()
            return when {
                // Checked first: "MAGISKSU" also contains "ksu".
                "magisk" in suffix -> RootId.MAGISK

                "apatch" in suffix -> RootId.APATCH

                "kernelsu" in suffix || "ksu" in suffix ->
                    if ("next" in suffix) RootId.KERNELSU_NEXT else RootId.KERNELSU

                else -> null
            }
        }

        /**
         * The secondary signal, and only for the KernelSU family: its `su -c id` answers in
         * `u:r:ksu:s0`. A `magisk` domain proves nothing — APatch grants an app exactly that
         * context, so a context alone never names Magisk.
         */
        internal fun activeFromKernelSuContext(output: String): RootId? {
            val context = SELINUX_CONTEXT.find(output)?.groupValues?.getOrNull(1) ?: return null
            return if (context.startsWith("u:r:ksu:")) RootId.KERNELSU else null
        }
    }
}
