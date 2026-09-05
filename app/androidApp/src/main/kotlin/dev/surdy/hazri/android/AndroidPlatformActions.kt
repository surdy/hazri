package dev.surdy.hazri.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.surdy.hazri.ui.PlatformActions
import java.io.File

/**
 * Clipboard, share and the notification permission: what the UI needs the platform for.
 *
 * The export goes through a [FileProvider] rather than an `EXTRA_TEXT` string: a session
 * JSON is tens of kilobytes, which is past what many receiving apps will accept as an
 * extra, and a file is what a spreadsheet wants anyway.
 */
class AndroidPlatformActions(
    private val context: Context,
    /**
     * Asks for POST_NOTIFICATIONS. A lambda because the request needs the Activity's
     * result registry, and this object is built on the application context.
     */
    private val requestNotifications: () -> Unit = {},
) : PlatformActions {

    override fun requestSurveyNotificationPermission() = requestNotifications()

    override fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override fun shareText(fileName: String, content: String) {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val file = File(directory, fileName).apply { writeText(content) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (fileName.endsWith(".json")) "application/json" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Export session").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private companion object {
        /** Must match the `cache-path` in `res/xml/file_paths.xml`. */
        const val EXPORT_DIRECTORY = "exports"
    }
}
