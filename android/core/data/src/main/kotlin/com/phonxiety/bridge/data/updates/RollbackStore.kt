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

/** Where a built module archive is published: root-owned, outside app storage. */
interface ModuleArchivePublisher {
    /** Publishes [archive] with [metadata] atomically; false when any root step failed. */
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
 */
class RootRollbackStore(
    private val shell: RootShell,
    private val workspace: File
) : ModuleArchivePublisher,
    RollbackJournalStore {
    override suspend fun publish(archive: File, metadata: File): Boolean {
        if (!shell.exec(RootCommand.createRollbackDirectory()).ok) return false
        if (!publishFile(archive, RollbackFiles.ARCHIVE)) return false
        return publishFile(metadata, RollbackFiles.ARCHIVE_META)
    }

    override suspend fun read(): RollbackArchiveState {
        // `/data/adb` tells "never captured" (the directory is absent) from "root was denied".
        val adb = list(RootCommand.ADB_ROOT) ?: return RollbackArchiveState.Unreadable
        if (RollbackFiles.DIRECTORY_NAME !in adb) return RollbackArchiveState.Missing
        val entries = list(RollbackFiles.DIRECTORY) ?: return RollbackArchiveState.Unreadable
        if (RollbackFiles.ARCHIVE !in entries || RollbackFiles.ARCHIVE_META !in entries) {
            return RollbackArchiveState.Missing
        }
        val meta = shell.exec(RootCommand.readRollbackFile(RollbackFiles.ARCHIVE_META))
        if (!meta.ok) return RollbackArchiveState.Unreadable
        val archive = ModuleArchiveMeta.parse(meta.stdout) ?: return RollbackArchiveState.Unreadable
        return RollbackArchiveState.Present(archive)
    }

    override suspend fun readJournal(): RollbackJournal? {
        val output = shell.exec(RootCommand.readRollbackFile(RollbackFiles.JOURNAL))
        return if (output.ok) RollbackJournalCodec.parse(output.stdout) else null
    }

    override suspend fun removeJournal(): Boolean = shell.exec(RootCommand.removeRollbackFile(RollbackFiles.JOURNAL)).ok

    /** Publishes the script and the journal, then starts the script detached. */
    suspend fun startScript(journal: RollbackJournal): RootStepResult {
        workspace.mkdirs()
        val script = File(workspace, RollbackFiles.SCRIPT).apply { writeText(RollbackScript.TEXT) }
        val journalFile = File(workspace, RollbackFiles.JOURNAL)
            .apply { writeText(RollbackJournalCodec.write(journal)) }
        return try {
            if (!shell.exec(RootCommand.createRollbackDirectory()).ok) {
                return RootStepResult(false, "root could not create ${RollbackFiles.DIRECTORY}")
            }
            if (!publishFile(script, RollbackFiles.SCRIPT)) {
                return RootStepResult(false, "the rollback script could not be published")
            }
            if (!publishFile(journalFile, RollbackFiles.JOURNAL)) {
                return RootStepResult(false, "the rollback journal could not be published")
            }
            val started = shell.exec(RootCommand.startRollbackScript())
            if (started.ok) {
                RootStepResult(true)
            } else {
                RootStepResult(false, started.combined.ifEmpty { "su exited ${started.exitCode}" })
            }
        } finally {
            script.delete()
            journalFile.delete()
        }
    }

    private suspend fun publishFile(source: File, name: String): Boolean {
        if (!shell.exec(RootCommand.copyIntoRollbackDirectory(source.absolutePath, name)).ok) return false
        return shell.exec(RootCommand.renameRollbackFile(name)).ok
    }

    private suspend fun list(path: String): List<String>? {
        val output = shell.exec(RootCommand.listDir(path))
        if (!output.ok) return null
        return output.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }
}
