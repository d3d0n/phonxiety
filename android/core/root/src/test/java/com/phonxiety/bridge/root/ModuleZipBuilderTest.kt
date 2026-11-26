package com.phonxiety.bridge.root

import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The zip built on the phone must have the same layout as the one `mise-tasks/phone/build` ships;
 * only the APK source differs (running install vs the release build).
 */
class ModuleZipBuilderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val files = setOf(
        ModuleZipBuilder.MODULE_PROP,
        ModuleZipBuilder.CUSTOMIZE_SH,
        ModuleZipBuilder.ACTION_SH,
        ModuleZipBuilder.ACTION_ICON,
        ModuleZipBuilder.WEB_UI,
        ModuleZipBuilder.ALLOWLIST,
        ModuleZipBuilder.APK_ENTRY
    )

    private val directories = setOf(
        "icon/",
        "webroot/",
        "system/",
        "system/priv-app/",
        "system/priv-app/phonxiety-bridge/",
        "system/etc/",
        "system/etc/permissions/"
    )

    private fun magiskDirectory(): File {
        val candidates = listOf(File("../../magisk"), File("android/magisk"), File("../magisk"))
        return candidates.firstOrNull { File(it, "module.prop").isFile }
            ?: throw AssertionError("android/magisk was not found from ${File(".").absolutePath}")
    }

    private fun apkFile(): File = temporaryFolder.newFile("app-release.apk").apply {
        writeBytes(ByteArray(64) { it.toByte() })
    }

    private fun builder(
        updateJson: String? = null,
        assets: File = magiskDirectory(),
        apk: File = apkFile(),
        version: String = "9.9.9",
        versionCode: Int = 42
    ): ModuleZipBuilder = ModuleZipBuilder(
        assets = FileAssetSource(assets),
        apkFile = apk,
        appVersion = version,
        appVersionCode = versionCode,
        outputFile = File(temporaryFolder.root, "phonxiety-module.zip"),
        updateJson = updateJson
    )

    private fun entries(zip: File): List<String> = ZipFile(zip).use { archive ->
        archive.entries().asSequence().map { it.name }.toList()
    }

    private fun text(zip: File, path: String): String = ZipFile(zip).use { archive ->
        checkNotNull(archive.getEntry(path)) { "missing zip entry $path" }
        archive.getInputStream(archive.getEntry(path)).bufferedReader().use { it.readText() }
    }

    @Test
    fun layoutMatchesThePackagingTask() = runTest {
        val zip = builder().build()

        assertEquals((files + directories).sorted(), entries(zip).sorted())
    }

    @Test
    fun modulePropCarriesTheRunningAppIdentity() = runTest {
        val zip = builder().build()

        val prop = text(zip, ModuleZipBuilder.MODULE_PROP)
        assertTrue(prop.contains("id=${ModuleController.MODULE_ID}"))
        assertTrue(prop.contains("version=9.9.9"))
        assertTrue(prop.contains("versionCode=42"))
        assertTrue(prop.contains("actionIcon=icon/action.png"))
        assertFalse(prop.contains("updateJson="))
        assertFalse(prop.contains(ModuleZipBuilder.UPDATE_JSON_PLACEHOLDER))
    }

    @Test
    fun updateJsonIsAddedOnlyWhenConfigured() = runTest {
        val zip = builder(updateJson = "https://updates.example.com/phonxiety/module.json").build()

        assertTrue(
            text(zip, ModuleZipBuilder.MODULE_PROP)
                .contains("updateJson=https://updates.example.com/phonxiety/module.json")
        )
    }

    @Test
    fun executableBitsAreAppliedByTheInstaller() = runTest {
        val zip = builder().build()
        val customize = text(zip, ModuleZipBuilder.CUSTOMIZE_SH)

        // Java's zip format stores no Unix mode, so customize.sh is the authority for the modes.
        assertTrue(customize.contains("set_perm \"\$MODPATH/action.sh\" 0 0 0755"))
        assertTrue(customize.contains("set_perm_recursive \"\$MODPATH/system\" 0 0 0755 0644"))
        assertTrue(customize.contains("if [ \"\$API\" -lt"))
        assertTrue(customize.contains(ModuleZipBuilder.APK_ENTRY))
        assertTrue(customize.contains(ModuleZipBuilder.ALLOWLIST))
    }

    @Test
    fun allowlistStaysExactlyThePrivilegedPermissions() = runTest {
        val zip = builder().build()
        val allowlist = text(zip, ModuleZipBuilder.ALLOWLIST)

        for (permission in PrivilegedPermissions.ALL) {
            assertTrue("allowlist is missing $permission", allowlist.contains("<permission name=\"$permission\" />"))
        }
        val granted = Regex("""<permission name="([^"]+)"""")
            .findAll(allowlist)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(PrivilegedPermissions.ALL, granted)
        assertFalse(granted.contains("android.permission.INSTALL_PACKAGES"))
    }

    @Test
    fun noBootScriptsOrZygiskPayloads() = runTest {
        val zip = builder().build()
        val forbidden = listOf(
            "post-fs-data.sh",
            "post-mount.sh",
            "service.sh",
            "boot-completed.sh",
            "uninstall.sh",
            "system.prop",
            "sepolicy.rule",
            "skip_mount",
            "post-fs-data"
        )
        for (entry in entries(zip)) {
            assertFalse("unexpected $entry", forbidden.any { entry.endsWith(it) })
            assertFalse("unexpected zygisk payload $entry", entry.contains("zygisk", ignoreCase = true))
        }
    }

    @Test
    fun missingAssetFailsBeforeWritingAnything() = runTest {
        val emptyAssets = temporaryFolder.newFolder("empty-assets")

        try {
            builder(assets = emptyAssets).build()
            fail("an incomplete payload must not produce a zip")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("module asset is missing"))
        }
    }

    @Test
    fun missingApkFails() = runTest {
        val absentApk = File(temporaryFolder.root, "no-such.apk")

        try {
            builder(apk = absentApk).build()
            fail("a module without its APK must not be produced")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("running APK was not found"))
        }
    }

    @Test
    fun insecureUpdateJsonIsRejected() = runTest {
        try {
            builder(updateJson = "http://insecure.example.com/module.json").build()
            fail("updateJson must be https")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("https://"))
        }
    }
}
