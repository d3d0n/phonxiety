package com.phonxiety.bridge.data.updates

/**
 * What a rollback does on this phone, decided from the APK the module mounted on the system image
 * and the APK kept before the last update. `pm install -r -d` is only accepted while the system
 * image is not newer; once a module put a newer priv-app there, the module has to go back first.
 */
sealed interface RollbackPlan {
    /** Reinstalling the kept APK as root is enough: the mounted system APK is not newer than it. */
    data class AppOnly(val keptVersionName: String?, val keptVersionCode: Long) : RollbackPlan

    /**
     * The mounted system APK is [moduleVersionCode], newer than the kept APK, so a plain downgrade
     * is refused. [previousVersionName] comes from the published archive, not from a rebuild.
     */
    data class WithPreviousModule(
        val moduleVersionName: String?,
        val moduleVersionCode: Long,
        val previousVersionName: String?,
        val previousVersionCode: Long,
        val previousSha256: String
    ) : RollbackPlan

    /** Nothing can be rolled back from this phone; [blocker] is why, and the screen says it. */
    data class Unavailable(
        val blocker: RollbackBlocker,
        val moduleVersion: String? = null,
        val moduleVersionCode: Long? = null,
        val keptVersionName: String? = null,
        val keptVersionCode: Long = 0L
    ) : RollbackPlan
}

enum class RollbackBlocker {
    /** No previous APK is kept, so there is nothing to return to. */
    NO_KEPT_APK,

    /** The mounted system APK is newer than the kept APK and no previous module archive exists. */
    NO_PREVIOUS_MODULE,

    /** `/data/adb/phonxiety` could not be read: root is denied or the directory is gone. */
    STORAGE_UNREADABLE,

    /** The APK mounted on the system image could not be read, so the downgrade floor is unknown. */
    SYSTEM_VERSION_UNKNOWN,

    /** The published archive is not older than the mounted system APK, so it is not a rollback. */
    STALE_ARCHIVE,

    /** The published archive's bytes are not the ones its own metadata names. */
    ARCHIVE_MISMATCH,

    /**
     * The app already runs the kept version: there is nothing older to return to, and offering the
     * kept APK again would repeat the downgrade the phone just made.
     */
    APP_NOT_NEWER
}

/**
 * What the module put on the system image, read from that APK rather than from staged module
 * metadata: the mounted version is the floor Android checks a downgrade against.
 */
sealed interface MountedSystemApkVersion {
    /** No module APK on the system image: the app is a plain data app. */
    data object Absent : MountedSystemApkVersion

    data class Present(val path: String, val versionName: String?, val versionCode: Long) : MountedSystemApkVersion

    /** The file exists but cannot be read as a package, so the floor is unknown. */
    data object Unreadable : MountedSystemApkVersion
}

/** The archive published under `/data/adb/phonxiety`, with the identity it was published with. */
data class PublishedModuleArchive(val versionName: String?, val versionCode: Long, val sha256: String)

/** Whether a previous-module archive is published and readable. */
sealed interface RollbackArchiveState {
    data object Missing : RollbackArchiveState

    data object Unreadable : RollbackArchiveState

    data class Present(val archive: PublishedModuleArchive) : RollbackArchiveState

    /** The archive is there, but its bytes hash to [actualSha256], not [expectedSha256]. */
    data class Mismatch(val expectedSha256: String, val actualSha256: String) : RollbackArchiveState
}

/** Everything the plan is decided from: probes fill it, nothing here is guessed. */
data class RollbackFacts(
    val keptApkExists: Boolean,
    val keptVersionName: String?,
    val keptVersionCode: Long,
    /** The version code of the app running now: a rollback only exists while it is newer. */
    val runningVersionCode: Long,
    val mounted: MountedSystemApkVersion,
    val archive: RollbackArchiveState
)

