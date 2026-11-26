package com.phonxiety.bridge.data.updates

import android.content.Context
import android.content.pm.PackageManager
import com.phonxiety.bridge.root.ModuleZipBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The APK path the module's overlay mounts, taken from the module layout itself. */
val MOUNTED_SYSTEM_APK_PATH: String = "/" + ModuleZipBuilder.APK_ENTRY

/** Reads the APK the module mounted on the system image: the downgrade floor Android checks. */
fun interface MountedSystemApk {
    fun read(): MountedSystemApkVersion
}

/** `PackageManager` is the only reader: it reports the mounted file's own version, not metadata. */
@Singleton
class AndroidMountedSystemApk @Inject constructor(
    @ApplicationContext context: Context
) : MountedSystemApk {
    private val appContext = context.applicationContext

    override fun read(): MountedSystemApkVersion {
        val path = MOUNTED_SYSTEM_APK_PATH
        if (!File(path).isFile) return MountedSystemApkVersion.Absent
        val info = runCatching {
            appContext.packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
        }.getOrNull() ?: return MountedSystemApkVersion.Unreadable
        if (info.packageName != appContext.packageName) return MountedSystemApkVersion.Unreadable
        return MountedSystemApkVersion.Present(path, info.versionName, info.longVersionCode)
    }
}

/** Builds a module zip around exactly [apk]; production uses `ModuleZipBuilder`, tests fake it. */
fun interface ModuleArchiveBuilder {
    suspend fun build(apk: File, versionName: String, versionCode: Long): File
}

/** `ModuleZipBuilder` over the app's assets, writing into the cache before publication. */
@Singleton
class AndroidModuleArchiveBuilder @Inject constructor(
    @ApplicationContext context: Context
) : ModuleArchiveBuilder {
    private val appContext = context.applicationContext

    override suspend fun build(apk: File, versionName: String, versionCode: Long): File = withContext(Dispatchers.IO) {
        val output = File(appContext.cacheDir, "module-previous-build.zip")
        if (output.isFile && !output.delete()) {
            throw IllegalStateException("could not replace ${output.path}")
        }
        ModuleZipBuilder(
            assets = com.phonxiety.bridge.root.AndroidAssetSource(appContext),
            apkFile = apk,
            appVersion = versionName,
            appVersionCode = versionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            outputFile = output
        ).build()
    }
}

/** What capturing the previous module produced. */
sealed interface CaptureResult {
    data class Captured(val archive: PublishedModuleArchive) : CaptureResult

    /** [detail] names what failed; the module update still installs, but no rollback is claimed. */
    data class Failed(val detail: String) : CaptureResult

    /** No module APK on the system image, so there is no previous module to keep. */
    data object NothingToCapture : CaptureResult
}

/** A capture taken before a module update stages, so the previous payload stays available. */
fun interface PreviousModuleCapture {
    suspend fun capture(): CaptureResult
}

/**
 * Builds the module zip of the APK the module has mounted and publishes it, with its identity,
 * under `/data/adb/phonxiety`. The archive is the actual pre-update payload: it is neither rebuilt
 * from the already-updated running app nor kept in app storage that a data clear would erase.
 */
class ModuleArchiveCapture(
    private val mounted: MountedSystemApk,
    private val builder: ModuleArchiveBuilder,
    private val publisher: ModuleArchivePublisher,
    private val workspace: File
) : PreviousModuleCapture {
    override suspend fun capture(): CaptureResult {
        val mountedApk = mounted.read()
        if (mountedApk is MountedSystemApkVersion.Absent) return CaptureResult.NothingToCapture
        if (mountedApk !is MountedSystemApkVersion.Present) {
            return CaptureResult.Failed("the mounted system APK could not be read")
        }
        val versionName = mountedApk.versionName
        if (versionName.isNullOrBlank()) {
            return CaptureResult.Failed("the mounted system APK has no version name")
        }
        val archive = try {
            builder.build(File(mountedApk.path), versionName, mountedApk.versionCode)
        } catch (error: Exception) {
            return CaptureResult.Failed(error.message ?: "the module archive could not be built")
        }
        return try {
            val identity = PublishedModuleArchive(versionName, mountedApk.versionCode, sha256(archive))
            workspace.mkdirs()
            val metadata = File(workspace, RollbackFiles.ARCHIVE_META)
                .apply { writeText(ModuleArchiveMeta.write(identity)) }
            val published = publisher.publish(archive, metadata)
            metadata.delete()
            if (published) {
                CaptureResult.Captured(identity)
            } else {
                CaptureResult.Failed("the archive could not be published under ${RollbackFiles.DIRECTORY}")
            }
        } catch (error: Exception) {
            CaptureResult.Failed(error.message ?: "the module archive could not be published")
        } finally {
            archive.delete()
        }
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
