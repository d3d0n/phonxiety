package com.phonxiety.bridge.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The allowlist is the security boundary: exact scripts, and no path that could escape. */
class RootCommandTest {
    @Test
    fun idAndVersionScripts() {
        assertEquals("id", RootCommand.id().script)
        assertEquals("magisk -v", RootCommand.magiskVersionName().script)
        assertEquals("magisk -V", RootCommand.magiskVersionCode().script)
        assertEquals("ksud -V", RootCommand.ksudVersion().script)
        assertEquals("apd -V", RootCommand.apdVersion().script)
        assertEquals("reboot", RootCommand.reboot().script)
    }

    @Test
    fun magiskLifecycleScripts() {
        assertEquals(
            "magisk --install-module /data/local/tmp/phonxiety_bridge.zip",
            RootCommand.magiskInstallModule("/data/local/tmp/phonxiety_bridge.zip").script
        )
        assertEquals("magisk --remove-modules", RootCommand.magiskRemoveAllModules().script)
        assertEquals(
            "touch /data/adb/modules/phonxiety_bridge/disable",
            RootCommand.setModuleFlag("phonxiety_bridge", ModuleFlag.DISABLE).script
        )
        assertEquals(
            "rm -f /data/adb/modules/phonxiety_bridge/remove",
            RootCommand.clearModuleFlag("phonxiety_bridge", ModuleFlag.REMOVE).script
        )
    }

    @Test
    fun ksudAndApdScripts() {
        assertEquals(
            "ksud module install /data/local/tmp/phonxiety_bridge.zip",
            RootCommand.ksudModule(ModuleAction.INSTALL, "/data/local/tmp/phonxiety_bridge.zip").script
        )
        assertEquals("ksud module enable phonxiety_bridge", RootCommand.ksudModule(ModuleAction.ENABLE, "phonxiety_bridge").script)
        assertEquals("ksud module disable phonxiety_bridge", RootCommand.ksudModule(ModuleAction.DISABLE, "phonxiety_bridge").script)
        assertEquals("ksud module uninstall phonxiety_bridge", RootCommand.ksudModule(ModuleAction.UNINSTALL, "phonxiety_bridge").script)
        assertEquals("apd module install /data/local/tmp/x.zip", RootCommand.apdModule(ModuleAction.INSTALL, "/data/local/tmp/x.zip").script)
        assertEquals("apd module enable phonxiety_bridge", RootCommand.apdModule(ModuleAction.ENABLE, "phonxiety_bridge").script)
        assertEquals("apd module disable phonxiety_bridge", RootCommand.apdModule(ModuleAction.DISABLE, "phonxiety_bridge").script)
        assertEquals("apd module uninstall phonxiety_bridge", RootCommand.apdModule(ModuleAction.UNINSTALL, "phonxiety_bridge").script)
    }

    @Test
    fun stagingAndModuleReads() {
        assertEquals(
            "cp -f /data/user/0/com.phonxiety.bridge/cache/module.zip /data/local/tmp/phonxiety_bridge.zip",
            RootCommand.copy(
                "/data/user/0/com.phonxiety.bridge/cache/module.zip",
                "phonxiety_bridge.zip"
            ).script
        )
        assertEquals("ls -1 /data/adb", RootCommand.listDir("/data/adb").script)
        assertEquals(
            "cat /data/adb/modules/phonxiety_bridge/module.prop",
            RootCommand.readModuleFile("/data/adb/modules/phonxiety_bridge/module.prop").script
        )
    }

    @Test
    fun rejectsShellMetacharactersAndEscapes() {
        assertThrows(IllegalArgumentException::class.java) {
            RootCommand.copy("/tmp/a.zip; rm -rf /", "x.zip")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootCommand.copy("/tmp/a.zip", "x.zip; reboot")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootCommand.ksudModule(ModuleAction.DISABLE, "x; reboot")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootCommand.ksudModule(ModuleAction.INSTALL, "/data/local/tmp/a.zip && reboot")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootCommand.listDir("/system")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootCommand.readModuleFile("/data/data/com.phonxiety.bridge/secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootCommand.copy("/data/local/tmp/../../etc/passwd", "x.zip")
        }
    }
}
