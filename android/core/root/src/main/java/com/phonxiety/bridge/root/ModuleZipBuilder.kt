package com.phonxiety.bridge.root

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads module payload files from an asset store: the APK's assets on the phone, a directory in
 * tests and tooling.
 */
fun interface AssetSource {
    /** Stream for [path] relative to the asset base, or null when it does not exist. */
    fun open(path: String): InputStream?
}

/** Reads the payload from a directory, e.g. `android/magisk` from the repository. */
class FileAssetSource(private val root: File) : AssetSource {
    override fun open(path: String): InputStream? {
        val file = File(root, path)
        return if (file.isFile) file.inputStream() else null
    }
}

/**
 * Assembles the provider module zip on the phone. The app cannot ship a zip that contains its own
 * current APK, so the APK is taken from `ApplicationInfo.sourceDir` and everything else comes from
 * the assets that are copied from `android/magisk/` at build time: one source of truth with the
 * packaging task.
 *
 * A module zip entry has no Unix mode in the Java zip format; executable bits are applied by
 * `customize.sh` (`set_perm ... 0755`), which every manager runs through its own shell.
 */
class ModuleZipBuilder(
    private val assets: AssetSource,
    private val apkFile: File,
    private val appVersion: String,
    private val appVersionCode: Int,
    private val outputFile: File,
    private val updateJson: String? = null,
    private val assetBase: String = "",
    private val moduleId: String = ModuleController.MODULE_ID
) {
    suspend fun build(): File = withContext(Dispatchers.IO) {
        require(apkFile.isFile) { "the running APK was not found at ${apkFile.path}" }
        require(appVersion.isNotBlank()) { "the app version name is unknown" }
        require(appVersionCode > 0) { "the app version code is unknown" }
        if (updateJson != null) {
            require(updateJson.startsWith("https://")) { "updateJson must be an https:// URL" }
        }

        val payload = LinkedHashMap<String, ByteArray>()
        for (path in REQUIRED_ASSETS) {
            payload[path] = readAsset(path)
        }
        payload[MODULE_PROP] = renderModuleProp(payload.getValue(MODULE_PROP))
        validatePayload(payload)
        payload[APK_ENTRY] = apkFile.readBytes()

        outputFile.parentFile?.mkdirs()
        val temporary = File(outputFile.path + ".tmp")
        val writtenDirectories = mutableSetOf<String>()
        ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
            for ((path, bytes) in payload) {
                addDirectories(zip, path, writtenDirectories)
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        if (!temporary.renameTo(outputFile)) {
            outputFile.delete()
            require(temporary.renameTo(outputFile)) { "could not write ${outputFile.path}" }
        }
        outputFile
    }

    private fun readAsset(path: String): ByteArray = assets.open(assetBase + path)?.use { it.readBytes() }
        ?: error("module asset is missing: ${assetBase + path}")

    /** Takes `module.prop` from the assets and puts the installed app's identity into it. */
    private fun renderModuleProp(source: ByteArray): ByteArray {
        val rendered = mutableListOf<String>()
        var sawVersion = false
        var sawVersionCode = false
        for (line in String(source, Charsets.UTF_8).lineSequence()) {
            when {
                line.startsWith("id=") -> rendered += "id=$moduleId"

                line.startsWith("version=") -> {
                    rendered += "version=$appVersion"
                    sawVersion = true
                }

                line.startsWith("versionCode=") -> {
                    rendered += "versionCode=$appVersionCode"
                    sawVersionCode = true
                }

                line.startsWith("updateJson=") -> if (updateJson != null) {
                    rendered += "updateJson=$updateJson"
                }

                else -> rendered += line
            }
        }
        require(sawVersion && sawVersionCode) { "module.prop has no version/versionCode fields" }
        if (updateJson == null) {
            rendered.removeAll { it.startsWith("updateJson=") }
        }
        return (rendered.joinToString("\n").trimEnd() + "\n").toByteArray(Charsets.UTF_8)
    }

    /** Fails before an install rather than shipping a zip the installer would refuse or half-apply. */
    private fun validatePayload(payload: Map<String, ByteArray>) {
        val customize = String(payload.getValue(CUSTOMIZE_SH), Charsets.UTF_8)
        require(customize.contains("if [ \"\$API\" -lt")) { "customize.sh does not check \$API" }
        require(customize.contains(APK_ENTRY)) { "customize.sh does not check the packaged APK" }
        require(customize.contains(ALLOWLIST)) {
            "customize.sh does not check the privapp permission allowlist"
        }
        val allowlist = String(payload.getValue(ALLOWLIST), Charsets.UTF_8)
        for (permission in listOf(
            PrivilegedPermissions.CAPTURE_AUDIO_OUTPUT,
            PrivilegedPermissions.MODIFY_PHONE_STATE,
            PrivilegedPermissions.CONTROL_INCALL_EXPERIENCE,
            PrivilegedPermissions.INSTALL_SELF_UPDATES,
            PrivilegedPermissions.REBOOT
        )) {
            require(allowlist.contains(permission)) { "allowlist is missing $permission" }
        }
        require(!allowlist.contains(PackagePermissions.INSTALL_PACKAGES)) {
            "the allowlist must not grant INSTALL_PACKAGES"
        }
        val prop = String(payload.getValue(MODULE_PROP), Charsets.UTF_8)
        require(prop.contains("id=$moduleId")) { "module.prop does not carry the module id" }
        require(!prop.contains(UPDATE_JSON_PLACEHOLDER)) {
            "module.prop still contains the updateJson placeholder"
        }
    }

    private fun addDirectories(zip: ZipOutputStream, path: String, writtenDirectories: MutableSet<String>) {
        val parts = path.split('/').dropLast(1)
        var current = ""
        for (part in parts) {
            current += "$part/"
            if (writtenDirectories.add(current)) {
                zip.putNextEntry(ZipEntry(current))
                zip.closeEntry()
            }
        }
    }

    companion object {
        const val MODULE_PROP: String = "module.prop"
        const val CUSTOMIZE_SH: String = "customize.sh"
        const val ACTION_SH: String = "action.sh"
        const val ACTION_ICON: String = "icon/action.png"
        const val WEB_UI: String = "webroot/index.html"
        const val ALLOWLIST: String =
            "system/etc/permissions/privapp-permissions-com.phonxiety.bridge.xml"
        const val APK_ENTRY: String = "system/priv-app/phonxiety-bridge/phonxiety-bridge.apk"
        const val UPDATE_JSON_PLACEHOLDER: String = "__PHONXIETY_UPDATE_JSON__"

        /** Every file `mise-tasks/phone/build` ships, without the APK added at build time. */
        val REQUIRED_ASSETS: List<String> = listOf(
            MODULE_PROP,
            CUSTOMIZE_SH,
            ACTION_SH,
            ACTION_ICON,
            WEB_UI,
            ALLOWLIST
        )
    }
}

/** Names used only to assert the allowlist stays narrow. */
private object PackagePermissions {
    const val INSTALL_PACKAGES: String = "android.permission.INSTALL_PACKAGES"
}