/** Pure decision: which rollback this phone needs, or why none is possible. */
object RollbackPlanner {
    fun plan(facts: RollbackFacts): RollbackPlan {
        if (!facts.keptApkExists) return RollbackPlan.Unavailable(RollbackBlocker.NO_KEPT_APK)
        val mounted = facts.mounted
        if (mounted is MountedSystemApkVersion.Unreadable) {
            return RollbackPlan.Unavailable(RollbackBlocker.SYSTEM_VERSION_UNKNOWN)
        }
        if (mounted !is MountedSystemApkVersion.Present || mounted.versionCode <= facts.keptVersionCode) {
            // Reinstalling the kept APK only rolls back while something newer runs. After a
            // completed rollback the kept version is what runs, and offering it again would repeat
            // the downgrade the phone just made.
            if (facts.runningVersionCode <= facts.keptVersionCode) {
                return RollbackPlan.Unavailable(
                    blocker = RollbackBlocker.APP_NOT_NEWER,
                    keptVersionName = facts.keptVersionName,
                    keptVersionCode = facts.keptVersionCode
                )
            }
            return RollbackPlan.AppOnly(facts.keptVersionName, facts.keptVersionCode)
        }
        return when (val archive = facts.archive) {
            is RollbackArchiveState.Present ->
                if (archive.archive.versionCode >= mounted.versionCode) {
                    RollbackPlan.Unavailable(
                        blocker = RollbackBlocker.STALE_ARCHIVE,
                        moduleVersion = mounted.versionName,
                        moduleVersionCode = mounted.versionCode,
                        keptVersionName = facts.keptVersionName,
                        keptVersionCode = facts.keptVersionCode
                    )
                } else {
                    RollbackPlan.WithPreviousModule(
                        moduleVersionName = mounted.versionName,
                        moduleVersionCode = mounted.versionCode,
                        previousVersionName = archive.archive.versionName,
                        previousVersionCode = archive.archive.versionCode,
                        previousSha256 = archive.archive.sha256
                    )
                }

            RollbackArchiveState.Missing -> RollbackPlan.Unavailable(
                blocker = RollbackBlocker.NO_PREVIOUS_MODULE,
                moduleVersion = mounted.versionName,
                moduleVersionCode = mounted.versionCode,
                keptVersionName = facts.keptVersionName,
                keptVersionCode = facts.keptVersionCode
            )

            is RollbackArchiveState.Mismatch -> RollbackPlan.Unavailable(
                blocker = RollbackBlocker.ARCHIVE_MISMATCH,
                moduleVersion = mounted.versionName,
                moduleVersionCode = mounted.versionCode,
                keptVersionName = facts.keptVersionName,
                keptVersionCode = facts.keptVersionCode
            )

            RollbackArchiveState.Unreadable -> RollbackPlan.Unavailable(
                blocker = RollbackBlocker.STORAGE_UNREADABLE,
                moduleVersion = mounted.versionName,
                moduleVersionCode = mounted.versionCode,
                keptVersionName = facts.keptVersionName,
                keptVersionCode = facts.keptVersionCode
            )
        }
    }
}

/** A root step's verdict; [detail] is the output that explains a refusal. */
data class RootStepResult(val ok: Boolean, val detail: String? = null)

/** The app that starts the rollback: its package and the version it is leaving behind. */
data class RunningApp(val packageName: String, val versionName: String?, val versionCode: Long)

/** The identity a rollback is verified against after the boot it caused. */
data class RollbackTargets(
    val packageName: String,
    val moduleVersionName: String?,
    val moduleVersionCode: Long,
    val moduleSha256: String,
    val fromAppVersionName: String?,
    val fromAppVersionCode: Long
)

/** `step` in the root-owned journal: what the detached script got to. */
enum class RollbackJournalStep(val wire: String) {
    /** Written by the app before the script starts; the module is staged. */
    MODULE_STAGED("module_staged"),

    /** The script removed the data-app update and verified it. */
    REMOVED("removed"),

    /** The data-app update was already gone; only the staged module needed the boot. */
    ALREADY_ABSENT("already_absent"),

