package com.phonxiety.bridge.data.updates

import com.phonxiety.bridge.root.RootCommand
import com.phonxiety.bridge.root.RootShell
import java.io.File

/** `module-previous.json`: the identity the archive was published with. */
object ModuleArchiveMeta {
    fun write(archive: PublishedModuleArchive): String = buildString {
        append('{')
        append("\"version\":")
        append(quote(archive.versionName))
        append(',')
        append("\"version_code\":").append(archive.versionCode).append(',')
        append("\"sha256\":")
        append(quote(archive.sha256))
        append('}')
    }

    fun parse(text: String?): PublishedModuleArchive? {
        val json = text?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val code = Regex("\"version_code\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        val version = Regex("\"version\"\\s*:\\s*(null|\"([^\"]*)\")").find(json)?.let { match ->
            if (match.groupValues[1] == "null") null else match.groupValues[2]
        }
        val sha256 = Regex("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]*)\"").find(json)?.groupValues?.get(1).orEmpty()
        return PublishedModuleArchive(version, code, sha256)
    }

    private fun quote(value: String?): String = if (value == null) "null" else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** Names under `/data/adb/phonxiety`, the root-owned home of a rollback. */
object RollbackFiles {
    const val DIRECTORY: String = RootCommand.ROLLBACK_DIR

    /** Inside one generation: the archive and the identity it was published with. */
    const val ARCHIVE: String = "module-previous.zip"
    const val ARCHIVE_META: String = "module-previous.json"

    /** The pointer that names the current generation; moving it is the whole publish. */
    const val POINTER: String = RootCommand.ROLLBACK_POINTER
    const val JOURNAL: String = "rollback.json"
    const val SCRIPT: String = RootCommand.ROLLBACK_SCRIPT
    const val LOG: String = RootCommand.ROLLBACK_LOG
    const val DIRECTORY_NAME: String = "phonxiety"
}

/** A complete generation under `/data/adb/phonxiety`, and the identity published inside it. */
data class PublishedArchiveFile(
    val generation: String,
    val path: String,
    val archive: PublishedModuleArchive
)

/** What the pointer and the generation it names say right now. */
sealed interface PublishedArchiveLookup {
    data class Found(val file: PublishedArchiveFile) : PublishedArchiveLookup

    /** No pointer: nothing was ever published. */
    data object Missing : PublishedArchiveLookup

    /** The pointer names something that cannot be read as a published archive. */
    data object Unreadable : PublishedArchiveLookup
}

/** Where the built module archive is published: root-owned, outside app storage. */
interface ModuleArchivePublisher {
    /**
     * Publishes [archive] and [metadata] as one generation, then names it with a single atomic
     * move of the pointer: readers see either the previous complete generation or the new one.
     */
    suspend fun publish(archive: File, metadata: File): Boolean

    /** The published archive's state, as root lets the app read it. */
    suspend fun read(): RollbackArchiveState
}

/**
 * The root-owned journal of the last rollback. It is written before the detached script starts and
 * updated by it, so a rollback survives the app process being killed and the phone rebooting.
 */
interface RollbackJournalStore {
    /** The last rollback's journal, or null when none was written or root cannot read it. */
    suspend fun readJournal(): RollbackJournal?

    /** Drops a journal whose rollback was verified complete. */
    suspend fun removeJournal(): Boolean
}

/**
 * Everything this app reads and writes under `/data/adb/phonxiety`: the previous module archive and
 * its identity, the rollback journal, and the detached script. It survives an app-data clear, which
 * removing the app's own update can cause.
 *
 * The archive lives in a generation named after its SHA-256 and is reached through a pointer file;
 * publishing writes the whole generation first and moves the pointer once. Its bytes are hashed
 * through root, because the app cannot read `/data/adb` on its own.
 */
class RootRollbackStore(
    private val shell: RootShell,
    private val workspace: File
) : ModuleArchivePublisher,
    RollbackJournalStore {
    override suspend fun publish(archive: File, metadata: File): Boolean {
        val identity = ModuleArchiveMeta.parse(readText(metadata)) ?: return false
        val generation = RootCommand.rollbackGenerationName(identity.sha256) ?: return false
        if (!shell.exec(RootCommand.createRollbackDirectory()).ok) return false
        if (!shell.exec(RootCommand.createRollbackGeneration(generation)).ok) return false
        if (!publishGenerationFile(archive, generation, RollbackFiles.ARCHIVE)) return false
        if (!publishGenerationFile(metadata, generation, RollbackFiles.ARCHIVE_META)) return false
        // One switch: until this move the pointer still names the previous complete generation.
        if (!switchPointer(generation)) return false
        removeOtherGenerations(generation)
        return true
    }

    override suspend fun read(): RollbackArchiveState {
        // `/data/adb` tells "never captured" (the directory is absent) from "root was denied".
        val adb = list(RootCommand.ADB_ROOT) ?: return RollbackArchiveState.Unreadable
        if (RollbackFiles.DIRECTORY_NAME !in adb) return RollbackArchiveState.Missing
        val published = when (val lookup = publishedArchive()) {
            is PublishedArchiveLookup.Unreadable -> return RollbackArchiveState.Unreadable
            is PublishedArchiveLookup.Missing -> return RollbackArchiveState.Missing
            is PublishedArchiveLookup.Found -> lookup.file
        }
        val digest = digestOf(published.generation) ?: return RollbackArchiveState.Unreadable
        return if (digest.equals(published.archive.sha256, ignoreCase = true)) {
            RollbackArchiveState.Present(published.archive)
        } else {
            RollbackArchiveState.Mismatch(published.archive.sha256, digest)
        }
    }

    /** The generation the pointer names, with the identity its own metadata carries. */
    suspend fun publishedArchive(): PublishedArchiveLookup {
        val pointer = shell.exec(RootCommand.readRollbackFile(RollbackFiles.POINTER))
        if (!pointer.ok) return PublishedArchiveLookup.Missing
        val generation = pointer.stdout.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!RootCommand.ROLLBACK_GENERATION.matches(generation)) return PublishedArchiveLookup.Unreadable
        val meta = shell.exec(RootCommand.readRollbackGenerationFile(generation, RollbackFiles.ARCHIVE_META))
        if (!meta.ok) return PublishedArchiveLookup.Unreadable
        val identity = ModuleArchiveMeta.parse(meta.stdout) ?: return PublishedArchiveLookup.Unreadable
        if (RootCommand.rollbackGenerationName(identity.sha256) != generation) return PublishedArchiveLookup.Unreadable
        return PublishedArchiveLookup.Found(
            PublishedArchiveFile(
                generation = generation,
                path = RootCommand.rollbackGenerationFilePath(generation, RollbackFiles.ARCHIVE),
                archive = identity
            )
        )
    }

