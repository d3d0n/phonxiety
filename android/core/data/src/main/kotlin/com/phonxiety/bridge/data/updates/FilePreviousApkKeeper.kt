package com.phonxiety.bridge.data.updates

import android.content.Context
import com.phonxiety.bridge.data.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Keeps the APK the next update replaces under `filesDir/updates/previous.apk`.
 *
 * The running APK is copied before every install attempt, but only when the version on disk is not
 * already the kept one: a retried download must not overwrite the only known-good copy with itself.
 * The copy is metadata for the rollback (name, code) and never executed from here.
 */
@Singleton
class FilePreviousApkKeeper @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : PreviousApkKeeper {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIRECTORY)
    private val apk = File(directory, APK_NAME)
    private val properties = File(directory, META_NAME)

    override suspend fun keepCurrentApk(): File? = withContext(io) {
        val source = File(appContext.applicationInfo.sourceDir)
        if (!source.isFile) return@withContext null
        val info = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val code = info?.longVersionCode ?: 0L
        if (apk.isFile && readMeta()?.getProperty(KEY_VERSION_CODE)?.toLongOrNull() == code) {
            return@withContext apk
        }
        directory.mkdirs()
        val temporary = File(directory, "$APK_NAME.tmp")
        runCatching {
            source.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            if (!temporary.renameTo(apk)) {
                apk.delete()
                temporary.renameTo(apk)
            }
            writeMeta(info?.versionName, code)
        }.getOrElse {
            temporary.delete()
            return@withContext null
        }
        apk.takeIf { it.isFile }
    }

    override fun keptApk(): File? = apk.takeIf { it.isFile }

    override fun keptVersionName(): String? = readMeta()?.getProperty(KEY_VERSION_NAME)

    override fun keptVersionCode(): Long = readMeta()?.getProperty(KEY_VERSION_CODE)?.toLongOrNull() ?: 0L

    private fun readMeta(): Properties? {
        if (!properties.isFile) return null
        return runCatching {
            Properties().apply { properties.inputStream().use { load(it) } }
        }.getOrNull()
    }

    private fun writeMeta(versionName: String?, versionCode: Long) {
        val values = Properties().apply {
            setProperty(KEY_VERSION_NAME, versionName ?: "")
            setProperty(KEY_VERSION_CODE, versionCode.toString())
        }
        val temporary = File(directory, "$META_NAME.tmp")
        temporary.outputStream().use { values.store(it, null) }
        if (!temporary.renameTo(properties)) {
            properties.delete()
            temporary.renameTo(properties)
        }
    }

    private companion object {
        const val DIRECTORY: String = "updates"
        const val APK_NAME: String = "previous.apk"
        const val META_NAME: String = "previous.properties"
        const val KEY_VERSION_NAME: String = "versionName"
        const val KEY_VERSION_CODE: String = "versionCode"
    }
}
