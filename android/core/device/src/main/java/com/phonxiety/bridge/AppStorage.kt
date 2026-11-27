package com.phonxiety.bridge

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Device-protected storage for everything the bridge needs before the first
 * unlock after a reboot: the pairing, the iroh secret key, the call ledger and
 * the handset mute snapshot.
 */
internal object AppStorage {
    fun deviceProtected(context: Context): Context = context.applicationContext.createDeviceProtectedStorageContext()

    /**
     * Where a DataStore keeps `name.preferences_pb`: `files/datastore` under
     * [filesDir], the layout `preferencesDataStore` uses.
     */
    fun dataStoreFile(filesDir: File, name: String): File = File(File(filesDir, "datastore"), "$name.preferences_pb")

    /**
     * Writes [bytes] through a temporary file, fsyncs it and renames it into
     * place with owner-only permissions.
     */
    fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            restrict(tmp)
            if (!tmp.renameTo(target)) throw IOException("rename_failed")
            restrict(target)
            syncDirectory(target.parentFile)
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        }
    }

    fun restrict(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    private fun syncDirectory(directory: File?) {
        if (directory == null) return
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
