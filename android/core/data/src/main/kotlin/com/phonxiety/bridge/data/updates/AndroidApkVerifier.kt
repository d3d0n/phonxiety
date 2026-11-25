package com.phonxiety.bridge.data.updates

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks a downloaded APK before it reaches `PackageInstaller`: its SHA-256, its package name, and
 * its signers. The signer set of the archive must equal the installed app's, and the pinned
 * certificate SHA-256 from the update document must be one of them — a valid signature from another
 * key is not an update, it is a different app that Android would refuse anyway.
 */
@Singleton
class AndroidApkVerifier @Inject constructor(
    @ApplicationContext private val context: Context
) : ApkVerifier {
    private val packageManager: PackageManager = context.applicationContext.packageManager

    override fun verify(file: File, expectedSha256: String, expectedSigningCertSha256: String): ApkVerification {
        val actualSha = file.sha256()
        if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
            return ApkVerification.HashMismatch(expectedSha256, actualSha)
        }
        val archive = runCatching {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        }.getOrNull() ?: return ApkVerification.NotAnApk
        if (archive.packageName != context.packageName) {
            return ApkVerification.WrongPackage(archive.packageName)
        }
        val archiveSigners = archive.signingInfo?.apkContentsSigners?.map { it.digest() }?.toSet().orEmpty()
        val installedSigners = installedSignerDigests()
            ?: return ApkVerification.SigningCertMismatch(setOf(expectedSigningCertSha256.lowercase()), emptySet())
        if (archiveSigners.isEmpty()) {
            return ApkVerification.SigningCertMismatch(installedSigners, archiveSigners)
        }
        val pinned = expectedSigningCertSha256.lowercase()
        if (archiveSigners != installedSigners || pinned !in archiveSigners) {
            return ApkVerification.SigningCertMismatch(installedSigners, archiveSigners)
        }
        return ApkVerification.Valid(archive.versionName, archive.longVersionCode)
    }

    /**
     * SHA-256 of every current signer of the installed app, lowercase hex. Null when
     * `PackageManager` cannot answer; the caller then refuses the install instead of trusting it.
     */
    private fun installedSignerDigests(): Set<String>? {
        val info = runCatching {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        }.getOrNull() ?: return null
        return info.signingInfo?.apkContentsSigners?.map { it.digest() }?.toSet().orEmpty()
    }

    private fun Signature.digest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .toHex()

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }
}
