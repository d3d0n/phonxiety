package com.phonxiety.bridge

import android.content.Context
import android.os.UserManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Device-protected storage for everything the bridge needs before the first
 * unlock after a reboot: the pairing, the iroh secret key, the call ledger and
 * the handset mute snapshot.
 *
 * Credential-protected copies written by 0.2.0 are migrated once, after unlock;
 * before unlock [deviceProtected] simply has no pairing to report.
 */
internal object AppStorage {
    private const val TAG = "PhonxietyBridge"

    fun deviceProtected(context: Context): Context = context.applicationContext.createDeviceProtectedStorageContext()

    /**
     * Moves a credential-protected file into device-protected storage when the
     * device-protected copy does not exist yet.  Requires an unlocked user; a
     * locked device leaves both copies untouched.
     */
    fun migrateFile(context: Context, name: String) {
        val target = File(deviceProtected(context).filesDir, name)
        if (target.exists()) return
        if (!userUnlocked(context)) return
        val source = File(context.applicationContext.filesDir, name)
        if (!source.isFile) return
        try {
            writeAtomically(target, source.readBytes())
            if (!source.delete()) Log.w(TAG, "migrated $name but could not remove the old copy")
        } catch (e: Exception) {
            Log.w(TAG, "could not migrate $name: ${e.javaClass.simpleName}")
        }
    }

    /** Moves one credential-protected SharedPreferences file into device-protected storage. */
    fun migratePreferences(context: Context, name: String) {
        if (!userUnlocked(context)) return
        runCatching {
            deviceProtected(context).moveSharedPreferencesFrom(context.applicationContext, name)
        }
    }

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

    private fun userUnlocked(context: Context): Boolean = try {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
    } catch (_: Exception) {
        false
    }
}