    /** The removal command reported a failure. */
    FAILED("failed"),

    /** The script could not tell what happened; nothing was rebooted. */
    UNKNOWN("unknown");

    companion object {
        fun of(wire: String?): RollbackJournalStep? = entries.firstOrNull { it.wire == wire }
    }
}

/** `/data/adb/phonxiety/rollback.json`: the rollback's targets and how far the script got. */
data class RollbackJournal(
    val createdAtMillis: Long,
    val step: RollbackJournalStep,
    val targets: RollbackTargets
)

/** The journal's one-line JSON; hand-written so the phone needs no JSON dependency here. */
object RollbackJournalCodec {
    fun write(journal: RollbackJournal): String {
        val targets = journal.targets
        return buildString {
            append('{')
            append("\"package\":").append(quote(targets.packageName)).append(',')
            append("\"created_at_ms\":").append(journal.createdAtMillis).append(',')
            append("\"step\":").append(quote(journal.step.wire)).append(',')
            append("\"from_app_version\":").append(quote(targets.fromAppVersionName)).append(',')
            append("\"from_app_version_code\":").append(targets.fromAppVersionCode).append(',')
            append("\"module_version\":").append(quote(targets.moduleVersionName)).append(',')
            append("\"module_version_code\":").append(targets.moduleVersionCode).append(',')
            append("\"module_sha256\":").append(quote(targets.moduleSha256))
            append('}')
        }
    }

    fun parse(text: String?): RollbackJournal? {
        val json = text?.trim()?.takeIf { it.startsWith("{") && it.endsWith("}") } ?: return null
        val step = RollbackJournalStep.of(string(json, "step")) ?: return null
        val packageName = string(json, "package")?.takeIf { it.isNotBlank() } ?: return null
        val moduleVersionCode = number(json, "module_version_code") ?: return null
        val sha256 = string(json, "module_sha256").orEmpty()
        return RollbackJournal(
            createdAtMillis = number(json, "created_at_ms") ?: 0L,
            step = step,
            targets = RollbackTargets(
                packageName = packageName,
                moduleVersionName = string(json, "module_version"),
                moduleVersionCode = moduleVersionCode,
                moduleSha256 = sha256,
                fromAppVersionName = string(json, "from_app_version"),
                fromAppVersionCode = number(json, "from_app_version_code") ?: 0L
            )
        )
    }

    private fun quote(value: String?): String {
        if (value == null) return "null"
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$escaped\""
    }

    private fun string(json: String, key: String): String? {
        val match = Regex("\"$key\"\\s*:\\s*(null|\"((?:[^\"\\\\]|\\\\.)*)\")").find(json) ?: return null
        if (match.groupValues[1] == "null") return null
        return match.groupValues[2].replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun number(json: String, key: String): Long? = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
}

/** Why a rollback from a previous boot is not complete yet. */
enum class RollbackPendingReason {
    /** The script never recorded anything: the update was not removed. */
    REMOVAL_NOT_STARTED,
    REMOVAL_FAILED,
    REMOVAL_UNVERIFIED,

    /** The removed update is gone, but an app copy on the data partition is still what runs. */
    OLD_APP_NOT_SELECTED,
    VERSION_MISMATCH
}

/** What the journal plus the phone's state say about a rollback. */
sealed interface RollbackRecovery {
    data class Completed(val versionName: String?) : RollbackRecovery