    /** `sha256sum` of the generation's archive, read through root; null when root cannot read it. */
    suspend fun digestOf(generation: String): String? {
        val output = shell.exec(RootCommand.hashRollbackGenerationFile(generation, RollbackFiles.ARCHIVE))
        if (!output.ok) return null
        val digest = output.stdout.trim().substringBefore(' ').trim().lowercase()
        return digest.takeIf { SHA256.matches(it) }
    }

    override suspend fun readJournal(): RollbackJournal? {
        val output = shell.exec(RootCommand.readRollbackFile(RollbackFiles.JOURNAL))
        return if (output.ok) RollbackJournalCodec.parse(output.stdout) else null
    }

    override suspend fun removeJournal(): Boolean = shell.exec(RootCommand.removeRollbackFile(RollbackFiles.JOURNAL)).ok

    /**
     * Writes the script and the journal into app storage. This is the fallible step, and it runs
     * before anything is staged: a full or unwritable cache then changes nothing on the phone and
     * the coordinator reports the explanation instead of losing it with the next process.
     */
    suspend fun prepareScript(journal: RollbackJournal): RootStepResult = try {
        if (!workspace.isDirectory && !workspace.mkdirs()) {
            RootStepResult(false, "the rollback workspace ${workspace.path} could not be created")
        } else {
            File(workspace, RollbackFiles.SCRIPT).writeText(RollbackScript.TEXT)
            File(workspace, RollbackFiles.JOURNAL).writeText(RollbackJournalCodec.write(journal))
            RootStepResult(true)
        }
    } catch (error: Exception) {
        RootStepResult(false, "the rollback files could not be written: ${error.message ?: error.javaClass.simpleName}")
    }

    /** Publishes the prepared script and journal as root, then starts the script detached. */
    suspend fun startScript(): RootStepResult {
        val script = File(workspace, RollbackFiles.SCRIPT)
        val journal = File(workspace, RollbackFiles.JOURNAL)
        if (!script.isFile || !journal.isFile) {
            return RootStepResult(false, "the rollback files were not prepared")
        }
        return try {
            if (!shell.exec(RootCommand.createRollbackDirectory()).ok) {
                RootStepResult(false, "root could not create ${RollbackFiles.DIRECTORY}")
            } else if (!publishRootFile(script, RollbackFiles.SCRIPT)) {
                RootStepResult(false, "the rollback script could not be published")
            } else if (!publishRootFile(journal, RollbackFiles.JOURNAL)) {
                RootStepResult(false, "the rollback journal could not be published")
            } else {
                val started = shell.exec(RootCommand.startRollbackScript())
                if (started.ok) {
                    RootStepResult(true)
                } else {
                    RootStepResult(false, started.combined.ifEmpty { "su exited ${started.exitCode}" })
                }
            }
        } finally {
            script.delete()
            journal.delete()
        }
    }

    private suspend fun publishGenerationFile(source: File, generation: String, name: String): Boolean {
        if (!shell.exec(RootCommand.copyIntoRollbackGeneration(source.absolutePath, generation, name)).ok) return false
        return shell.exec(RootCommand.renameInRollbackGeneration(generation, name)).ok
    }

    private suspend fun switchPointer(generation: String): Boolean {
        val pointer = File(workspace, RollbackFiles.POINTER)
        return try {
            pointer.parentFile?.mkdirs()
            pointer.writeText("$generation\n")
            publishRootFile(pointer, RollbackFiles.POINTER)
        } catch (error: Exception) {
            false
        } finally {
            pointer.delete()
        }
    }

    private suspend fun publishRootFile(source: File, name: String): Boolean {
        if (!shell.exec(RootCommand.copyIntoRollbackDirectory(source.absolutePath, name)).ok) return false
        return shell.exec(RootCommand.renameRollbackFile(name)).ok
    }

    /** Best effort: a generation the pointer no longer names is dead weight, never a rollback. */
    private suspend fun removeOtherGenerations(current: String) {
        for (entry in list(RollbackFiles.DIRECTORY).orEmpty()) {
            if (entry == current || !RootCommand.ROLLBACK_GENERATION.matches(entry)) continue
            shell.exec(RootCommand.removeRollbackGeneration(entry))
        }
    }

    private fun readText(file: File): String? = runCatching { file.readText() }.getOrNull()

    private suspend fun list(path: String): List<String>? {
        val output = shell.exec(RootCommand.listDir(path))
        if (!output.ok) return null
        return output.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    private companion object {
        val SHA256: Regex = Regex("[0-9a-f]{64}")
    }
}
