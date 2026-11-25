package org.tapetape.v2.ui.media

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.ui.common.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileActions {
    private fun uri(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))

    fun open(context: Context, path: String, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, path), Intent.normalizeMimeType(mime))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Logx.i("open $mime $path")
        } catch (e: ActivityNotFoundException) {
            Logx.w("no app to open $mime")
            toast(context, context.getString(R.string.no_app_to_handle_data))
        }
    }

    fun share(context: Context, paths: List<String>, mimes: List<String>) {
        val uris = paths.map { uri(context, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = commonMime(mimes)
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Logx.i("share ${uris.size} files type=${intent.type}")
    }

    suspend fun export(context: Context, path: String, name: String, mime: String): Boolean =
        copyToPublic(context, path, withExtension(name, mime), mime, Environment.DIRECTORY_DOWNLOADS)

    internal suspend fun saveToGallery(context: Context, path: String, mime: String): Boolean {
        val dir = if (isVideo(mime)) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        return copyToPublic(context, path, withExtension(File(path).name, mime), mime, "$dir/TapeTape")
    }

    private fun commonMime(mimes: List<String>): String {
        val distinct = mimes.distinct()
        if (distinct.size == 1) return distinct[0]
        val families = distinct.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1) "${families[0]}/*" else "*/*"
    }

    private fun withExtension(name: String, mime: String): String {
        if (name.contains('.')) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return name
        return "$name.$ext"
    }

    private suspend fun copyToPublic(context: Context, path: String, name: String, mime: String, relativeDir: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) insertMediaStore(context, path, name, mime, relativeDir) else writeLegacy(context, path, name, mime, relativeDir)
                Logx.i("exported $name to $relativeDir")
                true
            }.getOrElse {
                Logx.e("export $path to $relativeDir failed", it)
                false
            }
        }

    private fun insertMediaStore(context: Context, path: String, name: String, mime: String, relativeDir: String) {
        val collection = when {
            relativeDir.startsWith(Environment.DIRECTORY_DOWNLOADS) -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            relativeDir.startsWith(Environment.DIRECTORY_MOVIES) -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(collection, values) ?: error("insert returned null")
        try {
            resolver.openOutputStream(target)!!.use { out -> File(path).inputStream().use { it.copyTo(out) } }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(context: Context, path: String, name: String, mime: String, relativeDir: String) {
        val dir = File(Environment.getExternalStorageDirectory(), relativeDir).apply { mkdirs() }
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var target = File(dir, name)
        var n = 1
        while (target.exists()) target = File(dir, "$base (${n++})$ext")
        File(path).copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
    }
}

@Composable
fun rememberStorageAccess(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Logx.i("storage permission granted=$granted")
        if (granted) pending?.invoke() else toast(context, context.getString(R.string.perm_explain_access_to_storage_denied))
        pending = null
    }
    return remember(launcher) {
        { action ->
            val granted = Build.VERSION.SDK_INT >= 29 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                action()
            } else {
                pending = action
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}
