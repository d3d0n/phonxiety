package com.phonxiety.bridge.data.updates

import java.io.File
import java.security.MessageDigest
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

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

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
