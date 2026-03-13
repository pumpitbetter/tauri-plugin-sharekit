package app.tauri.share

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.webkit.WebView
import android.net.Uri
import androidx.activity.result.ActivityResult
import app.tauri.annotation.ActivityCallback
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.Plugin
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@InvokeArg
class ShareTextOptions {
    lateinit var text: String
    var mimeType: String = "text/plain"
    var title: String? = null
}

@InvokeArg
class ShareFileOptions {
    lateinit var url: String
    var mimeType: String = "*/*"
    var title: String? = null
}

@InvokeArg
class SaveToGalleryOptions {
    lateinit var url: String
    var mimeType: String = "image/png"
    var filename: String? = null
    var album: String? = null
}

@TauriPlugin
class SharePlugin(private val activity: Activity): Plugin(activity) {
    /**
     * Open the native sharing interface to share some text
     */
    @Command
    fun shareText(invoke: Invoke) {
        val args = invoke.parseArgs(ShareTextOptions::class.java)

        val sendIntent = Intent().apply {
            this.action = Intent.ACTION_SEND
            this.type = args.mimeType
            this.putExtra(Intent.EXTRA_TEXT, args.text)
            this.putExtra(Intent.EXTRA_TITLE, args.title)
        }

        val shareIntent = Intent.createChooser(sendIntent, null)
        startActivityForResult(invoke, shareIntent, "shareTextResult")
    }

    @ActivityCallback
    private fun shareTextResult(invoke: Invoke, result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_CANCELED) {
            invoke.reject("Share cancelled")
            return
        }
        invoke.resolve()
    }

    /**
     * Open the native sharing interface to share a file
     */
    @Command
    fun shareFile(invoke: Invoke) {
        val args = invoke.parseArgs(ShareFileOptions::class.java)
        
        // Get the source file from the URL
        val sourceFile = if (args.url.startsWith("file://")) {
            File(Uri.parse(args.url).path!!)
        } else {
            File(args.url)
        }
        
        // Create a temporary file to store the data
        val tempFile = File(activity.cacheDir, sourceFile.name)
        
        // Copy the source file to the temporary file
        sourceFile.inputStream().use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Get the authority from the app's manifest
        val authority = "${activity.packageName}.fileprovider"

        // Create a content URI for the file
        val contentUri = FileProvider.getUriForFile(activity, authority, tempFile)

        val sendIntent = Intent().apply {
            this.action = Intent.ACTION_SEND
            this.type = args.mimeType
            this.putExtra(Intent.EXTRA_STREAM, contentUri)
            this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            this.putExtra(Intent.EXTRA_TITLE, args.title)
        }

        // Android 10+ requires ClipData so the share sheet can show a preview thumbnail.
        sendIntent.clipData = ClipData.newUri(activity.contentResolver, args.title ?: "", contentUri)

        val shareIntent = Intent.createChooser(sendIntent, args.title)
        startActivityForResult(invoke, shareIntent, "shareFileResult")
    }

    @ActivityCallback
    private fun shareFileResult(invoke: Invoke, result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_CANCELED) {
            invoke.reject("Share cancelled")
            return
        }
        invoke.resolve()
    }

    /**
     * Save an image file to the device gallery via MediaStore.
     * The image will appear in the Photos app under the specified album.
     */
    @Command
    fun saveToGallery(invoke: Invoke) {
        val args = invoke.parseArgs(SaveToGalleryOptions::class.java)

        val sourceFile = if (args.url.startsWith("file://")) {
            File(Uri.parse(args.url).path!!)
        } else {
            File(args.url)
        }

        val displayName = args.filename ?: sourceFile.name
        val albumName = args.album ?: "Pump It Better"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, args.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$albumName")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = activity.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collection, values)
            ?: return invoke.reject("Failed to create MediaStore entry")

        try {
            resolver.openOutputStream(itemUri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: return invoke.reject("Failed to open output stream")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)

            invoke.resolve()
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            invoke.reject("Failed to save to gallery: ${e.message}")
        }
    }
}
