package com.phonxiety.bridge.feature.lab

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Cache files the lab shares: diagnostics bundles and the app's own log. */
object LabShare {
    const val AUTHORITY_SUFFIX = ".diagnostics"
    private const val DIRECTORY = "diagnostics"

    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    /** The content URI the provider in this module serves for a cache file. */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

    fun intent(uri: Uri, mimeType: String, title: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
