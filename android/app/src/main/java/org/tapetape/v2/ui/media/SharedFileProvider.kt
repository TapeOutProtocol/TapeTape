package org.tapetape.v2.ui.media

import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import org.tapetape.v2.Logx
import java.io.FileNotFoundException

class SharedFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = super.openFile(checked(uri), mode)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        super.query(checked(uri), projection, selection, selectionArgs, sortOrder)

    override fun getType(uri: Uri): String? = super.getType(checked(uri))

    private fun checked(uri: Uri): Uri {
        val s = uri.pathSegments
        val ok = ".." !in s && when (s.firstOrNull()) {
            "accounts" -> s.size >= 4 && s[1].all(Char::isDigit) && s[2] == "blobs"
            "blobs", "outgoing" -> s.size >= 2
            else -> false
        }
        if (!ok) {
            Logx.w("shared file refused ${uri.path}")
            throw FileNotFoundException(uri.path)
        }
        return uri
    }
}
