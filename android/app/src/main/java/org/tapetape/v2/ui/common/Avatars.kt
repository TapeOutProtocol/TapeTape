package org.tapetape.v2.ui.common

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import java.io.File

private const val AVATAR_SIZE = 512

private fun squareScaled(src: Bitmap): Bitmap {
    val side = minOf(src.width, src.height)
    val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    return if (side > AVATAR_SIZE) Bitmap.createScaledBitmap(cropped, AVATAR_SIZE, AVATAR_SIZE, true) else cropped
}

fun saveAvatar(context: Context, bitmap: Bitmap): String {
    val dir = File(context.filesDir, "avatars").apply { mkdirs() }
    val file = File(dir, "avatar-${System.currentTimeMillis()}.jpg")
    file.outputStream().use { squareScaled(bitmap).compress(Bitmap.CompressFormat.JPEG, 90, it) }
    Logx.d("avatar saved ${file.name}")
    return file.absolutePath
}

fun saveAvatar(context: Context, uri: Uri): String? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
    saveAvatar(context, bitmap)
}.onFailure { Logx.w("avatar decode failed", it) }.getOrNull()

@Composable
fun AvatarChooser(hasAvatar: Boolean, onDismiss: () -> Unit, onResult: (String?) -> Unit) {
    val context = LocalContext.current
    val c = LocalTapeTapeColors.current
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        onDismiss()
        uri?.let { saveAvatar(context, it) }?.let(onResult)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        onDismiss()
        bmp?.let { onResult(saveAvatar(context, it)) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(null) else {
            onDismiss()
            toast(context, context.getString(R.string.perm_explain_access_to_camera_denied))
        }
    }
    TapeTapeDialog(onDismiss = onDismiss, title = stringResource(R.string.profile_image_select)) {
        val item: @Composable (Int, () -> Unit) -> Unit = { label, action ->
            Text(
                stringResource(label),
                fontSize = 16.sp,
                color = c.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = action)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
        item(R.string.tab_gallery) { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        item(R.string.camera) { permission.launch(Manifest.permission.CAMERA) }
        if (hasAvatar) item(R.string.profile_image_delete) {
            onDismiss()
            onResult(null)
        }
    }
}
