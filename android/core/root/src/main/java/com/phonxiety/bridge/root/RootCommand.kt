package com.phonxiety.bridge.root

/**
 * The complete set of commands the app may run as root. Construction is private: callers can only
 * ask for the builders below, each of which validates its arguments against a strict charset, so no
 * user text can reach a root shell. [RootShell] executes [script] through `su -c` and nothing else.
 */
class RootCommand private constructor(
    /** The shell words after `su -c`. Never empty. */
    val argv: List<String>
) {
    /** The single argument handed to `su -c`. */
    val script: String get() = argv.joinToString(" ")

    override fun toString(): String = script

    companion object {
        /** `su -c id`, the root/denial probe. */
        fun id(): RootCommand = RootCommand(listOf("id"))

        /**
         * `su -v`, the answerer's own banner: `<version>:<SUFFIX>`, e.g. `31.1:MAGISKSU` (Magisk),
         * `11282:APatch`, and the KernelSU family's own spelling. It is the only signal that names
         * the provider without guessing from files another provider may have left behind.
         */
        fun suVersion(): RootCommand = RootCommand(listOf("su", "-v"))

        /** Magisk version name, e.g. `31.1:MAGISK`. */
        fun magiskVersionName(): RootCommand = RootCommand(listOf("magisk", "-v"))

        /** Magisk version code (integer). */
        fun magiskVersionCode(): RootCommand = RootCommand(listOf("magisk", "-V"))

        fun ksudVersion(): RootCommand = RootCommand(listOf("ksud", "-V"))

        fun apdVersion(): RootCommand = RootCommand(listOf("apd", "-V"))

        /** `magisk --install-module <zip>`; the zip must already be under `/data/local/tmp`. */
        fun magiskInstallModule(zipPath: String): RootCommand = RootCommand(listOf("magisk", "--install-module", absolutePath(zipPath, "zip")))

        /**
         * `magisk --remove-modules` removes every module Magisk manages. Only Doctor's guided
         * recovery offers this; a normal uninstall uses the `remove` flag file instead.
         */
        fun magiskRemoveAllModules(): RootCommand = RootCommand(listOf("magisk", "--remove-modules"))

        /** Copy the staged zip from the app's cache into root-owned temporary storage. */
        fun copy(sourcePath: String, destinationName: String): RootCommand {
            val source = absolutePath(sourcePath, "source")
            val destination = "$STAGING_DIR/${safeName(destinationName, "destination")}"
            return RootCommand(listOf("cp", "-f", source, destination))
        }

        /** `touch /data/adb/modules/<id>/disable` (or `remove`) — the flag-file fallback. */
        fun setModuleFlag(moduleId: String, flag: ModuleFlag): RootCommand = RootCommand(listOf("touch", moduleFlagPath(moduleId, flag)))

        /** `rm -f /data/adb/modules/<id>/disable` — re-enable through the flag file. */
        fun clearModuleFlag(moduleId: String, flag: ModuleFlag): RootCommand = RootCommand(listOf("rm", "-f", moduleFlagPath(moduleId, flag)))

        /** `ksud module install|enable|disable|uninstall <arg>`. */
        fun ksudModule(action: ModuleAction, argument: String): RootCommand = RootCommand(listOf("ksud", "module", action.cli, moduleArgument(action, argument)))

        /** `apd module install|enable|disable|uninstall <arg>`. */
        fun apdModule(action: ModuleAction, argument: String): RootCommand = RootCommand(listOf("apd", "module", action.cli, moduleArgument(action, argument)))

        /** `ls -1 <dir>` restricted to `/data/adb`, used to read the module tree as root. */
        fun listDir(dir: String): RootCommand {
            val path = absolutePath(dir, "directory")
            require(path == ADB_ROOT || path.startsWith("$ADB_ROOT/")) {
                "only /data/adb may be listed as root: $dir"
            }
            return RootCommand(listOf("ls", "-1", path))
        }

        /** `cat <file>` restricted to `/data/adb/modules*`. */
        fun readModuleFile(path: String): RootCommand {
            val file = absolutePath(path, "file")
            require(file.startsWith("$ADB_ROOT/modules/") || file.startsWith("$ADB_ROOT/modules_update/")) {
                "only module metadata may be read as root: $path"
            }
            return RootCommand(listOf("cat", file))
        }

        /**
         * `pm install -r [-d] <apk>` for the kept previous APK. `-d` allows the downgrade a rollback
         * always is; the path is validated like every other root argument and must already be under
         * root-owned temporary storage.
         */
        fun pmInstall(apkPath: String, allowDowngrade: Boolean = true): RootCommand {
            val apk = absolutePath(apkPath, "apk")
            val argv = mutableListOf("pm", "install", "-r")
            if (allowDowngrade) argv += "-d"
            argv += apk
            return RootCommand(argv)
        }

        /**
         * `mkdir -p /data/adb/phonxiety`: the root-owned directory a rollback has to survive an
         * app-data clear in, so it cannot live in the app's own files.
         */
        fun createRollbackDirectory(): RootCommand = RootCommand(listOf("mkdir", "-p", ROLLBACK_DIR))

        /** `cp -f <source> /data/adb/phonxiety/<name>.tmp`, published by [renameRollbackFile]. */
        fun copyIntoRollbackDirectory(sourcePath: String, name: String): RootCommand = RootCommand(
            listOf("cp", "-f", absolutePath(sourcePath, "source"), "$ROLLBACK_DIR/${rollbackFileName(name)}.tmp")
        )

        /** `mv -f <name>.tmp <name>`: the atomic publish of the pointer, the journal or the script. */
        fun renameRollbackFile(name: String): RootCommand {
            val file = rollbackFileName(name)
            return RootCommand(listOf("mv", "-f", "$ROLLBACK_DIR/$file.tmp", "$ROLLBACK_DIR/$file"))
        }

        /** `cat /data/adb/phonxiety/<name>`: the pointer, the journal and the script's own log. */
        fun readRollbackFile(name: String): RootCommand = RootCommand(listOf("cat", "$ROLLBACK_DIR/${rollbackFileName(name)}"))

        /** `rm -f /data/adb/phonxiety/<name>`. */
        fun removeRollbackFile(name: String): RootCommand = RootCommand(listOf("rm", "-f", "$ROLLBACK_DIR/${rollbackFileName(name)}"))

        /** `mkdir -p /data/adb/phonxiety/<generation>`: one complete archive generation. */
        fun createRollbackGeneration(generation: String): RootCommand = RootCommand(listOf("mkdir", "-p", rollbackGenerationPath(generation)))

        /** `/data/adb/phonxiety/<generation>/<name>`, the path root copies from and hashes. */
        fun rollbackGenerationFilePath(generation: String, name: String): String = "${rollbackGenerationPath(generation)}/${rollbackFileName(name)}"

        /** `cp -f <source> /data/adb/phonxiety/<generation>/<name>.tmp`, published by the rename below. */
        fun copyIntoRollbackGeneration(sourcePath: String, generation: String, name: String): RootCommand = RootCommand(
            listOf(
                "cp",
                "-f",
                absolutePath(sourcePath, "source"),
                "${rollbackGenerationFilePath(generation, name)}.tmp"
            )
        )

        /** `mv -f <generation>/<name>.tmp <generation>/<name>`: the atomic publish inside a generation. */
        fun renameInRollbackGeneration(generation: String, name: String): RootCommand {
            val path = rollbackGenerationFilePath(generation, name)
            return RootCommand(listOf("mv", "-f", "$path.tmp", path))
        }

        /** `cat /data/adb/phonxiety/<generation>/<name>`: the archive's metadata. */
        fun readRollbackGenerationFile(generation: String, name: String): RootCommand = RootCommand(listOf("cat", rollbackGenerationFilePath(generation, name)))

        /**
         * `sha256sum /data/adb/phonxiety/<generation>/<name>`: the digest of the exact bytes root
         * would copy into staging, so the archive is verified where the app cannot read it.
         */
        fun hashRollbackGenerationFile(generation: String, name: String): RootCommand = RootCommand(listOf("sha256sum", rollbackGenerationFilePath(generation, name)))

        /** `rm -rf /data/adb/phonxiety/<generation>`: a generation the pointer no longer names. */
        fun removeRollbackGeneration(generation: String): RootCommand = RootCommand(listOf("rm", "-rf", rollbackGenerationPath(generation)))

        /**
         * The detached rollback script: `nohup sh … >log 2>&1 &`. `pm uninstall-system-updates`
         * freezes and kills this app's process, so the removal, its verification, the journal
         * entry and the reboot all have to run outside it.
         */
        fun startRollbackScript(): RootCommand = RootCommand(
            listOf(
                "nohup",
                "sh",
                "$ROLLBACK_DIR/$ROLLBACK_SCRIPT",
                ">$ROLLBACK_DIR/$ROLLBACK_LOG",
                "2>&1",
                "&"
            )
        )

        /**
         * The reboot the managers use, for when `android.permission.REBOOT` is not granted:
         * `/system/bin/svc power reboot <reason>` asks PowerManager over binder, which uid 0 passes
         * without writing `sys.powerctl` — a plain `reboot` from a shell context is refused with
         * PROP_ERROR_PERMISSION_DENIED. The plain binary stays as the fallback.
         */
        fun reboot(reason: String): RootCommand {
            require(SAFE_NAME.matches(reason)) { "reboot reason is not a safe token: $reason" }
            return RootCommand(
                listOf("/system/bin/svc", "power", "reboot", reason, "||", "/system/bin/reboot", reason)
            )
        }

        const val ADB_ROOT: String = "/data/adb"
        const val MODULES_DIR: String = "$ADB_ROOT/modules"
        const val MODULES_UPDATE_DIR: String = "$ADB_ROOT/modules_update"
        const val STAGING_DIR: String = "/data/local/tmp"

        /** Root-owned home of the rollback archive, its journal and the detached script. */
        const val ROLLBACK_DIR: String = "$ADB_ROOT/phonxiety"
        const val ROLLBACK_SCRIPT: String = "rollback.sh"
        const val ROLLBACK_LOG: String = "rollback.log"

        /** The one file that names the current archive generation; moving it is the publish. */
        const val ROLLBACK_POINTER: String = "current"
        const val ROLLBACK_GENERATION_PREFIX: String = "g-"

        /** A generation is `g-<sha256>`: its name is the digest of the archive it holds. */
        val ROLLBACK_GENERATION: Regex = Regex("$ROLLBACK_GENERATION_PREFIX[0-9a-f]{64}")

        /** `g-<sha256>` for [sha256], or null when it is not a well-formed digest. */
        fun rollbackGenerationName(sha256: String): String? = (ROLLBACK_GENERATION_PREFIX + sha256.trim().lowercase()).takeIf { ROLLBACK_GENERATION.matches(it) }

        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val SAFE_ABSOLUTE_PATH = Regex("/[A-Za-z0-9._/-]{1,255}")

        private fun rollbackFileName(name: String): String = safeName(name, "rollback file")

        private fun rollbackGenerationPath(generation: String): String {
            require(ROLLBACK_GENERATION.matches(generation)) { "generation is not a safe name: $generation" }
            return "$ROLLBACK_DIR/$generation"
        }

        private fun absolutePath(value: String, what: String): String {
            require(SAFE_ABSOLUTE_PATH.matches(value) && !value.contains("..")) {
                "$what path is not a safe absolute path: $value"
            }
            return value
        }

        private fun safeName(value: String, what: String): String {
            require(SAFE_NAME.matches(value)) { "$what is not a safe file name: $value" }
            return value
        }

        private fun moduleArgument(action: ModuleAction, argument: String): String = if (action == ModuleAction.INSTALL) {
            absolutePath(argument, "zip")
        } else {
            checkedModuleId(argument)
        }

        private fun checkedModuleId(value: String): String {
            require(SAFE_NAME.matches(value)) { "module id is not safe: $value" }
            return value
        }

        private fun moduleFlagPath(moduleId: String, flag: ModuleFlag): String = "$MODULES_DIR/${checkedModuleId(moduleId)}/${flag.fileName}"
    }
}

/** A module lifecycle flag file understood by every provider. */
enum class ModuleFlag(val fileName: String) {
    DISABLE("disable"),
    REMOVE("remove")
}

/** The module subcommands shared by `ksud` and `apd`. */
enum class ModuleAction(val cli: String) {
    INSTALL("install"),
    ENABLE("enable"),
    DISABLE("disable"),
    UNINSTALL("uninstall")
}
