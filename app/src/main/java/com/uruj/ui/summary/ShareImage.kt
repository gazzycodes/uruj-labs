package com.uruj.ui.summary

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Saves a captured summary bitmap to the app's external `shared/` folder, then
 * returns a content:// URI via FileProvider so we can hand it to WhatsApp /
 * Instagram / system share without granting raw file-system access. The user can
 * overlay this on their selfie in any photo editor afterwards.
 */
object ShareImage {
    fun save(context: Context, bitmap: Bitmap, sessionId: String): Uri {
        val sharedDir = File(context.getExternalFilesDir(null), "shared").apply { mkdirs() }
        val file = File(sharedDir, "uruj-ride-$sessionId.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    fun launchShareIntent(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "URUJ Labs · my own training computer · صعود")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share ride to…").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