    data class Pending(val reason: RollbackPendingReason, val detail: String? = null) : RollbackRecovery
}

/**
 * Completion is never taken from the journal alone: the mounted module and the selected APK must
 * both be the declared target. Anything else is pending recovery with a concrete retry.
 */
object RollbackVerification {
    fun verify(
        journal: RollbackJournal,
        selectedApkPath: String?,
        runningVersionCode: Long,
        mountedModuleVersionCode: Long?
    ): RollbackRecovery {
        when (journal.step) {
            RollbackJournalStep.MODULE_STAGED ->
                return RollbackRecovery.Pending(
                    RollbackPendingReason.REMOVAL_NOT_STARTED,
                    "the previous module is staged, but the app update was not removed"
                )

            RollbackJournalStep.FAILED ->
                return RollbackRecovery.Pending(
                    RollbackPendingReason.REMOVAL_FAILED,
                    "the removal command reported a failure"
                )

            RollbackJournalStep.UNKNOWN ->
                return RollbackRecovery.Pending(
                    RollbackPendingReason.REMOVAL_UNVERIFIED,
                    "the removal could not be verified"
                )

            RollbackJournalStep.REMOVED, RollbackJournalStep.ALREADY_ABSENT -> Unit
        }
        val target = journal.targets
        val systemApk = selectedApkPath != null && selectedApkPath.startsWith("/system/")
        if (!systemApk) {
            return RollbackRecovery.Pending(
                RollbackPendingReason.OLD_APP_NOT_SELECTED,
                "the running app is still the data-partition copy at ${selectedApkPath ?: "an unknown path"}"
            )
        }
        if (runningVersionCode != target.moduleVersionCode) {
            return RollbackRecovery.Pending(
                RollbackPendingReason.VERSION_MISMATCH,
                "the running app is $runningVersionCode, the rollback target is ${target.moduleVersionCode}"
            )
        }
        if (mountedModuleVersionCode != target.moduleVersionCode) {
            return RollbackRecovery.Pending(
                RollbackPendingReason.VERSION_MISMATCH,
                "the mounted module is ${mountedModuleVersionCode ?: "unreadable"}, the rollback target is ${target.moduleVersionCode}"
            )
        }
        return RollbackRecovery.Completed(target.moduleVersionName)
    }
}

/** The root actions a combined rollback performs, in order: prepare, stage, hand off. */
interface RollbackRootSteps {
    /**
     * Writes the script and the journal into app storage, where a failure is still this process's
     * to report. It runs before anything is staged, so a full cache changes nothing on the phone.
     */
    suspend fun prepare(journal: RollbackJournal): RootStepResult

    /**
     * Verifies the published archive through root, then stages it through the detected provider.
     * The archive lives in root-only `/data/adb`, so its existence and digest are root's answers.
     */
    suspend fun stagePreviousModule(archive: PublishedModuleArchive): RootStepResult

    /** Publishes the prepared journal and script, then starts the script detached. */
    suspend fun startRemovalScript(): RootStepResult
}

/** What a combined rollback already applied, so a failure names it instead of hiding it. */
enum class RollbackStep { MODULE_STAGED, REMOVAL_STARTED }

sealed interface RollbackState {
    data object Idle : RollbackState

    /** Writing the script and the journal locally; nothing on the phone changed yet. */
    data object Preparing : RollbackState

    data object StagingModule : RollbackState

    data object StartingRemoval : RollbackState

    /** The detached script owns the removal now; the phone reboots once it is verified. */
    data class Started(val versionName: String?) : RollbackState

    /** [applied] is what the phone already changed; an empty list means nothing changed. */
    data class Failed(
        val code: RollbackFailure,
        val detail: String? = null,
        val applied: List<RollbackStep> = emptyList()
    ) : RollbackState

    val busy: Boolean
        get() = this is Preparing || this is StagingModule || this is StartingRemoval
}

enum class RollbackFailure {
    NO_KEPT_APK,
    NO_PREVIOUS_MODULE,
    SYSTEM_VERSION_UNKNOWN,
    STORAGE_UNREADABLE,
    STALE_ARCHIVE,
    ARCHIVE_MISMATCH,

    /** The app already runs the kept version, so there is nothing to roll back to. */
    APP_NOT_NEWER,

