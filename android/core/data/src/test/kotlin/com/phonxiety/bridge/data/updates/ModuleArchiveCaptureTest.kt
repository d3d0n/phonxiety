package com.phonxiety.bridge.data.updates

import com.phonxiety.bridge.root.FileAssetSource
import com.phonxiety.bridge.root.ModuleZipBuilder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Capturing the pre-update module: it is built from the APK mounted on the system image, published
 * with its identity, and every failure is named rather than turned into an available rollback.
 */
class ModuleArchiveCaptureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `the archive is built from the mounted apk and published with its identity`() = runTest {
        val built = File(temporary.root, "built.zip").apply { writeText("zip-bytes") }
        val builder = FakeBuilder(built)
        val publisher = FakePublisher()
        val mounted = MountedSystemApkVersion.Present(
            "/system/priv-app/phonxiety-bridge/phonxiety-bridge.apk",
            "0.3.0",
            3
        )

        val result = capture(mounted, builder, publisher).capture()

        val expected = PublishedModuleArchive("0.3.0", 3, sha256("zip-bytes"))
        assertEquals(CaptureResult.Captured(expected), result)
        assertEquals(mounted.path, builder.apk?.path)
        assertEquals("0.3.0", builder.versionName)
        assertEquals(3L, builder.versionCode)
        assertEquals("zip-bytes", publisher.archiveText)
        assertEquals(expected, ModuleArchiveMeta.parse(publisher.metadataText))
        assertFalse(built.exists())
    }

    /**
     * The transition this card introduces: the app has already updated and asks for a grant the
     * running module does not have, so the module install has to replace the overlay. The generation
     * kept before that install is the payload that runs now — its mounted APK and its own copy of
     * the allowlist — recorded with its identity, never a rebuild from the newer running app.
     */
    @Test
    fun `a generation captured before the module update keeps its own apk and allowlist`() = runTest {
        val currentAssets = magiskDirectory()
        val currentAllowlist = File(currentAssets, ModuleZipBuilder.ALLOWLIST).readText()
        // The generation's assets are the ones the running app carries; the marker stands for the
        // files of that build, so a rebuild over today's assets is distinguishable from this archive.
        val generationAllowlist = currentAllowlist.replace(
            "<permissions>",
            "<permissions>\n    <!-- captured with the 0.3.0 payload -->"
        )
        assertFalse("the marker must change the bytes", generationAllowlist == currentAllowlist)
        val generationAssets = File(temporary.root, "generation-assets").apply {
            currentAssets.copyRecursively(this, overwrite = true)
        }
        File(generationAssets, ModuleZipBuilder.ALLOWLIST).writeText(generationAllowlist)

        val mountedApk = File(temporary.root, "mounted.apk").apply {
            writeBytes("module apk 0.3.0 as the system image mounts it".toByteArray())
        }
        val runningApk = File(temporary.root, "running.apk").apply {
            writeBytes("app apk 0.4.0, the build that runs after the update".toByteArray())
        }

        val publisher = CopyPublisher(File(temporary.root, "published/platform.zip"))
        val result = capture(
            mounted = MountedSystemApkVersion.Present(mountedApk.path, "0.3.0", 3),
            builder = RealBuilder(generationAssets, File(temporary.root, "build.zip")),
            publisher = publisher
        ).capture()

        val captured = result as? CaptureResult.Captured ?: error("the generation was not captured: $result")
        assertEquals("0.3.0", captured.archive.versionName)
        assertEquals(3L, captured.archive.versionCode)
        val archive = checkNotNull(publisher.archive) { "nothing was published" }
        assertEquals("the identity is the published bytes", sha256(archive), captured.archive.sha256)

        ZipFile(archive).use { zip ->
            val apkBytes = zip.getInputStream(checkNotNull(zip.getEntry(ModuleZipBuilder.APK_ENTRY)))
                .use { it.readBytes() }
            val allowlist = zip.getInputStream(checkNotNull(zip.getEntry(ModuleZipBuilder.ALLOWLIST)))
                .bufferedReader()
                .use { it.readText() }

            assertEquals(String(mountedApk.readBytes()), String(apkBytes))
            assertFalse(
                "the archive carries the running app instead of the mounted one",
                String(apkBytes) == String(runningApk.readBytes())
            )
            assertEquals("the archive keeps the generation's own XML", generationAllowlist, allowlist)
            assertFalse(
                "the archive was rebuilt over today's allowlist",
                allowlist == currentAllowlist
            )
        }
    }

    @Test
    fun `a build failure is reported and nothing is published`() = runTest {
        val publisher = FakePublisher()
        val result = capture(
            MountedSystemApkVersion.Present("/system/x.apk", "0.3.0", 3),
            FakeBuilder(error = IllegalStateException("the mounted APK is unreadable")),
            publisher
        ).capture()

        assertEquals(CaptureResult.Failed("the mounted APK is unreadable"), result)
        assertFalse(publisher.called)
    }

    @Test
    fun `a refused publish is reported`() = runTest {
        val result = capture(
            MountedSystemApkVersion.Present("/system/x.apk", "0.3.0", 3),
            FakeBuilder(File(temporary.root, "built.zip").apply { writeText("zip") }),
            FakePublisher(ok = false)
        ).capture()

        assertTrue((result as CaptureResult.Failed).detail.contains("/data/adb/phonxiety"))
    }

    @Test
    fun `nothing is captured when no module is mounted or the apk cannot be read`() = runTest {
        assertEquals(
            CaptureResult.NothingToCapture,
            capture(MountedSystemApkVersion.Absent, FakeBuilder(error = AssertionError("must not build"))).capture()
        )
        assertEquals(
            CaptureResult.Failed("the mounted system APK could not be read"),
            capture(MountedSystemApkVersion.Unreadable, FakeBuilder(error = AssertionError("must not build"))).capture()
        )
    }

    private fun capture(
        mounted: MountedSystemApkVersion,
        builder: ModuleArchiveBuilder,
        publisher: ModuleArchivePublisher = FakePublisher()
    ) = ModuleArchiveCapture(
        mounted = { mounted },
        builder = builder,
        publisher = publisher,
        workspace = File(temporary.root, "workspace")
    )

    private fun sha256(text: String): String = sha256(text.toByteArray())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun magiskDirectory(): File {
        val candidates = listOf(File("../../magisk"), File("android/magisk"), File("../magisk"))
        return candidates.firstOrNull { File(it, "module.prop").isFile }
            ?: throw AssertionError("android/magisk was not found from ${File(".").absolutePath}")
    }

    /** The production builder over a transaction's asset directory; the archive is real zip bytes. */
    private class RealBuilder(
        private val assets: File,
        private val output: File
    ) : ModuleArchiveBuilder {
        override suspend fun build(apk: File, versionName: String, versionCode: Long): File {
            output.delete()
            return ModuleZipBuilder(
                assets = FileAssetSource(assets),
                apkFile = apk,
                appVersion = versionName,
                appVersionCode = versionCode.toInt(),
                outputFile = output
            ).build()
        }
    }

    /** Publishes the produced bytes unchanged, the way the root file publisher does. */
    private class CopyPublisher(private val destination: File) : ModuleArchivePublisher {
        var archive: File? = null

        override suspend fun publish(archive: File, metadata: File): Boolean {
            destination.parentFile?.mkdirs()
            archive.copyTo(destination, overwrite = true)
            this.archive = destination
            return true
        }

        override suspend fun read(): RollbackArchiveState = RollbackArchiveState.Missing

        override suspend fun readGeneration(sha256: String): RollbackArchiveState = RollbackArchiveState.Missing
    }

    private class FakeBuilder(
        private val output: File? = null,
        private val error: Throwable? = null
    ) : ModuleArchiveBuilder {
        var apk: File? = null
        var versionName: String? = null
        var versionCode: Long? = null

        override suspend fun build(apk: File, versionName: String, versionCode: Long): File {
            error?.let { throw it }
            this.apk = apk
            this.versionName = versionName
            this.versionCode = versionCode
            return checkNotNull(output)
        }
    }

    private class FakePublisher(private val ok: Boolean = true) : ModuleArchivePublisher {
        var called = false
        var archiveText: String? = null
        var metadataText: String? = null

        override suspend fun publish(archive: File, metadata: File): Boolean {
            called = true
            archiveText = archive.readText()
            metadataText = metadata.readText()
            return ok
        }

        override suspend fun read(): RollbackArchiveState = RollbackArchiveState.Missing

        override suspend fun readGeneration(sha256: String): RollbackArchiveState = RollbackArchiveState.Missing
    }
}
