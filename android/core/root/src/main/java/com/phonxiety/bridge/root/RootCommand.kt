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

        /** `reboot`, the fallback when `android.permission.REBOOT` is not granted. */
        fun reboot(): RootCommand = RootCommand(listOf("reboot"))

        const val ADB_ROOT: String = "/data/adb"
        const val MODULES_DIR: String = "$ADB_ROOT/modules"
        const val MODULES_UPDATE_DIR: String = "$ADB_ROOT/modules_update"
        const val STAGING_DIR: String = "/data/local/tmp"

        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val SAFE_ABSOLUTE_PATH = Regex("/[A-Za-z0-9._/-]{1,255}")

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