    /** The local script or journal could not be written, so nothing was staged. */
    PREPARATION_FAILED,
    MODULE_STAGE_FAILED,
    REMOVAL_NOT_STARTED
}

/**
 * The combined rollback: stage the published previous module as root, then hand the rest to a
 * detached root script, because removing this app's own update freezes and kills its process.
 * A failing step stops the sequence and reports what was already applied.
 */
class ModuleRollbackCoordinator(
    private val archives: ModuleArchivePublisher,
    private val root: RollbackRootSteps,
    private val app: RunningApp,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The archive a pending journal already staged: a retry then only repeats the hand-off. */
    private val stagedArchiveSha256: String? = null,
    /**
     * The digest a pending journal recorded. Recovery completes that rollback, so it resolves this
     * generation by name and refuses a different archive instead of substituting its target.
     */
    private val recoverySha256: String? = null,
    private val onState: (RollbackState) -> Unit
) {
    suspend fun rollback(expectedPreviousVersionCode: Long): RollbackState {
        val state = if (recoverySha256 != null) archives.readGeneration(recoverySha256) else archives.read()
        val archive = when (state) {
            is RollbackArchiveState.Present -> state.archive

            RollbackArchiveState.Missing ->
                return finish(
                    RollbackFailure.NO_PREVIOUS_MODULE,
                    if (recoverySha256 == null) {
                        "no previous module is published"
                    } else {
                        "no archive is published for the $recoverySha256 this rollback recorded"
                    }
                )

            RollbackArchiveState.Unreadable ->
                return finish(
                    RollbackFailure.NO_PREVIOUS_MODULE,
                    "the rollback directory could not be read"
                )

            is RollbackArchiveState.Mismatch ->
                return finish(
                    RollbackFailure.ARCHIVE_MISMATCH,
                    "the archive published as ${state.expectedSha256} hashes to ${state.actualSha256}"
                )
        }
        if (recoverySha256 != null && !archive.sha256.equals(recoverySha256, ignoreCase = true)) {
            return finish(
                RollbackFailure.ARCHIVE_MISMATCH,
                "the archive for the recorded rollback is ${archive.sha256}, not the recorded $recoverySha256"
            )
        }
        if (archive.versionCode != expectedPreviousVersionCode) {
            return finish(
                RollbackFailure.ARCHIVE_MISMATCH,
                "the published archive is ${archive.versionCode}, the rollback expected $expectedPreviousVersionCode"
            )
        }

        val journal = RollbackJournal(
            createdAtMillis = clock(),
            step = RollbackJournalStep.MODULE_STAGED,
            targets = RollbackTargets(
                packageName = app.packageName,
                moduleVersionName = archive.versionName,
                moduleVersionCode = archive.versionCode,
                moduleSha256 = archive.sha256,
                fromAppVersionName = app.versionName,
                fromAppVersionCode = app.versionCode
            )
        )
        // The fallible local writes come first: if the cache is full, nothing on the phone changed
        // and the coordinator still holds the explanation to show.
        onState(RollbackState.Preparing)
        val prepared = root.prepare(journal)
        if (!prepared.ok) return finish(RollbackFailure.PREPARATION_FAILED, prepared.detail)

        if (!archive.sha256.equals(stagedArchiveSha256, ignoreCase = true)) {
            onState(RollbackState.StagingModule)
            val staged = root.stagePreviousModule(archive)
            if (!staged.ok) return finish(RollbackFailure.MODULE_STAGE_FAILED, staged.detail)
        }

        onState(RollbackState.StartingRemoval)
        val started = root.startRemovalScript()
        if (!started.ok) {
            return finish(
                RollbackFailure.REMOVAL_NOT_STARTED,
                started.detail,
                applied = listOf(RollbackStep.MODULE_STAGED)
            )
        }
        return finish(RollbackState.Started(archive.versionName))
    }

    private fun finish(
        code: RollbackFailure,
        reason: String? = null,
        applied: List<RollbackStep> = emptyList()
    ): RollbackState = finish(RollbackState.Failed(code, reason, applied))

    private fun finish(state: RollbackState): RollbackState {
        onState(state)
        return state
    }
}
