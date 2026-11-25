package com.phonxiety.bridge.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The in-progress install must survive process death and the reboot it caused. */
class FileProgressStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun beginThenReadResumesTheOperation() {
        val file = File(temporaryFolder.root, "root/install.progress")
        val store = FileProgressStore(file) { 1_700_000_000_000L }

        assertNull(store.read())

        store.begin(InstallOperation.UPDATE, RootId.KERNELSU)

        assertEquals(
            ProgressRecord(InstallOperation.UPDATE, RootId.KERNELSU, 1_700_000_000_000L),
            store.read()
        )
    }

    @Test
    fun clearRemovesTheRecord() {
        val file = File(temporaryFolder.root, "install.progress")
        val store = FileProgressStore(file)

        store.begin(InstallOperation.INSTALL, RootId.MAGISK)
        store.clear()

        assertNull(store.read())
        assertEquals(false, file.exists())
    }

    @Test
    fun corruptFileReadsAsNoRecord() {
        val file = File(temporaryFolder.root, "install.progress")
        file.writeText("operation=NOT_A_REAL_OPERATION\nprovider=MAGISK\n")
        val store = FileProgressStore(file)

        assertNull(store.read())
    }
}
