package flowmobile.add_to_gallery

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

class AddToGalleryPlugin : FlutterPlugin, MethodCallHandler {

    private lateinit var context: Context
    private lateinit var channel: MethodChannel

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "add_to_gallery")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (call.method == "addToGallery") {
            val album = call.argument<String>("album")!!
            val path = call.argument<String>("path")!!
            val contentResolver: ContentResolver = context.contentResolver;
            try {
                val file = File(path)
                if (!file.exists()) {
                    result.error("file_not_exist", "File not found at path: $path", null)
                    return
                }
                // Determine MIME type from file extension
                val extension = MimeTypeMap.getFileExtensionFromUrl(file.path)
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream" // Default if type not found

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    //
                    // Android 9 and below
                    //
                    val filepath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val dir = File(filepath.absolutePath.toString() + "/$album/")
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    val fileCopy = File(dir, File(path).name)
                    try {
                        val output = FileOutputStream(fileCopy)
                        val inS: InputStream = FileInputStream(File(path))
                        val buf = ByteArray(1024)
                        var len: Int
                        while (inS.read(buf).also { len = it } > 0) {
                            output?.write(buf, 0, len)
                        }
                        inS.close()
                        output.close()
                        // Copy image into  Gallery
                        val values = ContentValues()
                        val collectionUri: Uri

                        when {
                            mimeType.startsWith("image/") -> {
                                collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                values.put(MediaStore.Images.Media.TITLE, fileCopy.name)
                                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileCopy.name)
                                values.put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                                values.put(MediaStore.MediaColumns.DATA, fileCopy.path)
                            }
                            mimeType.startsWith("video/") -> {
                                collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                values.put(MediaStore.Video.Media.TITLE, fileCopy.name)
                                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileCopy.name)
                                values.put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                                values.put(MediaStore.MediaColumns.DATA, fileCopy.path)
                            }
                            // Add audio or other types if needed
                            else -> {
                                // Handle unsupported type - maybe default to Images or throw error?
                                // For now, defaulting to Images might cause issues with non-media files.
                                // Let's return an error for unsupported types for clarity.
                                result.error("unsupported_type", "Unsupported MIME type: $mimeType", null)
                                return
                            }
                        }
                        try {
                            contentResolver.insert(collectionUri, values)
                            result.success(fileCopy.path)
                        } catch (e: java.lang.Exception) {
                            e.printStackTrace()
                            result.error("error", e.message, e.toString())
                        }
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                        result.error("error", e.message, e.toString())
                    }
                } else {
                    //
                    // Android 10 and above
                    //
                    val collection: Uri
                    val relativePath: String

                    when {
                        mimeType.startsWith("image/") -> {
                            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            relativePath = Environment.DIRECTORY_PICTURES
                        }
                        mimeType.startsWith("video/") -> {
                            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            relativePath = Environment.DIRECTORY_MOVIES
                        }
                        mimeType.startsWith("audio/") -> {
                            collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            relativePath = Environment.DIRECTORY_MUSIC
                        }
                        else -> {
                            // On Q+, we can use Downloads for generic types
                            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            relativePath = Environment.DIRECTORY_DOWNLOADS
                        }
                    }

                    val value = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, File(path).name)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativePath/$album")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }

                    val resolver = contentResolver
                    val item = resolver.insert(collection, value)
                    if (item != null) {
                        try {
                            resolver.openOutputStream(item).use { out ->
                                val inS: InputStream = FileInputStream(File(path))
                                val buf = ByteArray(1024)
                                var len: Int
                                while (inS.read(buf).also { len = it } > 0) {
                                    out?.write(buf, 0, len)
                                }
                                inS.close()
                                out?.close()
                            }
                            value.clear()
                            value.put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(item, value, null, null)
                            result.success(getRealPathFromURI(context, item))
                        } catch (e: Exception) {
                            // If writing fails, delete the pending item
                            resolver.delete(item, null, null)
                            e.printStackTrace()
                            result.error("write_failed", "Failed to write file to MediaStore", e.toString())
                        }
                    } else {
                        result.error("insert_failed", "Failed to insert item into MediaStore", null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                result.error("error", e.message, e.toString())
            }
        } else {
            result.notImplemented()
        }
    }

    fun getRealPathFromURI(context: Context, contentUri: Uri?): String? {
        var cursor: Cursor? = null
        return try {
            val proj = arrayOf(MediaStore.Images.Media.DATA)
            cursor = context.contentResolver.query(contentUri!!, proj, null, null, null)
            val column_index: Int = cursor!!.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            cursor.getString(column_index)
        } finally {
            cursor?.close()
        }
    }
}
