package com.phonxiety.bridge.feature.calls

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.phonxiety.bridge.model.TranscriptTurn
import java.io.File

/** Files the detail screen hands to other apps; the provider is declared by the app module. */
internal object CallSharing {
    fun audioFile(context: Context, callId: String): File = File(context.cacheDir, "call-audio/$callId.m4a")

    fun transcriptFile(context: Context, callId: String, turns: List<TranscriptTurn>): File {
        val file = File(context.cacheDir, "call-transcripts/$callId.txt")
        file.parentFile?.mkdirs()
        file.writeText(
            turns.joinToString("\n") { turn ->
                "${turn.role ?: "unknown"}: ${turn.text.orEmpty()}"
            }
        )
        return file
    }

    fun shareFile(context: Context, file: File, mimeType: String, subject: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
