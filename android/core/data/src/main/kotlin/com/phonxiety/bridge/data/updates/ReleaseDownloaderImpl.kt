package com.phonxiety.bridge.data.updates

import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.di.IoDispatcher
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** One artifact from the daemon's update directory, streamed into [sink]. */
fun interface UpdateArtifactSource {
    suspend fun fetch(name: String, sink: OutputStream): ApiResult
}

/**
 * Fetches a release artifact from whichever source the document names.
 *
 * `daemon:` sources read `<state dir>/updates/<name>` through
 * `/app/v1/update/artifact?name=…` on the authenticated device link, so a release published by
 * `mise run phone:publish --serve` installs with no hosting at all. https stays the alternative for
 * a release uploaded somewhere else. Both paths digest while they stream: the SHA-256 that is
 * verified is the SHA-256 of the bytes on disk.
 */
@Singleton
class ReleaseDownloaderImpl @Inject constructor(
    private val artifacts: UpdateArtifactSource,
    @IoDispatcher private val io: CoroutineDispatcher
) : ReleaseDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun download(source: ReleaseSource, destination: File, onProgress: (Float?) -> Unit): DownloadedFile = when (source) {
        is ReleaseSource.Https -> downloadHttps(source.url, destination, onProgress)
        is ReleaseSource.Daemon -> downloadFromDaemon(source.name, destination, onProgress)
    }

    private suspend fun downloadHttps(url: String, destination: File, onProgress: (Float?) -> Unit): DownloadedFile = withContext(io) {
        ReleaseUrls.requireHttps(url, "the release URL")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("the server answered HTTP ${response.code} for $url")
            }
            val body = response.body
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_BYTES) {
                throw IllegalStateException("the release is $declaredLength bytes, over the $MAX_BYTES byte limit")
            }
            destination.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            destination.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        requireWithinLimit(total)
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(declaredLength.takeIf { it > 0 }?.let { (total.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
            DownloadedFile(destination, digest.digest().toHex(), total)
        }
    }

    private suspend fun downloadFromDaemon(name: String, destination: File, onProgress: (Float?) -> Unit): DownloadedFile = withContext(io) {
        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        destination.outputStream().use { fileStream ->
            val sink = object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    total += length
                    requireWithinLimit(total)
                    digest.update(bytes, offset, length)
                    fileStream.write(bytes, offset, length)
                }

                override fun flush() = fileStream.flush()

                // The surrounding `use` owns the file; the daemon only sees a sink.
                override fun close() = Unit
            }
            // The attachment carries no length; the bar is indeterminate until it finishes.
            onProgress(null)
            when (val result = artifacts.fetch(name, sink)) {
                is ApiResult.Success -> Unit

                is ApiResult.Failure -> {
                    destination.delete()
                    throw IllegalStateException("the daemon refused $name: ${result.code}: ${result.message}")
                }
            }
        }
        DownloadedFile(destination, digest.digest().toHex(), total)
    }

    private fun requireWithinLimit(total: Long) {
        if (total > MAX_BYTES) {
            throw IllegalStateException("the release exceeded the $MAX_BYTES byte limit")
        }
    }

    private companion object {
        const val BUFFER_BYTES: Int = 64 * 1024

        /** Far above the APK and module zip; a release larger than this is not ours. */
        const val MAX_BYTES: Long = 256L * 1024 * 1024
    }
}

internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        out[index * 2] = HEX[value ushr 4]
        out[index * 2 + 1] = HEX[value and 0x0F]
    }
    return String(out)
}

private const val HEX = "0123456789abcdef"
